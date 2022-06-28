import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import com.softwaremill.quicklens._
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._

import codecs._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import crypto.Crypto

import Utils.OnionParseResult

// -- questions:
// should we fail all pending incoming htlcs on our actual normal node side whenever we fail the channel (i.e. send an Error message -- or receive an error message?)
//   answer: no, as they can still be resolve manually.
//   instead we should fail them whenever the timeout expires on the hosted channel side

type PaymentStatus = Option[Either[Option[PaymentFailure], ByteVector32]]

sealed trait PaymentFailure
case class FailureOnion(onion: ByteVector) extends PaymentFailure
case class NormalFailureMessage(message: FailureMessage) extends PaymentFailure

case class FromLocal(upd: ChannelModifier)
case class FromRemote(upd: ChannelModifier)

class Channel(peerId: ByteVector) {
  lazy val channelId = Utils.getChannelId(Main.node.ourPubKey, peerId)
  lazy val shortChannelId = Utils.getShortChannelId(Main.node.ourPubKey, peerId)

  var state = ChannelState(peerId)
  val logger = Main.logger.attach.item("peer", peerId.toHex).logger

  def sendMessage(
      msg: HostedClientMessage | HostedServerMessage
  ): Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, msg)

  // a update_add_htlc we've received from the upstream node
  // (for c-lightning this comes from the "htlc_accepted" hook)
  def addHTLC(
      incoming: HtlcIdentifier,
      incomingAmount: MilliSatoshi,
      outgoingAmount: MilliSatoshi,
      paymentHash: ByteVector32,
      cltvExpiry: CltvExpiry,
      nextOnion: ByteVector
  ): Future[PaymentStatus] = {
    val localLogger =
      Main.logger.attach.item(state.status).item("hash", paymentHash).logger
    localLogger.debug
      .item("incoming", incoming)
      .item("in-amount", incomingAmount)
      .item("out-amount", outgoingAmount)
      .item("cltv", cltvExpiry)
      .msg("addHTLC")

    var promise = Promise[PaymentStatus]()

    if (state.status != Active) {
      Main.log("can't add an HTLC in a channel that isn't active")
      promise.success(None)
    } else if (
      state.lcssNext.incomingHtlcs
        .exists(_.paymentHash == paymentHash)
    ) {
      // reject htlc as outgoing if it's already incoming, sanity check
      Main.log(
        s"${paymentHash} is already incoming, can't add it as outgoing"
      )
      promise.success(None)
    } else if (
      Database.data.htlcForwards
        .get(incoming) == Some(HtlcIdentifier(shortChannelId, _))
    ) {
      // do not add htlc to state if it's already there (otherwise the state will be invalid)
      // this is likely to be hit on reboots as the upstream node will replay pending htlcs on us
      Main.log("won't forward the htlc as it's already there")

      // but we still want to update the callbacks we're keeping track of (because we've rebooted!)
      val htlc = (for {
        outgoing <- Database.data.htlcForwards.get(incoming)
        entry <- Database.data.channels.find((p, _) =>
          Utils.getShortChannelId(Main.node.ourPubKey, p) == outgoing.scid
        )
        chandata = entry._2
        htlc <- state.lcss.outgoingHtlcs.find(htlc => htlc.id == outgoing.id)
      } yield htlc).get

      state = state.copy(htlcResults = state.htlcResults + (htlc.id -> promise))
    } else {
      // the default case in which we add a new htlc
      // create update_add_htlc based on the prototype we've received
      val htlc = UpdateAddHtlc(
        channelId = channelId,
        id = state.lcssNext.localUpdates.toULong + 1L.toULong,
        paymentHash = paymentHash,
        amountMsat = outgoingAmount,
        cltvExpiry = cltvExpiry,
        onionRoutingPacket = nextOnion
      )

      // prepare modification to new lcss to be our next
      val upd = FromLocal(htlc)
      val updated = state.addUncommittedUpdate(upd)

      // check a bunch of things, if any fail return a temporary_channel_failure
      val requiredFee = MilliSatoshi(
        Main.config.feeBase.toLong + (Main.config.feeProportionalMillionths * htlc.amountMsat.toLong / 1000000L)
      )
      if (
        (htlc.cltvExpiry.blockHeight - Main.currentBlock).toInt < Main.config.cltvExpiryDelta.toInt ||
        (incomingAmount - htlc.amountMsat) < requiredFee ||
        updated.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
        updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
      ) {
        Main.log(
          s"failing ${(htlc.cltvExpiry.blockHeight - Main.currentBlock).toInt} < ${} == ${(htlc.cltvExpiry.blockHeight - Main.currentBlock).toInt < Main.config.cltvExpiryDelta.toInt}; ${incomingAmount - htlc.amountMsat} >= ${requiredFee} == ${(incomingAmount - htlc.amountMsat) >= requiredFee}; ${updated.lcssNext.localBalanceMsat < MilliSatoshi(
              0L
            )} ${updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)}"
        )
        promise.success(
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  TemporaryChannelFailure(getChannelUpdate)
                )
              )
            )
          )
        )
      } else {
        // will send update_add_htlc to hosted client
        //
        // but first we update the database with the mapping between received and sent htlcs
        Database.update { data =>
          data
            .modify(_.htlcForwards)
            .using(
              _ + (incoming -> HtlcIdentifier(shortChannelId, htlc.id))
            )
        }
        // and we update the state to include this uncommitted htlc
        // and add to the callbacks we're keeping track of for the upstream node
        state =
          updated.copy(htlcResults = state.htlcResults + (htlc.id -> promise))

        sendMessage(htlc)
          .onComplete {
            case Success(_) =>
              // success here means the client did get our update_add_htlc,
              // so send our signed state_update
              sendMessage(updated.lcssNext.stateUpdate)
            case Failure(err) => {
              // client is offline and can't take our update_add_htlc,
              // so we fail it on upstream
              // and remove it from the list of uncommitted updates
              Main.log(s"failed to send update_add_htlc to $peerId: $err")
              promise.success(None)
              state = state.removeUncommitedUpdate(upd)
            }
          }
      }
    }

    promise.future
      .andThen { case Success(status) =>
        status match {
          case Some(Right(preimage)) =>
            Main.log(
              s"[add-htlc] $shortChannelId routed ${paymentHash} successfully: $preimage"
            )
          case Some(Left(Some(FailureOnion(_)))) =>
            Main.log(
              s"[add-htlc] $shortChannelId received failure onion for ${paymentHash}"
            )
          case Some(Left(_)) =>
            Main.log(s"[add-htlc] $shortChannelId failed ${paymentHash}")
          case None =>
            Main.log(s"[add-htlc] $shortChannelId didn't handle ${paymentHash}")
        }
      }
  }

  def gotPaymentResult(
      htlcId: ULong,
      status: PaymentStatus
  ): Unit = {
    val localLogger = logger.attach
      .item(state.status)
      .item("htlc", htlcId)
      .item("payment", status)
      .logger

    localLogger.debug.item(state).msg("gotPaymentResult")

    if (status.isEmpty) {
      // payment still pending
    } else if (
      state.status != Active && state.status != Errored && state.status != Suspended
    ) {
      // these are the 3 states in which we will still accept results, otherwise do nothing
      localLogger.warn
        .msg(
          "got a payment result, but we are not in an acceptable channel state"
        )
    } else
      status.get match {
        case Right(preimage) => {
          // since this comes from the upstream node it is assumed the preimage is valid
          val fulfill = UpdateFulfillHtlc(
            channelId,
            htlcId,
            preimage
          )

          // migrate our state to one containing this uncommitted update
          val upd = FromLocal(fulfill)
          val updated = state.addUncommittedUpdate(upd)
          state = updated

          // save the preimage so if we go offline we can keep trying to send it or resolve manually
          Database.update { data =>
            data
              .modify(_.preimages)
              .using(_ + (Crypto.sha256(preimage) -> preimage))
          }

          // we will send this immediately to the client and hope he will acknowledge it
          sendMessage(fulfill)
            .onComplete {
              case Success(_) => {
                sendMessage(updated.lcssNext.stateUpdate)
              }
              case Failure(err) => {
                // client is offline and can't take our update_fulfill_htlc,
                // so we remove it from the list of uncommitted updates
                // and wait for when the peer becomes online again
                localLogger.warn
                  .item(err)
                  .msg("failed to send update_fulfill_htlc")
                state = state.removeUncommitedUpdate(upd)
              }
            }
        }
        case Left(failure) => {
          (for {
            htlc <- state.lcssNext.incomingHtlcs.find(_.id == htlcId)
            OnionParseResult(packet, _, sharedSecret) <- Utils
              .parseClientOnion(htlc)
              .toOption
            fail = failure match {
              case Some(NormalFailureMessage(bo: BadOnion)) =>
                UpdateFailMalformedHtlc(
                  htlc.channelId,
                  htlc.id,
                  bo.onionHash,
                  bo.code
                )
              case _ => {
                val reason = failure.getOrElse(
                  NormalFailureMessage(
                    TemporaryChannelFailure(getChannelUpdate)
                  )
                ) match {
                  case NormalFailureMessage(fm) =>
                    Sphinx.FailurePacket.create(sharedSecret, fm)
                  case FailureOnion(fo) =>
                    Sphinx.FailurePacket.wrap(fo, sharedSecret)
                }

                UpdateFailHtlc(channelId, htlcId, reason)
              }
            }
          } yield fail)
            .foreach { fail =>
              // prepare updated state
              val upd = FromLocal(fail)
              state = state.addUncommittedUpdate(upd)

              sendMessage(fail)
                .onComplete {
                  case Success(_) => {
                    sendMessage(state.lcssNext.stateUpdate)
                  }
                  case Failure(err) => {
                    // client is offline and can't take our update_fulfill_htlc,
                    // so we remove it from the list of uncommitted updates
                    // and wait for when the peer becomes online again
                    localLogger.warn
                      .item("err", err)
                      .msg(s"failed to send update_fail_htlc")
                    state = state.removeUncommitedUpdate(upd)
                  }
                }
            }
        }
      }
  }

  def gotPeerMessage(
      message: HostedClientMessage | HostedServerMessage
  ): Unit = {
    val localLogger = logger.attach.item(state.status).logger

    localLogger.debug
      .item("state", state)
      .item("message", message)
      .msg("gotPeerMessage")

    message match {
      // someone wants a new hosted channel from us
      case msg: InvokeHostedChannel
          if state.status == NotOpened || state.status == Suspended => {
        // check chain hash
        if (msg.chainHash != Main.chainHash) {
          localLogger.warn
            .item("local", Main.chainHash)
            .item("remote", msg.chainHash)
            .msg(s"peer sent InvokeHostedChannel for wrong chain")
          sendMessage(
            Error(
              channelId,
              s"invalid chainHash (local=${Main.chainHash} remote=${msg.chainHash})"
            )
          )
        } else {
          // chain hash is ok, proceed
          state.data.lcss match {
            case Some(lcss) => {
              state = state.copy(openingRefundScriptPubKey =
                Some(msg.refundScriptPubKey)
              )

              // channel already exists, so send last cross-signed-state
              sendMessage(lcss)
            }
            case None => {
              state = state.copy(openingRefundScriptPubKey =
                Some(msg.refundScriptPubKey)
              )

              // reply saying we accept the invoke
              sendMessage(Main.ourInit)
            }
          }
        }
      }

      // final step of channel open process
      case msg: StateUpdate if state.status == Opening => {
        // build last cross-signed state for the beginning of channel
        val lcssInitial = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = state.openingRefundScriptPubKey.get,
          initHostedChannel = Main.ourInit,
          blockDay = msg.blockDay,
          localBalanceMsat =
            Main.ourInit.channelCapacityMsat - Main.ourInit.initialClientBalanceMsat,
          remoteBalanceMsat = Main.ourInit.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = Nil,
          outgoingHtlcs = Nil,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = msg.localSigOfRemoteLCSS
        )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        // step out of the "opening" state
        state = state.copy(openingRefundScriptPubKey = None)

        // check if everything is ok
        if ((msg.blockDay - Main.currentBlockDay).abs > 1) {
          localLogger.warn
            .item("local", Main.currentBlockDay)
            .item("remote", msg.blockDay)
            .msg("peer sent state_update with wrong blockday")
          sendMessage(
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
        } else if (!lcssInitial.verifyRemoteSig(peerId)) {
          localLogger.warn.msg("peer sent state_update with wrong signature.")
          sendMessage(
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          )
        } else {
          // all good, save this channel to the database and consider it opened
          Database.update { data =>
            data
              .modify(_.channels)
              .using(_ + (peerId -> ChannelData(lcss = Some(lcssInitial))))
          }

          // send our signed state update
          sendMessage(lcssInitial.stateUpdate)

          // send a channel update
          sendMessage(getChannelUpdate)
        }
      }

      // when the client tries to invoke it we return the error
      case _: InvokeHostedChannel if state.status == Errored =>
        sendMessage(state.data.localErrors.head)

      // a client was just turned on and is sending this to sync states
      case msg: LastCrossSignedState => {
        val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(peerId)

        if (!isLocalSigOk || !isRemoteSigOk) {
          val err = if (!isLocalSigOk) {
            localLogger.warn.msg(
              "peer sent LastCrossSignedState with a signature that isn't ours"
            )
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            localLogger.warn.msg(
              "peer sent LastCrossSignedState with an invalid signature"
            )
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          sendMessage(err)
          Database.update { data =>
            data
              .modify(_.channels.at(peerId).localErrors)
              .using(_ :+ err)
          }
        } else if (state.status == Active) {
          val lcssMostRecent =
            if (
              (state.lcss.localUpdates + state.lcss.remoteUpdates) >=
                (msg.remoteUpdates + msg.localUpdates)
            ) {
              // we are even or ahead
              state.lcss
            } else {
              // we are behind
              localLogger.warn
                .item(
                  "local",
                  s"${state.lcss.localUpdates}/${state.lcss.remoteUpdates}"
                )
                .item("remote", s"${msg.remoteUpdates}/${msg.localUpdates}")
                .msg("peer sent lcss showing that we are behind")

              // save their lcss here
              Database.update { data =>
                data
                  .modify(_.channels.at(peerId))
                  .setTo(ChannelData(lcss = Some(msg.reverse)))
              }

              msg
            }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssMostRecent)
          sendMessage(getChannelUpdate)
        }
      }

      // a client is telling us they are online
      case msg: InvokeHostedChannel if state.status == Active => {
        // investigate the situation of any payments that might be pending
        Timer.timeout(FiniteDuration(5, "seconds")) { () =>
          state.lcssNext.incomingHtlcs.foreach { htlc =>
            Main.node
              .inspectOutgoingPayment(
                HtlcIdentifier(shortChannelId, htlc.id),
                htlc.paymentHash
              )
              .foreach { result => gotPaymentResult(htlc.id, result) }
          }
        }
      }

      // client is fulfilling an HTLC we've sent
      case msg: UpdateFulfillHtlc if state.status == Active => {
        // find the htlc
        state.lcss.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            Main.log(s"resolving htlc ${htlc.paymentHash}")

            // call our htlc callback so our upstream node is notified
            // we do this to guarantee our money as soon as possible
            state.provideHtlcResult(
              htlc.id,
              Some(Right(msg.paymentPreimage))
            )

            // keep updated state
            state = state.addUncommittedUpdate(FromRemote(msg))
          }
          case _ => {
            localLogger.warn.msg(
              "client has fulfilled an HTLC we don't know about (or used a wrong preimage)"
            )
          }
        }
      }

      // client is failing an HTLC we've sent
      case msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          if state.status == Active => {
        msg match {
          case f: UpdateFailHtlc if (f.reason.isEmpty) => {
            // fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            Database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(_ :+ err)
            }
          }
          case _ =>
            // keep the updated state
            state = state.addUncommittedUpdate(FromRemote(msg))
        }
      }

      // client is sending an htlc through us
      case htlc: UpdateAddHtlc if state.status == Active => {
        val updated = state.addUncommittedUpdate(FromRemote(htlc))

        // check if fee and cltv delta etc are correct, otherwise return a failure
        Utils
          .parseClientOnion(htlc)
          .map(_.packet) match {
          case Right(packet: PaymentOnion.ChannelRelayPayload) => {
            if (
              // critical failures, fail the channel
              htlc.amountMsat < packet.amountToForward ||
              updated.lcssNext.incomingHtlcs.size > updated.lcssNext.initHostedChannel.maxAcceptedHtlcs ||
              updated.lcssNext.incomingHtlcs
                .map(_.amountMsat.toLong)
                .sum > updated.lcssNext.initHostedChannel.maxHtlcValueInFlightMsat.toLong ||
              updated.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
              updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
            ) {
              val err = Error(
                channelId,
                Error.ERR_HOSTED_MANUAL_SUSPEND
              )
              sendMessage(err)
              Database.update { data =>
                data
                  .modify(_.channels.at(peerId).localErrors)
                  .using(_ :+ err)
              }
            } else if (
              // non-critical failures, just fail the htlc
              htlc.amountMsat < updated.lcssNext.initHostedChannel.htlcMinimumMsat
            ) {
              scala.concurrent.ExecutionContext.global.execute(() =>
                gotPaymentResult(htlc.id, Some(Left(None)))
              )
            }

            state = updated
          }
          case Left(_: Exception) => {
            // this means the htlc onion is too garbled, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_MANUAL_SUSPEND
            )
            sendMessage(err)
            Database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(_ :+ err)
            }
          }
          case Left(fail: FailureMessage) => {
            // we have a proper error, so fail this htlc on client
            scala.concurrent.ExecutionContext.global.execute(() =>
              gotPaymentResult(
                htlc.id,
                Some(Left(Some(NormalFailureMessage(fail))))
              )
            )

            // still we first must acknowledge this received htlc, so we keep the updated state
            state = updated
          }

          // decide later what to do here (could be a payment directed to us etc)
          case _ => {}
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // account for situations in which peer is behind us (ignore?) and for when we're behind (keep track of the forward state?)
      case msg: StateUpdate
          if state.status == Active && !state.uncommittedUpdates.isEmpty => {
        // this will only be triggered if there are uncommitted updates
        // otherwise it will be ignored so the client is free to spam us with
        // valid and up-to-date state_updates and we won't even notice
        Main.log(s"updating our local state after a transition")
        if (
          msg.totalUpdates == state.lcssNext.totalUpdates &&
          msg.blockDay == state.lcssNext.blockDay
        ) {
          Main.log("we and the client are now even")

          // verify signature
          val lcssNext =
            state.lcssNext.copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)
          if (!lcssNext.verifyRemoteSig(peerId)) {
            // a wrong signature, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            Database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(_ :+ err)
            }
          } else {
            // grab state before saving the update
            val lcssPrev = state.lcss

            // update new last_cross_signed_state on the database
            Main.log(s"saving on db: $lcssNext")
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(ChannelData(lcss = Some(lcssNext)))
                //
                // also remove the links for any htlcs that were relayed from elsewhere to this channel
                // (htlcs that were relayed from this channel to elsewhere will be handled on their side)
                .modify(_.htlcForwards)
                .using(fwd =>
                  fwd -- fwd.values // here we grab just the values, i.e. just the "to" part of the mapping
                    .filter({ case HtlcIdentifier(scid, _) =>
                      scid == shortChannelId // then we just leave the ones relayed to this channel
                    })
                    .filter({ case HtlcIdentifier(_, id) =>
                      lcssPrev.incomingHtlcs // then we just leave the ones that were in the previous lcss
                        .filterNot(htlc => // but are not in the next (which was just saved)
                          lcssNext.incomingHtlcs.contains(htlc)
                        )
                        .exists(htlc =>
                          htlc.id == id
                        ) // from these we only leave one if it was on the mapping
                    })
                  // for all effects this will remove from `fwd` all entries that had the value
                  // equal to an HtlcIdentifier that corresponding to this channel that was inflight
                  // before but isn't anymore
                )
                //
                // and remove any preimages we were keeping track of but are now committed
                // we don't care about these preimages anymore since we have the signature of the peer
                // in the updated state, which is much more powerful
                .modify(_.preimages)
                .using(preimages =>
                  preimages -- // removing the following list of payment hashes:
                    ((lcssPrev.incomingHtlcs // all incoming update_add_htlcs that were in the previous lcss
                      .filterNot(htlc => // but are not in the next lcss (which was just saved)
                        lcssNext.incomingHtlcs.contains(htlc)
                      )) ++ (lcssPrev.outgoingHtlcs
                      .filterNot(htlc => // idem for outgoing
                        lcssNext.outgoingHtlcs.contains(htlc)
                      )))
                      .map(_.paymentHash) // grabbing the payment hash
                )
            }

            // act on each pending message, relaying them as necessary
            state.uncommittedUpdates.foreach {
              // i.e. and fail htlcs if any
              case FromRemote(fail: UpdateFailHtlc) =>
                state.provideHtlcResult(
                  fail.id,
                  Some(Left(Some(FailureOnion(fail.reason))))
                )
              case FromRemote(fail: UpdateFailMalformedHtlc) =>
                // for c-lightning there is no way to return this correctly,
                // so just return a temporary_channel_failure for now
                state.provideHtlcResult(
                  fail.id,
                  Some(
                    Left(
                      Some(
                        NormalFailureMessage(
                          TemporaryChannelFailure(getChannelUpdate)
                        )
                      )
                    )
                  )
                )
              case FromRemote(fulfill: UpdateFulfillHtlc) => {
                // we've already relayed this to the upstream node eagerly, so do nothing
              }
              case FromRemote(htlc: UpdateAddHtlc) => {
                // send a payment through the upstream node
                Utils.parseClientOnion(htlc) match {
                  case Left(fail) => {
                    // this should never happen
                    localLogger.warn.msg(
                      "upstream node has relayed a broken update_add_htlc to us"
                    )
                  }
                  case Right(
                        OnionParseResult(
                          payload: PaymentOnion.FinalTlvPayload,
                          _,
                          _
                        )
                      ) => {
                    // we're receiving the payment? this is weird but possible.
                    // figure out how to handle this later, but we will have to patch
                    // c-lightning so it can allow invoices to be manually settled
                    // (and release the preimage in the process.)
                    // this could also be a trampoline, so when we want to support that
                    // we'll have to look again at how eclair is doing it.
                    localLogger.warn
                      .item("payload", payload)
                      .msg("we're receiving a payment from the client?")
                  }
                  case Right(
                        OnionParseResult(
                          payload: PaymentOnion.ChannelRelayPayload,
                          nextOnion: ByteVector,
                          sharedSecret: ByteVector32
                        )
                      ) => {
                    // a payment the client is sending through us to someone else
                    //
                    // first check if it's for another hosted channel we may have
                    Database.data.channels
                      .find((p, _) =>
                        Utils.getShortChannelId(Main.node.ourPubKey, p) ==
                          ShortChannelId(payload.outgoingChannelId)
                      ) match {
                      case Some((targetPeerId, chandata)) => {
                        // it is a local hosted channel
                        // send it to the corresponding channel actor
                        ChannelMaster
                          .getChannel(targetPeerId)
                          .addHTLC(
                            incoming = HtlcIdentifier(shortChannelId, htlc.id),
                            incomingAmount = htlc.amountMsat,
                            outgoingAmount = payload.amountToForward,
                            paymentHash = htlc.paymentHash,
                            cltvExpiry = payload.outgoingCltv,
                            nextOnion = nextOnion
                          )
                          .foreach { status =>
                            gotPaymentResult(htlc.id, status)
                          }
                      }
                      case None =>
                        // it is a normal channel on the upstream node
                        // use sendonion
                        Main.node
                          .sendOnion(
                            chan = this,
                            htlcId = htlc.id,
                            paymentHash = htlc.paymentHash,
                            firstHop =
                              ShortChannelId(payload.outgoingChannelId),
                            amount = payload.amountToForward,
                            cltvExpiryDelta =
                              payload.outgoingCltv - Main.currentBlock,
                            onion = nextOnion
                          )
                    }
                  }
                }
              }
              case FromLocal(_) => {
                // we do not take any action reactively with updates we originated
                // since we have sent them already before sending our state updates
              }
            }

            // send our state update
            sendMessage(lcssNext.stateUpdate)

            // update this channel FSM state to the new lcss
            // plus clean up htlcResult promises that were already fulfilled
            state = state.copy(
              htlcResults =
                state.htlcResults.filterNot((_, p) => p.future.isCompleted),
              uncommittedUpdates = List.empty
            )
          }
        } else {
          // this state update is outdated, do nothing and wait for the next
          localLogger.debug
            .item("local-blockday", state.lcssNext.blockDay)
            .item("remote-blockday", msg.blockDay)
            .item(
              "local-updates",
              s"${state.lcssNext.localUpdates}/${state.lcssNext.remoteUpdates}"
            )
            .item("remote-updates", s"${msg.remoteUpdates}/${msg.localUpdates}")
            .msg("the state they sent is different from our next lcss")
        }
      }

      // client is accepting our override proposal
      case msg: StateUpdate if state.status == Overriding => {
        if (
          msg.remoteUpdates == state.data.proposedOverride.get.localUpdates &&
          msg.localUpdates == state.data.proposedOverride.get.remoteUpdates &&
          msg.blockDay == state.data.proposedOverride.get.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = state.data.proposedOverride.get
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)
          if (lcss.verifyRemoteSig(peerId)) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(ChannelData(lcss = Some(lcss)))
            }

            // send our channel policies again just in case
            sendMessage(getChannelUpdate)

            // channel is active again
            state = ChannelState(peerId = peerId)
          }
        }
      }

      // client is sending an error
      case msg: Error => {
        Database.update { data =>
          data
            .modify(_.channels.at(peerId).remoteErrors)
            .using(_ :+ msg)

            // add a local error here so this channel is marked as "Errored" for future purposes
            .modify(_.channels.at(peerId).localErrors)
            .using(
              _ :+ Error(
                channelId,
                Error.ERR_HOSTED_CLOSED_BY_REMOTE_PEER
              )
            )
        }
      }

      case _ =>
        localLogger.debug.msg("unhandled")
    }
  }

  def onBlockUpdated(block: BlockHeight): Unit = {
    if (state.data.lcss.map(_.outgoingHtlcs.size).getOrElse(0) == 0) {
      // nothing to do here
    } else {
      val expiredOutgoingHtlcs = state.lcss.outgoingHtlcs
        .filter(htlc => htlc.cltvExpiry.toLong < block.toLong)

      if (!expiredOutgoingHtlcs.isEmpty) {
        // if we have any HTLC, we fail the channel
        val err = Error(
          channelId,
          Error.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC
        )
        sendMessage(err)

        // we also fail them on their upstream node
        expiredOutgoingHtlcs
          .map(out =>
            Database.data.htlcForwards
              .find((_, to) => to == out)
              .map((from, _) => from)
          )
          .collect { case Some(htlc) => htlc }
          .foreach { in =>
            state.provideHtlcResult(
              in.id,
              Some(
                Left(
                  Some(
                    NormalFailureMessage(
                      PermanentChannelFailure
                    )
                  )
                )
              )
            )

            // and saved errors on the channel state
            Database.update { data =>
              data.modify(_.channels.at(peerId).localErrors).using(_ :+ err)
            }
          }
      }
    }
  }

  def proposeOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    if (state.status != Errored || state.status != Overriding) {
      Future.failed(
        throw new Exception(
          "can't send to this channel since it is not errored or in overriding state."
        )
      )
    } else if (state.data.lcss.map(_.isHost) != Some(true)) {
      Future.failed(
        throw new Exception(
          "can't send to this channel since we are not the hosts."
        )
      )
    } else {
      val lcssOverride = state.data.proposedOverride
        .getOrElse(
          state.lcss
            .copy(
              incomingHtlcs = Nil,
              outgoingHtlcs = Nil,
              localUpdates = state.lcss.localUpdates + 1,
              remoteUpdates = state.lcss.remoteUpdates + 1,
              remoteSigOfLocal = ByteVector64.Zeroes
            )
        )
        .copy(
          localBalanceMsat = newLocalBalance,
          remoteBalanceMsat =
            state.lcss.initHostedChannel.channelCapacityMsat - newLocalBalance,
          blockDay = Main.currentBlockDay
        )
        .withLocalSigOfRemote(Main.node.getPrivateKey())

      Database.update { data =>
        data
          .modify(_.channels.at(peerId).proposedOverride)
          .setTo(Some(lcssOverride))
      }

      sendMessage(lcssOverride.stateOverride)
        .map((v: ujson.Value) => v("status").str)
    }
  }

  def getChannelUpdate: ChannelUpdate = {
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(Main.node.ourPubKey, peerId),
      isEnabled = true
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              Main.chainHash,
              shortChannelId,
              timestamp,
              flags,
              Main.config.cltvExpiryDelta,
              Main.ourInit.htlcMinimumMsat,
              Main.config.feeBase,
              Main.config.feeProportionalMillionths,
              Some(Main.ourInit.channelCapacityMsat),
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, Main.node.getPrivateKey())
    ChannelUpdate(
      signature = sig,
      chainHash = Main.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = Main.config.cltvExpiryDelta,
      htlcMinimumMsat = Main.ourInit.htlcMinimumMsat,
      feeBaseMsat = Main.config.feeBase,
      feeProportionalMillionths = Main.config.feeProportionalMillionths,
      htlcMaximumMsat = Some(Main.ourInit.channelCapacityMsat)
    )
  }
}
