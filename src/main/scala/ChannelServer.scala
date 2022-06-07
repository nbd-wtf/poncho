import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._

import codecs._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import crypto.Crypto

import Utils.OnionParseResult
import scala.concurrent.duration.FiniteDuration

// -- questions:
// should we fail all pending incoming htlcs on our actual normal node side whenever we fail the channel (i.e. send an Error message -- or receive an error message?)
//   answer: no, as they can still be resolve manually.
//   instead we should fail them whenever the timeout expires on the hosted channel side

class ChannelServer(peerId: ByteVector)(implicit
    ac: castor.Context
) extends Channel[HostedClientMessage, HostedServerMessage](peerId) {
  case class Opening(refundScriptPubKey: ByteVector) extends State
  case class Inactive() extends State
  case class Errored(lcssNext: Option[LastCrossSignedState]) extends State
  case class Overriding(target: LastCrossSignedState) extends State
  case class Active(
      htlcResults: Map[ULong, Promise[PaymentStatus]] = Map.empty,
      uncommittedUpdates: List[FromLocal | FromRemote] = List.empty
  ) extends State {
    // return a copy of this state with the update_add_htlc/update_fail_htlc/update_fulfill_htlc
    // appended to the list of uncommitted updates that will be used to generate lcssNext below
    // and that will be processed and dispatched to our upstream node once they are actually committed
    def addUncommittedUpdate(upd: FromLocal | FromRemote): Active =
      this.copy(uncommittedUpdates = this.uncommittedUpdates :+ upd)
    def removeUncommitedUpdate(upd: FromLocal | FromRemote): Active =
      this.copy(uncommittedUpdates =
        this.uncommittedUpdates.filterNot(_ == upd)
      )

    // this tells our upstream node to resolve or fail the htlc it is holding
    def provideHtlcResult(id: ULong, result: PaymentStatus): Unit =
      this.htlcResults
        .get(id)
        .foreach(_.success(result))

    // calculates what will be our next state once we commit these uncommitted updates
    lazy val lcssNext: LastCrossSignedState = {
      val base = Database.data.channels
        .get(peerId)
        .get
        .lcss
        .copy(
          blockDay = Main.currentBlockDay,
          remoteSigOfLocal = ByteVector64.Zeroes,
          localSigOfRemote = ByteVector64.Zeroes
        )

      uncommittedUpdates
        .foldLeft(base)((lcss, upd) =>
          upd match {
            case FromRemote(add: UpdateAddHtlc) =>
              lcss.copy(
                remoteBalanceMsat = lcss.remoteBalanceMsat - add.amountMsat,
                remoteUpdates = lcss.remoteUpdates + 1,
                incomingHtlcs = lcss.incomingHtlcs :+ add
              )
            case FromRemote(
                  fail: (UpdateFailHtlc | UpdateFailMalformedHtlc)
                ) => {
              val htlcId = fail match {
                case x: UpdateFailHtlc          => x.id;
                case x: UpdateFailMalformedHtlc => x.id
              }

              lcss.outgoingHtlcs.find(_.id == htlcId) match {
                case Some(htlc) => {
                  lcss.copy(
                    localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                    remoteUpdates = lcss.remoteUpdates + 1,
                    outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
                  )
                }
                case None => lcss
              }
            }
            case FromRemote(fulfill: UpdateFulfillHtlc) => {
              lcss.outgoingHtlcs.find(_.id == fulfill.id) match {
                case Some(htlc) => {
                  lcss.copy(
                    remoteBalanceMsat =
                      lcss.remoteBalanceMsat + htlc.amountMsat,
                    remoteUpdates = lcss.remoteUpdates + 1,
                    outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
                  )
                }
                case None => lcss
              }
            }
            case FromLocal(add: UpdateAddHtlc) => {
              lcss.copy(
                localBalanceMsat = lcss.localBalanceMsat - add.amountMsat,
                localUpdates = lcss.localUpdates + 1,
                outgoingHtlcs = lcss.outgoingHtlcs :+ add
              )
            }
            case FromLocal(
                  fail: (UpdateFailHtlc | UpdateFailMalformedHtlc)
                ) => {
              val htlcId = fail match {
                case x: UpdateFailHtlc          => x.id;
                case x: UpdateFailMalformedHtlc => x.id
              }

              lcss.incomingHtlcs.find(_.id == htlcId) match {
                case Some(htlc) => {
                  lcss.copy(
                    remoteBalanceMsat =
                      lcss.remoteBalanceMsat + htlc.amountMsat,
                    localUpdates = lcss.localUpdates + 1,
                    incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                  )
                }
                case None => lcss
              }
            }
            case FromLocal(fulfill: UpdateFulfillHtlc) => {
              lcss.incomingHtlcs.find(_.id == fulfill.id) match {
                case Some(htlc) => {
                  lcss.copy(
                    localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                    localUpdates = lcss.localUpdates + 1,
                    incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                  )
                }
                case None => lcss
              }
            }
          }
        )
        .withLocalSigOfRemote(Main.node.getPrivateKey())
    }

    override def toString: String =
      s"Active(${lcssNext}, $htlcResults, $uncommittedUpdates)"
  }

  var state: State =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive        => Active()
      case Some(chandata) if chandata.error.isDefined => Errored(None)
      case _                                          => Inactive()
    }

  def run(msg: HostedClientMessage): Unit = {
    Main.log(s"[$this] at $state <-- $msg")
    state = (state, msg) match {
      // someone wants a new hosted channel from us
      case (Inactive(), msg: InvokeHostedChannel) => {
        // check chain hash
        if (msg.chainHash != Main.chainHash) {
          Main.log(
            s"[${peerId}] sent InvokeHostedChannel for wrong chain: ${msg.chainHash} (current: ${Main.chainHash})"
          )
          sendMessage(
            Error(
              channelId,
              s"invalid chainHash (local=${Main.chainHash} remote=${msg.chainHash})"
            )
          )
          stay
        } else {
          // chain hash is ok, proceed
          Database.data.channels.get(peerId) match {
            case Some(chandata) => {
              // channel already exists, so send last cross-signed-state
              sendMessage(chandata.lcss)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
            case None => {
              // reply saying we accept the invoke
              sendMessage(Main.ourInit)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
          }
        }
      }

      // final step of channel open process
      case (Opening(refundScriptPubKey), msg: StateUpdate) => {
        // build last cross-signed state
        val lcss = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = refundScriptPubKey,
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

        // check if everything is ok
        if ((msg.blockDay - Main.currentBlockDay).abs > 1) {
          Main.log(
            s"[${peerId}] sent state_update with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
          )
          sendMessage(
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
          Inactive()
        } else if (!lcss.verifyRemoteSig(peerId)) {
          Main.log(s"[${peerId}] sent state_update with wrong signature.")
          sendMessage(
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          )
          Inactive()
        } else {
          // all good, save this channel to the database and consider it opened
          Database.update { data =>
            {
              data
                .modify(_.channels)
                .using(
                  _ +
                    (
                      peerId -> ChannelData(
                        isActive = true,
                        error = None,
                        lcss = lcss,
                        proposedOverride = None
                      )
                    )
                )
            }
          }

          // send our signed state update
          sendMessage(lcss.stateUpdate)

          // send a channel update
          sendMessage(getChannelUpdate)

          Active()
        }
      }

      // when the client tries to invoke it we return the error
      case (_: Errored, _: InvokeHostedChannel) => {
        val chandata = Database.data.channels.get(peerId).get
        sendMessage(chandata.error.get)
        stay
      }

      // a client was just turned on and is sending this to sync states
      case (active: Active, msg: LastCrossSignedState) => {
        val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(peerId)

        if (!isLocalSigOk || !isRemoteSigOk) {
          val err = if (!isLocalSigOk) {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with a signature that isn't ours"
            )
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with an invalid signature"
            )
            Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          sendMessage(err)
          Database.update { data =>
            data
              .modify(_.channels.at(peerId).isActive)
              .setTo(false)
              .modify(_.channels.at(peerId).error)
              .setTo(Some(err))
          }
          Errored(Some(active.lcssNext))
        } else {
          // channel is active, which means we must have a database entry necessarily
          val chandata = Database.data.channels.get(peerId).get

          val lcssMostRecent =
            if (
              (chandata.lcss.localUpdates + chandata.lcss.remoteUpdates) >=
                (msg.remoteUpdates + msg.localUpdates)
            ) {
              // we are even or ahead
              chandata.lcss
            } else {
              // we are behind
              Main.log(
                s"[${peerId}] sent LastCrossSignedState showing that we are behind: " +
                  s"local=${chandata.lcss.localUpdates}/${chandata.lcss.remoteUpdates} " +
                  s"remote=${msg.remoteUpdates}/${msg.localUpdates}"
              )

              // save their lcss here
              Database.update { data =>
                {
                  data
                    .modify(_.channels.at(peerId).lcss)
                    .setTo(msg.reverse)
                }
              }

              msg
            }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssMostRecent)
          sendMessage(getChannelUpdate)
          stay
        }
      }

      // a client is telling us they are online
      case (active: Active, msg: InvokeHostedChannel) => {
        // channel already exists, so we just send last cross-signed-state
        val chandata = Database.data.channels.get(peerId).get
        sendMessage(chandata.lcss)

        // investigate the situation of any payments that might be pending
        Timer.timeout(FiniteDuration(5, "seconds")) { () =>
          active.lcssNext.incomingHtlcs.foreach { htlc =>
            Main.node
              .inspectOutgoingPayment(peerId, htlc.id, htlc.paymentHash)
              .foreach { result => upstreamPaymentResult(htlc.id, result) }
          }
        }

        stay
      }

      // client is fulfilling an HTLC we've sent
      case (
            active: Active,
            msg: UpdateFulfillHtlc
          ) => {
        // find the htlc
        active.lcssNext.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            Main.log(s"resolving htlc ${htlc.paymentHash}")

            // call our htlc callback so our node is notified
            // we do this to guarantee our money as soon as possible
            active.provideHtlcResult(
              htlc.id,
              Some(Right(msg.paymentPreimage))
            )

            // keep updated state
            active.addUncommittedUpdate(FromRemote(msg))
          }
          case _ => {
            Main.log(
              s"client has fulfilled an HTLC we don't know about (or used a wrong preimage): ${msg}"
            )
            stay
          }
        }
      }

      // client is failing an HTLC we've sent
      case (
            active: Active,
            msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          ) => {
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
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
                .modify(_.channels.at(peerId).error)
                .setTo(Some(err))
            }
            Errored(Some(active.lcssNext))
          }
          case _ =>
            // keep the updated state
            active.addUncommittedUpdate(FromRemote(msg))
        }
      }

      // client is sending an htlc through us
      case (
            active: Active,
            add: UpdateAddHtlc
          ) => {
        val updated = active.addUncommittedUpdate(FromRemote(add))

        // check if fee and cltv delta etc are correct, otherwise return a failure
        Utils
          .parseClientOnion(add)
          .map(_.packet) match {
          case Right(packet: PaymentOnion.ChannelRelayPayload) => {
            // TODO check for fees, cltv expiry, sizes, counts etc
            updated
          }
          case Left(_: Exception) => {
            // this means the htlc onion is too garbled, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_MANUAL_SUSPEND
            )
            Database.update { data =>
              data
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
                .modify(_.channels.at(peerId).error)
                .setTo(Some(err))
            }
            Errored(Some(active.lcssNext))
          }
          case Left(fail: FailureMessage) => {
            // we have a proper error, so fail this htlc on client
            Timer.timeout(FiniteDuration(1, "seconds")) { () =>
              upstreamPaymentResult(
                add.id,
                Some(Left(Some(NormalFailureMessage(fail))))
              )
            }

            // still we first must acknowledge this received htlc, so we keep the updated state
            updated
          }

          // decide later what to do here (could be a payment directed to us etc)
          case _ => stay
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // account for situations in which peer is behind us (ignore?) and for when we're behind (keep track of the forward state?)
      case (active: Active, msg: StateUpdate)
          if !active.uncommittedUpdates.isEmpty => {
        // this will only be triggered if there are uncommitted updates
        // otherwise it will be ignored so the client is free to spam us with
        // valid and up-to-date state_updates and we won't even notice
        Main.log(s"updating our local state after a transition")
        if (
          msg.totalUpdates == active.lcssNext.totalUpdates &&
          msg.blockDay == active.lcssNext.blockDay
        ) {
          Main.log("we and the client are now even")

          // verify signature
          val lcssNext = active.lcssNext.copy(
            remoteSigOfLocal = msg.localSigOfRemoteLCSS
          )
          if (lcssNext.verifyRemoteSig(peerId)) {
            // update state on the database
            Main.log(s"saving on db: $lcssNext")
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcssNext, proposedOverride = None, error = None)
                )
            }

            // relay pending messages to node
            active.uncommittedUpdates.foreach {
              // i.e. and fail htlcs if any
              case FromRemote(fail: UpdateFailHtlc) =>
                active.provideHtlcResult(
                  fail.id,
                  Some(Left(Some(FailureOnion(fail.reason))))
                )
              case FromRemote(fail: UpdateFailMalformedHtlc) =>
                // for c-lightning there is no way to return this correctly,
                // so just return a temporary_channel_failure for now
                active.provideHtlcResult(
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
              case FromRemote(add: UpdateAddHtlc) => {
                // send a payment through the upstream node
                Utils.parseClientOnion(add) match {
                  case Left(fail) => {
                    // this should never happen
                    Main.log(s"upstream node has relayed a broken add to us")
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
                    Main.log(
                      s"we're receiving a payment from the client? $payload"
                    )
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
                      .find((peerId, chandata) =>
                        shortChannelId ==
                          ShortChannelId(payload.outgoingChannelId)
                      ) match {
                      case Some((peerId, chandata)) => {
                        // it is a local hosted channel
                        // send it to the corresponding channel actor
                        val chan = (if chandata.lcss.isHost then
                                      ChannelMaster.getChannelServer
                                    else ChannelMaster.getChannelClient)(peerId)
                        chan
                          .addHTLC(
                            add.amountMsat,
                            UpdateAddHtlc(
                              channelId = chan.channelId,
                              id = 0L.toULong, // will be replaced
                              amountMsat = payload.amountToForward,
                              paymentHash = add.paymentHash,
                              cltvExpiry = payload.outgoingCltv,
                              onionRoutingPacket = nextOnion
                            )
                          )
                          .foreach { status =>
                            upstreamPaymentResult(add.id, status)
                          }
                      }
                      case None =>
                        // it is a normal channel on the upstream node
                        // use sendonion
                        Main.node
                          .getPeerFromChannel(
                            ShortChannelId(payload.outgoingChannelId)
                          )
                          .onComplete {
                            case Success(Some(nodeid)) =>
                              Main.node
                                .sendOnion(
                                  hostedPeerId = peerId,
                                  htlcId = add.id,
                                  paymentHash = add.paymentHash,
                                  firstHop = nodeid,
                                  amount = payload.amountToForward,
                                  cltvExpiryDelta =
                                    payload.outgoingCltv - Main.currentBlock,
                                  onion = nextOnion
                                )
                                .onComplete {
                                  case Failure(e) => {
                                    Main.log(s"sendonion failure: $e")
                                    upstreamPaymentResult(
                                      add.id,
                                      Some(Left(None))
                                    )
                                  }
                                  case Success(_) => {}
                                }
                            case Failure(err) => {
                              Main.log(s"failed to get peer for channel: $err")
                              upstreamPaymentResult(
                                add.id,
                                Some(
                                  Left(
                                    Some(NormalFailureMessage(UnknownNextPeer))
                                  )
                                )
                              )
                            }
                            case Success(None) => {
                              Main.log("didn't find peer for channel")
                              upstreamPaymentResult(
                                add.id,
                                Some(
                                  Left(
                                    Some(NormalFailureMessage(UnknownNextPeer))
                                  )
                                )
                              )
                            }
                          }
                    }
                  }
                }
              }
              case FromRemote(_: UpdateFulfillHtlc) => {
                // we've already relayed this to the upstream node eagerly, so do nothing
              }
              case FromLocal(_) => {
                // we do not take any action reactively with updates we originated
                // since we have sent them already before sending our state updates
              }
            }

            // send our state update
            sendMessage(lcssNext.stateUpdate)

            // update this channel state to the new lcss
            // plus clean up htlcResult promises that were already fulfilled
            active.copy(
              htlcResults =
                active.htlcResults.filterNot((_, p) => p.future.isCompleted),
              uncommittedUpdates = List.empty
            )
          } else {
            // a wrong signature, fail the channel
            val err = Error(
              channelId,
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            Database.update { data =>
              data
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
                .modify(_.channels.at(peerId).error)
                .setTo(Some(err))
            }
            Errored(Some(active.lcssNext))
          }
        } else {
          // this state update is outdated, do nothing and wait for the next
          Main.log(
            s"the state they've sent (${msg.blockDay}) is different from our last (${active.lcssNext.blockDay}):"
          )
          Main.log(s"theirs: ${msg.remoteUpdates}/${msg.localUpdates}")
          Main.log(
            s"ours: ${active.lcssNext.localUpdates}/${active.lcssNext.remoteUpdates}"
          )
          stay
        }
      }

      // client is (hopefully) accepting our override proposal
      case (Overriding(lcssOverrideProposal), msg: StateUpdate) => {
        if (
          msg.remoteUpdates == lcssOverrideProposal.localUpdates &&
          msg.localUpdates == lcssOverrideProposal.remoteUpdates &&
          msg.blockDay == lcssOverrideProposal.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = lcssOverrideProposal.copy(remoteSigOfLocal =
            msg.localSigOfRemoteLCSS
          )
          if (lcss.verifyRemoteSig(peerId)) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }

            // send our channel policies again just in case
            sendMessage(getChannelUpdate)

            // channel is active again
            Active()
          } else stay
        } else stay
      }

      // client is sending an error
      case (active: Active, msg: Error) => {
        Database.update { data =>
          data.modify(_.channels.at(peerId).error).setTo(Some(msg))
        }
        Errored(lcssNext = Some(active.lcssNext))
      }

      case _ => stay
    }
  }

  // a update_add_htlc we've received from the upstream node
  // (for c-lightning this comes from the "htlc_accepted" hook)
  def addHTLC(
      incoming: MilliSatoshi,
      prototype: UpdateAddHtlc
  ): Future[PaymentStatus] = {
    var promise = Promise[PaymentStatus]()

    Main.log(s"forwarding payment $state <-- $prototype")
    state match {
      case active: Active
          if (active.lcssNext.incomingHtlcs
            .exists(_.paymentHash == prototype.paymentHash)) => {
        // reject htlc as outgoing if it's already incoming, sanity check
        Main.log(
          s"${prototype.paymentHash} is already incoming, can't add it as outgoing"
        )
        promise.success(None)
      }
      case active: Active
          if (active.lcssNext.outgoingHtlcs
            .exists(_.paymentHash == prototype.paymentHash)) => {
        // do not add htlc to state if it's already there (otherwise the state will be invalid)
        // this is likely to be hit on restarts as the node will replay pending htlcs on us
        Main.log("won't forward the htlc as it's already there")

        // but we still want to update the callbacks we're keeping track of (because we've restarted!)
        val add = active.lcssNext.outgoingHtlcs
          .find(htlc =>
            // TODO update this to check for the id once CLN allows
            htlc.paymentHash == prototype.paymentHash && htlc.amountMsat == prototype.amountMsat
          )
          .get

        state =
          active.copy(htlcResults = active.htlcResults + (add.id -> promise))
      }
      case active: Active => {
        // the default case in which we add a new htlc
        // create update_add_htlc based on the prototype we've received
        val add = prototype
          .copy(id = active.lcssNext.localUpdates.toULong + 1L.toULong)

        // prepare modification to new lcss to be our next
        val upd = FromLocal(add)
        val updated = active.addUncommittedUpdate(upd)

        // check a bunch of things, if any fail return a temporary_channel_failure
        val requiredFee = MilliSatoshi(
          Main.config.feeBase.toLong + (Main.config.feeProportionalMillionths * add.amountMsat.toLong / 1000000L)
        )
        if (
          add.amountMsat < updated.lcssNext.initHostedChannel.htlcMinimumMsat ||
          (add.cltvExpiry.blockHeight - Main.currentBlock).toInt < Main.config.cltvExpiryDelta.toInt ||
          (incoming - add.amountMsat) >= requiredFee ||
          updated.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
          updated.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
        ) {
          Main.log(
            s"failing ${add.amountMsat < updated.lcssNext.initHostedChannel.htlcMinimumMsat} ${(add.cltvExpiry.blockHeight - Main.currentBlock).toInt >= Main.config.cltvExpiryDelta.toInt} ${(incoming - add.amountMsat) >= requiredFee} ${updated.lcssNext.localBalanceMsat < MilliSatoshi(
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
          // but first we update the state to include this uncommitted htlc
          // and add to the callbacks we're keeping track of for the upstream node
          state =
            updated.copy(htlcResults = active.htlcResults + (add.id -> promise))

          sendMessage(add)
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
                state match {
                  case active: Active =>
                    state = active.removeUncommitedUpdate(upd)
                  case _ => {}
                }
              }
            }
        }
      }
      case _ => {
        Main.log("can't add an HTLC in a channel that isn't Active()")
        promise.success(None)
      }
    }

    promise.future
      .andThen { case Success(status) =>
        status match {
          case Some(Right(preimage)) =>
            Main.log(
              s"[add-htlc] $shortChannelId routed ${prototype.paymentHash} successfully: $preimage"
            )
          case Some(Left(Some(FailureOnion(_)))) =>
            s"[add-htlc] $shortChannelId received failure onion for ${prototype.paymentHash}"
          case Some(Left(_)) =>
            Main.log(
              s"[add-htlc] $shortChannelId failed ${prototype.paymentHash}"
            )
          case None =>
            Main.log(
              s"[add-htlc] $shortChannelId didn't handle ${prototype.paymentHash}"
            )
        }
      }
  }

  def upstreamPaymentResult(
      htlcId: ULong,
      status: PaymentStatus
  ): Unit =
    if (status.isEmpty) {
      // payment still pending
    } else
      scala.concurrent.ExecutionContext.global.execute(() => {
        (status.get, state) match {
          case (Right(preimage), active: Active) => {
            val fulfill = UpdateFulfillHtlc(
              channelId,
              htlcId,
              preimage
            )

            val upd = FromLocal(fulfill)
            val updated = active.addUncommittedUpdate(upd)
            state = updated

            sendMessage(fulfill)
              .onComplete {
                case Success(_) => {
                  sendMessage(updated.lcssNext.stateUpdate)
                }
                case Failure(err) => {
                  // client is offline and can't take our update_fulfill_htlc,
                  // so we remove it from the list of uncommitted updates
                  // and wait for when the peer becomes online again
                  Main.log(
                    s"failed to send update_fulfill_htlc to $peerId: $err"
                  )
                  state match {
                    case active: Active =>
                      state = active.removeUncommitedUpdate(upd)
                    case _ => {}
                  }
                }
              }
          }
          case (Left(failure), active: Active) => {
            (for {
              htlc <- active.lcssNext.incomingHtlcs.find(_.id == htlcId)
              OnionParseResult(packet, _, sharedSecret) <- Utils
                .parseClientOnion(htlc)
                .toOption
              fail = failure match {
                case bo: BadOnion =>
                  UpdateFailMalformedHtlc(
                    htlc.channelId,
                    htlc.id,
                    bo.onionHash,
                    bo.code
                  )
                case _ =>
                  UpdateFailHtlc(
                    channelId,
                    htlcId,
                    failure.getOrElse(
                      TemporaryChannelFailure(getChannelUpdate)
                    ) match {
                      case fm: FailureMessage =>
                        Sphinx.FailurePacket.create(sharedSecret, fm)
                      case fo: ByteVector =>
                        Sphinx.FailurePacket.wrap(fo, sharedSecret)
                    }
                  )
              }
            } yield fail)
              .foreach { fail =>
                // prepare updated state
                val upd = FromLocal(fail)
                val updated =
                  active.addUncommittedUpdate(upd)
                state = updated

                sendMessage(fail)
                  .onComplete {
                    case Success(_) => {
                      sendMessage(updated.lcssNext.stateUpdate)
                    }
                    case Failure(err) => {
                      // client is offline and can't take our update_fulfill_htlc,
                      // so we remove it from the list of uncommitted updates
                      // and wait for when the peer becomes online again
                      Main.log(
                        s"failed to send update_fail_htlc to $peerId: $err"
                      )
                      state match {
                        case active: Active =>
                          state = active.removeUncommitedUpdate(upd)
                        case _ => {}
                      }
                    }
                  }
              }
          }
          case (Right(preimage), state) => {
            Main.log(
              s"a payment has succeeded with $preimage but the channel with $peerId is not Active, instead it is $state"
            )
            // TODO here is probably when we should publish this preimage somewhere
            // fire the alarms etc
          }
          case (Left(failure), state) => {
            Main.log(
              s"a payment has failed with $failure but the channel with $peerId is not Active, instead it is $state"
            )
          }
        }
      })

  def proposeOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    state match {
      case Errored(maybeLcssNext) => {
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

        val lcssOverride = lcssNextBase
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              lcssNextBase.initHostedChannel.channelCapacityMsat - newLocalBalance,
            incomingHtlcs = Nil,
            outgoingHtlcs = Nil,
            localUpdates = lcssNextBase.localUpdates + 1,
            remoteUpdates = lcssNextBase.remoteUpdates + 1,
            blockDay = Main.currentBlockDay,
            remoteSigOfLocal = ByteVector64.Zeroes
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        state = Overriding(lcssOverride)
        sendMessage(lcssOverride.stateOverride)
          .map((v: ujson.Value) => v("status").str)
      }
      case Overriding(target) => {
        val lcssOverride = target
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              target.initHostedChannel.channelCapacityMsat - newLocalBalance,
            blockDay = Main.currentBlockDay
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        state = Overriding(lcssOverride)
        sendMessage(lcssOverride.stateOverride)
          .map((v: ujson.Value) => v("status").str)
      }
      case _ => {
        Future {
          s"can't send to this channel since it is not errored or in overriding state."
        }
      }
    }
  }
}
