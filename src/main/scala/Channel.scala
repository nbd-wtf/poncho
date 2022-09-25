import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.collection.mutable.Map
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import com.softwaremill.quicklens._
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._
import scoin._
import scoin.Crypto.PublicKey
import scoin.ln._
import scoin.ln.LightningMessageCodecs._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._

import Utils.OnionParseResult

type PaymentStatus = Option[Either[Option[PaymentFailure], ByteVector32]]

sealed trait PaymentFailure
case class FailureOnion(onion: ByteVector) extends PaymentFailure
case class NormalFailureMessage(message: FailureMessage) extends PaymentFailure

case class FromLocal(
    upd: UpdateMessage,

    // this exists to match the htlc incoming and outgoing at the .htlcForwards table
    relatedIncoming: Option[HtlcIdentifier]
)
case class FromRemote(upd: UpdateMessage)

trait ChannelStatus
case object Opening extends ChannelStatus
case object Invoking extends ChannelStatus
case object Active extends ChannelStatus
case object Overriding extends ChannelStatus
case object NotOpened extends ChannelStatus
case object Errored extends ChannelStatus
case object Suspended extends ChannelStatus

class Channel(peerId: ByteVector) {
  lazy val channelId =
    HostedChannelHelpers.getChannelId(
      ChannelMaster.node.publicKey.value,
      peerId
    )
  lazy val shortChannelId =
    HostedChannelHelpers.getShortChannelId(
      ChannelMaster.node.publicKey.value,
      peerId
    )

  val htlcResults = Map.empty[Long, Promise[PaymentStatus]]
  var openingRefundScriptPubKey: Option[ByteVector] = None
  var invoking: Option[ByteVector | LastCrossSignedState] = None
  var state = StateManager(peerId, lcssStored)

  def currentData =
    ChannelMaster.database.data.channels.get(peerId).getOrElse(ChannelData())
  def lcssStored = currentData.lcss
  def status =
    if openingRefundScriptPubKey.isDefined then Opening
    else if invoking.isDefined then Invoking
    else if currentData.lcss.isEmpty then NotOpened
    else if currentData.proposedOverride.isDefined then Overriding
    else if !currentData.localErrors.isEmpty then Errored
    else if currentData.suspended then Suspended
    else Active

  val logger =
    ChannelMaster.logger.attach.item("peer", peerId.toHex.take(7)).logger()

  def sendMessage(msg: LightningMessage): Future[ujson.Value] =
    ChannelMaster.node.sendCustomMessage(peerId, msg)

  // this function only sends one state_update once for each state
  var stateUpdateSendsTracker = List.empty[StateManager]
  def sendStateUpdate(st: StateManager): Unit = {
    if (!stateUpdateSendsTracker.contains(st)) {
      sendMessage(
        st.lcssNext
          .withCurrentBlockDay(ChannelMaster.currentBlockDay)
          .withLocalSigOfRemote(ChannelMaster.node.privateKey)
          .stateUpdate
      )
      stateUpdateSendsTracker = (st +: stateUpdateSendsTracker).take(3)
    }
  }

  // this tells our upstream to resolve or fail the htlc it is holding
  // (the upstream might be either an actual node (CLN, LND) or another hosted channel)
  def provideHtlcResult(id: Long, result: PaymentStatus): Unit =
    htlcResults
      .get(id)
      .foreach(_.success(result))

  // a update_add_htlc we've received from the upstream node
  // (for c-lightning this comes from the "htlc_accepted" hook)
  def addHtlc(
      htlcIn: HtlcIdentifier,
      paymentHash: ByteVector32,
      amountIn: MilliSatoshi,
      amountOut: MilliSatoshi,
      cltvIn: CltvExpiry,
      cltvOut: CltvExpiry,
      nextOnion: ByteVector
  ): Future[PaymentStatus] = {
    val localLogger =
      logger.attach.item(status).item("hash", paymentHash).logger()

    localLogger.debug
      .item("htlc-in", htlcIn)
      .item("amount-in", amountIn)
      .item("amount-out", amountOut)
      .item("cltv-in", cltvIn.toLong)
      .item("cltv-out", cltvOut.toLong)
      .msg("adding HTLC")

    var promise = Promise[PaymentStatus]()

    val preimage = ChannelMaster.database.data.preimages.get(paymentHash)
    val onionRoutingPacket = PaymentOnionCodecs.paymentOnionPacketCodec
      .decode(nextOnion.toBitVector)
      .toOption
    val alreadyIncoming =
      state.lcssNext.incomingHtlcs.exists(_.paymentHash == paymentHash)
    val isInflight = ChannelMaster.database.data.htlcForwards.get(htlcIn) ==
      Some(HtlcIdentifier(shortChannelId, _))
    val isActive = (status == Active)

    (
      isActive,
      isInflight,
      alreadyIncoming,
      preimage,
      onionRoutingPacket
    ) match {
      case (_, _, _, Some(preimage), _) => {
        localLogger.warn
          .item("preimage", preimage.toHex)
          .msg("HTLC was already resolved, and we have the preimage right here")
        promise.success(Some(Right(preimage)))
      }
      case (_, _, alreadyIncoming @ true, _, _) => {
        // reject htlc as outgoing if it's already incoming, sanity check
        localLogger.err.msg("htlc is incoming, can't add it as outgoing")
        promise.success(
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  IncorrectOrUnknownPaymentDetails(
                    amountIn,
                    ChannelMaster.currentBlock
                  )
                )
              )
            )
          )
        )
      }
      case (_, isInflight @ true, _, _, _) => {
        // do not add htlc to state if it's already there (otherwise the state will be invalid)
        // this is likely to be hit on reboots as the upstream node will replay pending htlcs on us
        localLogger.debug.msg("won't forward the htlc as it's already there")

        // but we still want to update the callbacks we're keeping track of (because we've rebooted!)
        for {
          outgoing <- ChannelMaster.database.data.htlcForwards.get(htlcIn)
          entry <- ChannelMaster.database.data.channels.find((p, _) =>
            HostedChannelHelpers
              .getShortChannelId(
                ChannelMaster.node.publicKey.value,
                p
              ) == outgoing.scid
          )
          chandata = entry._2
          htlc <- lcssStored.outgoingHtlcs.find(htlc => htlc.id == outgoing.id)
        } yield {
          htlcResults += (htlc.id -> promise)
        }
      }
      case (isActive @ false, _, _, _, _) => {
        localLogger.debug
          .item("status", status)
          .msg("can't forward an HTLC to channel that isn't active")
        promise.success(
          Some(
            Left(
              Some(NormalFailureMessage(UnknownNextPeer))
            )
          )
        )
      }
      case (_, _, _, _, Some(onionRoutingPacket)) => {
        // the default case in which we add a new htlc
        //
        // check a bunch of things, if any fail return a temporary_channel_failure
        val impliedCltvDelta = (cltvIn.blockHeight - cltvOut.blockHeight).toInt
        val inflightHtlcs =
          state.lcssNext.incomingHtlcs.size + state.lcssNext.outgoingHtlcs.size
        val inflightValue =
          (state.lcssNext.incomingHtlcs.map(_.amountMsat.toLong) ++
            state.lcssNext.outgoingHtlcs.map(_.amountMsat.toLong))
            .fold(0L)(_ + _)
        val requiredFee =
          MilliSatoshi(
            ChannelMaster.config.feeBase.toLong + (amountOut.toLong * ChannelMaster.config.feeProportionalMillionths / 1000000)
          )

        val failure: Option[FailureMessage] = () match {
          case _ if amountOut < lcssStored.initHostedChannel.htlcMinimumMsat =>
            Some(AmountBelowMinimum(amountOut, getChannelUpdate(true)))
          case _ if cltvOut.blockHeight < ChannelMaster.currentBlock + 2 =>
            Some(ExpiryTooSoon(getChannelUpdate(true)))
          case _ if cltvOut - cltvIn < ChannelMaster.config.cltvExpiryDelta =>
            Some(IncorrectCltvExpiry(cltvOut, getChannelUpdate(true)))
          case _ if amountIn - amountOut < requiredFee =>
            Some(FeeInsufficient(amountIn, getChannelUpdate(true)))
          case _ if state.lcssNext.localBalanceMsat < amountOut =>
            Some(TemporaryChannelFailure(getChannelUpdate(true)))
          case _
              if inflightHtlcs + 1 > lcssStored.initHostedChannel.maxAcceptedHtlcs ||
                inflightValue + amountOut.toLong > lcssStored.initHostedChannel.maxHtlcValueInFlightMsat.toLong =>
            Some(TemporaryChannelFailure(getChannelUpdate(true)))
          case _ => None
        }

        failure match {
          case Some(f) =>
            promise.success(
              Some(Left(Some(NormalFailureMessage(f))))
            )
          case None => {
            // htlc proposal is good, we will proceed to send update_add_htlc to hosted peer
            // create update_add_htlc based on the data we've received
            val htlc = UpdateAddHtlc(
              channelId = channelId,
              id = state.lcssNext.localUpdates + 1L,
              paymentHash = paymentHash,
              amountMsat = amountOut,
              cltvExpiry = cltvOut,
              onionRoutingPacket = onionRoutingPacket.value
            )

            // prepare modification to new lcss to be our next
            val upd = FromLocal(htlc, Some(htlcIn))

            // update the state to include this uncommitted htlc
            state = state.addUncommittedUpdate(upd)

            // and add to the callbacks we're keeping track of for the upstream node
            htlcResults += (htlc.id -> promise)

            sendMessage(htlc)
              .onComplete {
                case Success(_) =>
                  // success here means the client did get our update_add_htlc,
                  // so send our signed state_update
                  sendStateUpdate(state)
                case Failure(err) => {
                  // client is offline and can't take our update_add_htlc,
                  // so we fail it on upstream
                  // and remove it from the list of uncommitted updates
                  localLogger.warn
                    .item(err)
                    .msg("failed to send update_add_htlc")
                  promise.success(
                    Some(
                      Left(
                        Some(
                          NormalFailureMessage(
                            TemporaryChannelFailure(getChannelUpdate(false))
                          )
                        )
                      )
                    )
                  )
                  state = state.removeUncommitedUpdate(upd)
                }
              }
          }
        }
      }
      case otherwise => {
        localLogger.debug
          .item("context", otherwise)
          .msg(
            "failed to forward, probably because the htlc has a garbled onion."
          )
        promise.success(
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  InvalidOnionPayload(UInt64(0), 0)
                )
              )
            )
          )
        )
      }
    }

    promise.future
      // just some debug messages
      .andThen { case Success(status) =>
        status match {
          case Some(Right(preimage)) =>
            localLogger.info
              .item("preimage", preimage)
              .msg("routed successfully")
          case Some(Left(Some(FailureOnion(_)))) =>
            localLogger.info.msg("received failure onion")
          case Some(Left(failure)) =>
            localLogger.debug
              .item("failure", failure)
              .msg("received generic failure")
          case None =>
            localLogger.warn.msg("didn't handle")
        }
      }
  }

  // this tells to our hosted peer we have a failure or success (or if it's still pending -- None -- it does nothing)
  def gotPaymentResult(htlcId: Long, res: PaymentStatus): Unit = {
    val localLogger = logger.attach
      .item(status)
      .item("htlc", htlcId)
      .item("result", res)
      .logger()

    localLogger.debug.item(summary).msg("got payment result")

    res match {
      case None => // payment still pending
      case Some(_)
          if (status != Active && status != Errored && status != Suspended) =>
        // these are the 3 states in which we will still accept results, otherwise do nothing
        // (the other states are effectively states in which no payment could have ever been relayed)
        localLogger.err.msg(
          "not in an acceptable status to accept payment result"
        )
      case Some(Right(preimage)) => {
        // since this comes from the upstream node it is assumed the preimage is valid
        val fulfill = UpdateFulfillHtlc(
          channelId,
          htlcId,
          preimage
        )

        // migrate our state to one containing this uncommitted update
        val upd = FromLocal(fulfill, None)
        state = state.addUncommittedUpdate(upd)

        // save the preimage so if we go offline we can keep trying to send it or resolve manually
        ChannelMaster.database.update { data =>
          data
            .modify(_.preimages)
            .using(_ + (Crypto.sha256(preimage) -> preimage))
        }

        // we will send this immediately to the client and hope he will acknowledge it
        sendMessage(fulfill)
          .onComplete {
            case Success(_) => {
              if (status == Active) sendStateUpdate(state)
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
      case Some(Left(failure)) => {
        for {
          htlc <- state.lcssNext.incomingHtlcs.find(_.id == htlcId)
          OnionParseResult(packet, _, sharedSecret) <- Utils
            .parseClientOnion(ChannelMaster.node.privateKey, htlc)
            .toOption
        } yield {
          val fail = failure match {
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
                  TemporaryChannelFailure(getChannelUpdate(true))
                )
              ) match {
                case NormalFailureMessage(fm) =>
                  Sphinx.FailurePacket.create(sharedSecret, fm)
                case FailureOnion(fo) =>
                  // must unwrap here because neither upstream node (CLN) or another hosted channel
                  // won't unwrap whatever packet they got from the next hop
                  Sphinx.FailurePacket.wrap(fo, sharedSecret)
              }

              UpdateFailHtlc(channelId, htlcId, reason)
            }
          }

          // prepare updated state
          val upd = FromLocal(fail, None)
          state = state.addUncommittedUpdate(upd)

          sendMessage(fail)
            .onComplete {
              case Success(_) => {
                if (status == Active) sendStateUpdate(state)
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

  def gotPeerMessage(message: LightningMessage): Unit = {
    val localLogger = logger.attach.item(status).logger()

    localLogger.debug
      .item("state", summary)
      .item("message", message)
      .msg("  <:: got peer message")

    message match {
      // we send branding to anyone really
      case msg: AskBrandingInfo =>
        ChannelMaster.config.branding(localLogger).foreach(sendMessage(_))

      // someone wants a new hosted channel from us
      case msg: InvokeHostedChannel
          if status == NotOpened || status == Suspended => {
        // check chain hash
        if (msg.chainHash != ChannelMaster.chainHash) {
          localLogger.warn
            .item("local", ChannelMaster.chainHash)
            .item("remote", msg.chainHash)
            .msg(s"peer sent InvokeHostedChannel for wrong chain")
          sendMessage(
            Error(
              channelId,
              s"invalid chainHash (ours=${ChannelMaster.chainHash} yours=${msg.chainHash})"
            )
          )
        } else {
          // chain hash is ok, proceed
          if (status == NotOpened) {
            if (
              !ChannelMaster.config.requireSecret ||
              ChannelMaster.config.permanentSecrets.contains(
                msg.secret.toHex
              ) ||
              ChannelMaster.temporarySecrets.contains(msg.secret.toHex)
            ) {
              // save this for the next step (having this also moves us to the Invoking state)
              openingRefundScriptPubKey = Some(msg.refundScriptPubKey)

              // reply saying we accept the invoke and go into Opening state
              sendMessage(ChannelMaster.config.init)

              // remove the temporary secret used, if any
              ChannelMaster.temporarySecrets =
                ChannelMaster.temporarySecrets.filterNot(_ == msg.secret.toHex)
            }
          } else {
            // channel already exists, so send last cross-signed-state
            sendMessage(currentData.lcss)
          }
        }
      }

      // final step of channel open process from the server side
      case msg: StateUpdate if status == Opening => {
        // build last cross-signed state for the beginning of channel
        val lcssInitial = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = openingRefundScriptPubKey.get,
          initHostedChannel = ChannelMaster.config.init,
          blockDay = msg.blockDay,
          localBalanceMsat =
            ChannelMaster.config.channelCapacityMsat - ChannelMaster.config.initialClientBalanceMsat,
          remoteBalanceMsat = ChannelMaster.config.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = List.empty,
          outgoingHtlcs = List.empty,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = msg.localSigOfRemoteLCSS
        )
          .withLocalSigOfRemote(ChannelMaster.node.privateKey)

        // step out of the "opening" state
        openingRefundScriptPubKey = None

        // check if everything is ok
        if ((msg.blockDay - ChannelMaster.currentBlockDay).abs > 1) {
          // we don't get a channel, but also do not send any errors
          localLogger.warn
            .item("local", ChannelMaster.currentBlockDay)
            .item("remote", msg.blockDay)
            .msg("peer sent state_update with wrong blockday")
        } else if (!lcssInitial.verifyRemoteSig(PublicKey(peerId))) {
          // we don't get a channel, but also do not send any errors
          localLogger.warn.msg("peer sent state_update with wrong signature")
        } else {
          // all good, save this channel to the database and consider it opened
          ChannelMaster.database.update { data =>
            data
              .modify(_.channels)
              .using(_ + (peerId -> ChannelData(lcss = lcssInitial)))
          }
          state = state.copy(lcssCurrent = lcssStored)

          // send our signed state update
          sendMessage(
            lcssInitial
              .withLocalSigOfRemote(ChannelMaster.node.privateKey)
              .stateUpdate
          )

          // send a channel update
          sendMessage(getChannelUpdate(true))
        }
      }

      // we're invoking a channel and the server is ok with it
      case init: InitHostedChannel
          if status == Invoking && invoking.get.isInstanceOf[ByteVector] => {
        // we just accept anything they offer, we don't care
        val spk = invoking.get.asInstanceOf[ByteVector]
        val lcss = LastCrossSignedState(
          isHost = false,
          refundScriptPubKey = spk,
          initHostedChannel = init,
          blockDay = ChannelMaster.currentBlockDay,
          localBalanceMsat = init.initialClientBalanceMsat,
          remoteBalanceMsat =
            init.channelCapacityMsat - init.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = List.empty,
          outgoingHtlcs = List.empty,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = ByteVector64.Zeroes
        )
          .withLocalSigOfRemote(ChannelMaster.node.privateKey)
        invoking = Some(lcss)

        sendMessage(
          StateUpdate(
            blockDay = ChannelMaster.currentBlockDay,
            localUpdates = 0L,
            remoteUpdates = 0L,
            localSigOfRemoteLCSS = lcss.localSigOfRemote
          )
        )
      }

      // final step of channel open process from the client side
      case msg: StateUpdate
          if status == Invoking && invoking.get
            .isInstanceOf[LastCrossSignedState] => {
        // we'll check if lcss they sent is the same we just signed
        val lcssInitial = invoking.get
          .asInstanceOf[LastCrossSignedState]
          .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

        // step out of the "invoking" state
        invoking = None

        if (lcssInitial.verifyRemoteSig(PublicKey(peerId)) == false) {
          // their lcss or signature is wrong, stop all here, we won't get a channel
          // but also do not send any errors
          localLogger.warn.msg("peer sent state_update with wrong signature")
        } else {
          // all good, save this channel to the database and consider it opened
          ChannelMaster.database.update { data =>
            data
              .modify(_.channels)
              .using(_ + (peerId -> ChannelData(lcss = lcssInitial)))
          }
          state = state.copy(lcssCurrent = lcssStored)

          // send a channel update
          sendMessage(getChannelUpdate(true))
        }
      }

      // a client is telling us they are online
      case msg: InvokeHostedChannel if status == Active =>
        // after a reconnection our peer won't have any of our
        //   current uncommitted updates
        val updatesToReplay = state.uncommittedUpdates
          .filter { case _: FromLocal => true; case _ => false }
        state = state.copy(uncommittedUpdates = List.empty)

        // send the committed state
        sendMessage(lcssStored)
          // replay the uncommitted updates now
          .andThen(_ =>
            // first the fail/fulfill
            updatesToReplay
              .collect {
                case m @ FromLocal(f, _) if !f.isInstanceOf[UpdateAddHtlc] => m
              }
              .foreach { m =>
                state = state.addUncommittedUpdate(m)
                sendMessage(m.upd)
              }
          )
          .andThen(_ =>
            // then the adds
            updatesToReplay
              .collect {
                case m @ FromLocal(add: UpdateAddHtlc, _) => {
                  val newAdd = add.copy(
                    id = state.lcssNext.localUpdates + 1L
                  )
                  state = state.addUncommittedUpdate(m.copy(upd = newAdd))
                  sendMessage(newAdd)
                }
              }
          )
          .andThen(_ =>
            // finally send our state_update
            if (updatesToReplay.size > 0) sendStateUpdate(state)
          )

      // if errored, when the client tries to invoke it we return the error
      case _: InvokeHostedChannel if status == Errored =>
        sendMessage(lcssStored)
          .andThen(_ => sendMessage(currentData.localErrors.head.error))

      // if we have an override proposal we return it when the client tries to invoke
      case _: InvokeHostedChannel if status == Overriding =>
        sendMessage(lcssStored)
          .andThen(_ =>
            currentData.localErrors.headOption.map { err =>
              sendMessage(err.error)
            }
          )
          .andThen(_ =>
            sendMessage(
              currentData.proposedOverride.get
                .withLocalSigOfRemote(ChannelMaster.node.privateKey)
                .stateOverride
            )
          )

      // after we've sent our last_cross_signed_state above, the client replies with theirs
      case msg: LastCrossSignedState => {
        val isLocalSigOk = msg.verifyRemoteSig(ChannelMaster.node.publicKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(PublicKey(peerId))

        if (!isLocalSigOk || !isRemoteSigOk) {
          val (err, reason) = if (!isLocalSigOk) {
            (
              Error(
                channelId,
                HostedError.ERR_HOSTED_WRONG_LOCAL_SIG
              ),
              "peer sent LastCrossSignedState with a signature that isn't ours"
            )
          } else {
            (
              Error(
                channelId,
                HostedError.ERR_HOSTED_WRONG_REMOTE_SIG
              ),
              "peer sent LastCrossSignedState with an invalid signature"
            )
          }
          localLogger.warn.msg(reason)
          sendMessage(err)
          ChannelMaster.database.update { data =>
            data
              .modify(_.channels.at(peerId).localErrors)
              .using(_ + DetailedError(err, None, reason))
          }
        } else if (status == Active || status == Opening) {
          if (
            (lcssStored.localUpdates + lcssStored.remoteUpdates) <
              (msg.remoteUpdates + msg.localUpdates)
          ) {
            // we are behind. replace our lcss with theirs.
            localLogger.warn
              .item(
                "local",
                s"${lcssStored.localUpdates}/${lcssStored.remoteUpdates}"
              )
              .item("remote", s"${msg.remoteUpdates}/${msg.localUpdates}")
              .msg("peer sent lcss showing that we are behind")

            // step out of the "opening" state
            openingRefundScriptPubKey = None

            // save their lcss here
            ChannelMaster.database.update { data =>
              data
                .modify(_.channels)
                .using(_ + (peerId -> ChannelData(lcss = msg.reverse)))
            }
            state = state.copy(lcssCurrent = lcssStored)
          }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssStored)
          sendMessage(getChannelUpdate(true))

          // investigate the situation of any payments that might be pending
          if (lcssStored.incomingHtlcs.size > 0) {
            val upto = lcssStored.incomingHtlcs.map(_.id).max
            Timer.timeout(3.seconds) { () =>
              lcssStored.incomingHtlcs.filter(_.id <= upto).foreach { htlc =>
                // try cached preimages first
                localLogger.debug
                  .item("in", htlc)
                  .msg("checking the outgoing status of pending incoming htlc")
                ChannelMaster.database.data.preimages
                  .get(htlc.paymentHash) match {
                  case Some(preimage) =>
                    gotPaymentResult(htlc.id, Some(Right(preimage)))
                  case None =>
                    localLogger.debug.msg("no preimage")
                    ChannelMaster.database.data.htlcForwards
                      .get(HtlcIdentifier(shortChannelId, htlc.id)) match {
                      case Some(outgoing @ HtlcIdentifier(outScid, outId)) =>
                        // it went to another HC peer, so just wait for it to resolve
                        // (if it had resolved already we would have the resolution on the preimages)
                        {
                          localLogger.debug
                            .item("out", outgoing)
                            .msg("it went to another hc peer")
                        }
                      case None =>
                        // it went to the upstream node, so ask that
                        ChannelMaster.node
                          .inspectOutgoingPayment(
                            HtlcIdentifier(shortChannelId, htlc.id),
                            htlc.paymentHash
                          )
                          .onComplete {
                            case Success(result) =>
                              gotPaymentResult(htlc.id, result)
                            case Failure(err) =>
                              localLogger.err
                                .item(err)
                                .msg("inspectOutgoingPayment failed")
                          }
                    }
                }
              }
            }
          }
        }
      }

      // client is fulfilling an HTLC we've sent
      case msg: UpdateFulfillHtlc if status == Active => {
        // find the htlc
        lcssStored.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            // call our htlc callback so our upstream node is notified
            // we do this to guarantee our money as soon as possible
            provideHtlcResult(htlc.id, Some(Right(msg.paymentPreimage)))

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
          if status == Active => {
        msg match {
          case f: UpdateFailHtlc if (f.reason.isEmpty) => {
            // fail the channel
            val err = Error(
              channelId,
              HostedError.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            ChannelMaster.database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(
                  _ + DetailedError(
                    err,
                    lcssStored.outgoingHtlcs.find(htlc => htlc.id == f.id),
                    "peer sent UpdateFailHtlc with empty 'reason'"
                  )
                )
            }
          }
          case _ =>
            // keep the updated state
            state = state.addUncommittedUpdate(FromRemote(msg))
        }
      }

      // client is sending an htlc through us
      case htlc: UpdateAddHtlc if status == Active => {
        state = state.addUncommittedUpdate(FromRemote(htlc))

        // check if fee and cltv delta etc are correct, otherwise return a failure
        Utils
          .parseClientOnion(ChannelMaster.node.privateKey, htlc)
          .map(_.packet) match {
          case Right(packet: PaymentOnion.ChannelRelayPayload) => {
            val inflightHtlcs =
              state.lcssNext.incomingHtlcs.size + state.lcssNext.outgoingHtlcs.size
            val inflightValue =
              (state.lcssNext.incomingHtlcs.map(_.amountMsat.toLong) ++
                state.lcssNext.outgoingHtlcs.map(_.amountMsat.toLong))
                .fold(0L)(_ + _)

            // critical failures, fail the channel
            // these should never happen because the peer will know enough to not proposed these invalid HTLCs
            if (
              state.lcssNext.localBalanceMsat < MilliSatoshi(0L) ||
              state.lcssNext.remoteBalanceMsat < MilliSatoshi(0L)
            ) {
              val err = Error(
                channelId,
                HostedError.ERR_HOSTED_MANUAL_SUSPEND
              )
              sendMessage(err)
              ChannelMaster.database.update { data =>
                data
                  .modify(_.channels.at(peerId).localErrors)
                  .using(
                    _ + DetailedError(
                      err,
                      Some(htlc),
                      s"peer sent an htlc that caused some balance to go below zero ${state.lcssNext.localBalanceMsat}/${state.lcssNext.remoteBalanceMsat}"
                    )
                  )
              }
            } else {
              // non-critical failures, just fail the htlc
              // (some are actually critical, but we're being lax since we hold their money anyway)
              val failure: Option[FailureMessage] = () match {
                case _
                    if inflightHtlcs > lcssStored.initHostedChannel.maxAcceptedHtlcs ||
                      inflightValue > lcssStored.initHostedChannel.maxHtlcValueInFlightMsat.toLong =>
                  Some(TemporaryChannelFailure(getChannelUpdate(true)))
                case _
                    if htlc.amountMsat < lcssStored.initHostedChannel.htlcMinimumMsat =>
                  Some(
                    AmountBelowMinimum(htlc.amountMsat, getChannelUpdate(true))
                  )
                case _ if htlc.amountMsat < packet.amountToForward =>
                  Some(FeeInsufficient(htlc.amountMsat, getChannelUpdate(true)))
                case _ => None
              }

              failure.foreach { f =>
                scala.concurrent.ExecutionContext.global.execute(() =>
                  gotPaymentResult(
                    htlc.id,
                    Some(Left(Some(NormalFailureMessage(f))))
                  )
                )
              }
            }
          }
          case Left(fail: FailureMessage) => {
            // this is a BadOnion error from Sphinx.peel, we just fail this htlc
            scala.concurrent.ExecutionContext.global.execute(() =>
              gotPaymentResult(
                htlc.id,
                Some(Left(Some(NormalFailureMessage(fail))))
              )
            )
          }
          // TODO decide what to do here (could be a payment directed to us etc)
          //      for now we just fail it
          case _ => {
            scala.concurrent.ExecutionContext.global.execute(() =>
              gotPaymentResult(
                htlc.id,
                Some(
                  Left(
                    Some(NormalFailureMessage(RequiredChannelFeatureMissing))
                  )
                )
              )
            )
          }
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // question: account for situations in which peer is behind us (ignore?) and for when we're behind?
      //   actually no, these mismatched states will never happen because TCP guarantees the order of messages
      //   -- we must handle them synchronously!
      //   -- if any concurrency is to be added it must be between channels, not inside the same channel.
      case msg: StateUpdate
          if status == Active && !state.uncommittedUpdates.isEmpty && (msg.remoteUpdates > lcssStored.localUpdates || msg.localUpdates > lcssStored.remoteUpdates) => {
        // this will only be triggered if there are uncommitted updates
        // otherwise it will be ignored so the client is free to spam us with
        // valid and up-to-date state_updates and we won't even notice
        localLogger.debug
          .item("local-blockday", state.lcssNext.blockDay)
          .item("remote-blockday", msg.blockDay)
          .item(
            "local-updates",
            s"${state.lcssNext.localUpdates}/${state.lcssNext.remoteUpdates}"
          )
          .item("remote-updates", s"${msg.remoteUpdates}/${msg.localUpdates}")
          .msg("updating our local state after a transition")

        if (msg.blockDay != ChannelMaster.currentBlockDay) {
          localLogger.warn.msg("blockdays are different")
        } else if (msg.localUpdates > state.lcssNext.remoteUpdates) {
          localLogger.debug.msg("we are missing updates from them")
        } else if (msg.remoteUpdates < state.lcssNext.localUpdates) {
          localLogger.debug.msg("they are missing updates from us")
        } else if (
          msg.localUpdates != state.lcssNext.remoteUpdates || msg.remoteUpdates != state.lcssNext.localUpdates
        ) localLogger.debug.msg("peer has a different state than we")
        else {
          // copy the state here so weird things don't happen in the meantime that break it
          // (although this is all synchronous so there shouldn't be any issue, but anyway)
          val currentState = state
          val lcssNext = currentState.lcssNext
            .withCurrentBlockDay(ChannelMaster.currentBlockDay)
            .withLocalSigOfRemote(ChannelMaster.node.privateKey)
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

          localLogger.debug
            .item(
              "updates",
              s"${lcssNext.localUpdates}/${lcssNext.remoteUpdates}"
            )
            .msg("we and the client are now even")
          // verify signature
          if (!lcssNext.verifyRemoteSig(PublicKey(peerId))) {
            // a wrong signature, fail the channel
            val err = Error(
              channelId,
              HostedError.ERR_HOSTED_WRONG_REMOTE_SIG
            )
            sendMessage(err)
            ChannelMaster.database.update { data =>
              data
                .modify(_.channels.at(peerId).localErrors)
                .using(
                  _ + DetailedError(
                    err,
                    None,
                    "peer sent a wrong state update or one with a broken signature"
                  )
                )
            }
          } else {
            // grab state before saving the update
            val lcssPrev = lcssStored

            // update new last_cross_signed_state on the database
            localLogger.info.item("lcss", lcssNext).msg("saving on db")
            ChannelMaster.database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(
                  ChannelData(lcss = lcssNext)
                )
                //
                // also remove the links for any htlcs that were relayed from elsewhere to this channel
                // (htlcs that were relayed from this channel to elsewhere will be handled on their side)
                .modify(_.htlcForwards)
                .using(fwd => {
                  val previousOutgoing = lcssPrev.outgoingHtlcs.toSet
                  val nextOutgoing = lcssNext.outgoingHtlcs.toSet
                  val resolved = (previousOutgoing -- nextOutgoing)
                    .map(htlc => HtlcIdentifier(shortChannelId, htlc.id))
                  val remains =
                    fwd.filterNot((_, to) => resolved.contains(to))
                  remains
                })
            }
            state = state.copy(lcssCurrent = lcssStored)

            // time to do some cleaning up -- non-priority
            scala.concurrent.ExecutionContext.global
              .execute(() => ChannelMaster.cleanupPreimages())

            // act on each pending message, relaying them as necessary
            currentState.uncommittedUpdates.foreach {
              // i.e. and fail htlcs if any
              case FromRemote(_: UpdateFee)   => // this will never happen
              case FromLocal(_: UpdateFee, _) => // this will never happen
              case FromRemote(fail: UpdateFailHtlc) =>
                provideHtlcResult(
                  fail.id,
                  Some(
                    Left(
                      Some(
                        // we don't unwrap it here, it will be unwrapped at gotPaymentResult on the other hosted channel
                        // or it will be unwraped on the node interface layer
                        FailureOnion(fail.reason)
                      )
                    )
                  )
                )
              case FromRemote(fail: UpdateFailMalformedHtlc) =>
                // for c-lightning there is no way to return this correctly,
                // so just return another error for now
                provideHtlcResult(
                  fail.id,
                  Some(
                    Left(
                      Some(
                        NormalFailureMessage(
                          InvalidOnionPayload(UInt64(0), 0)
                        )
                      )
                    )
                  )
                )
              case FromRemote(fulfill: UpdateFulfillHtlc) => {
                // we've already relayed this to the upstream node eagerly, so do nothing
              }
              case FromRemote(htlc: UpdateAddHtlc) => {
                // send a payment through the upstream node -- or to another hosted channel
                scala.concurrent.ExecutionContext.global.execute { () =>
                  Utils.parseClientOnion(
                    ChannelMaster.node.privateKey,
                    htlc
                  ) match {
                    case Left(fail) => {
                      // this should never happen
                      localLogger.err.msg(
                        "this should never happen because we had parsed the onion already"
                      )
                      gotPaymentResult(
                        htlc.id,
                        Some(
                          Left(
                            Some(
                              NormalFailureMessage(
                                InvalidOnionPayload(UInt64(0), 0)
                              )
                            )
                          )
                        )
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

                      scala.concurrent.ExecutionContext.global.execute(() =>
                        gotPaymentResult(
                          htlc.id,
                          Some(
                            Left(
                              Some(NormalFailureMessage(TemporaryNodeFailure))
                            )
                          )
                        )
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
                      // first check if it's for another hosted channel we may have
                      ChannelMaster.database.data.channels
                        .find((p, _) =>
                          HostedChannelHelpers.getShortChannelId(
                            ChannelMaster.node.publicKey.value,
                            p
                          ) == payload.outgoingChannelId
                        ) match {
                        case Some((targetPeerId, chandata)) => {
                          // it is a local hosted channel
                          // send it to the corresponding channel actor
                          ChannelMaster
                            .getChannel(targetPeerId)
                            .addHtlc(
                              htlcIn = HtlcIdentifier(shortChannelId, htlc.id),
                              paymentHash = htlc.paymentHash,
                              amountIn = htlc.amountMsat,
                              amountOut = payload.amountToForward,
                              cltvIn = htlc.cltvExpiry,
                              cltvOut = payload.outgoingCltv,
                              nextOnion = nextOnion
                            )
                            .foreach { res => gotPaymentResult(htlc.id, res) }
                        }
                        case None =>
                          // it is a normal channel on the upstream node
                          // use sendonion
                          ChannelMaster.node
                            .sendOnion(
                              chan = this,
                              htlcId = htlc.id,
                              paymentHash = htlc.paymentHash,
                              firstHop = payload.outgoingChannelId,
                              amount = payload.amountToForward,
                              cltvExpiryDelta =
                                htlc.cltvExpiry - payload.outgoingCltv,
                              onion = nextOnion
                            )
                      }
                    }
                  }
                }
              }
              case FromLocal(
                    htlc: UpdateAddHtlc,
                    Some(in: HtlcIdentifier)
                  ) => {
                // here we update the database with the mapping between received and sent htlcs
                // (now that we are sure the peer has accepted our update_add_htlc)
                ChannelMaster.database.update { data =>
                  data
                    .modify(_.htlcForwards)
                    .using(
                      _ + (in -> HtlcIdentifier(shortChannelId, htlc.id))
                    )
                }
              }
              case _: FromLocal => {
                // we mostly (except for the action above) do not take any action reactively with
                // updates we originated since we have sent them already before sending our state update
              }
            }

            // send our state update
            sendStateUpdate(currentState)

            // update the state manager state to the new lcss -- i.e. remove all the updates that were
            // committed from the list of uncommitted updates
            // if any  new updates were added in the meantime (shouldn't happen) those won't be affected
            state = state.copy(uncommittedUpdates =
              state.uncommittedUpdates.filterNot(upd =>
                currentState.uncommittedUpdates.exists(_ == upd)
              )
            )

            // clean up htlcResult promises that were already fulfilled
            htlcResults.filterInPlace((_, p) => !p.future.isCompleted)
          }
        }
      }

      // client is accepting our override proposal
      case msg: StateUpdate if status == Overriding => {
        if (
          msg.remoteUpdates == currentData.proposedOverride.get.localUpdates &&
          msg.localUpdates == currentData.proposedOverride.get.remoteUpdates &&
          msg.blockDay == currentData.proposedOverride.get.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = currentData.proposedOverride.get
            .withCurrentBlockDay(ChannelMaster.currentBlockDay)
            .withLocalSigOfRemote(ChannelMaster.node.privateKey)
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)

          if (lcss.verifyRemoteSig(PublicKey(peerId))) {
            // update state on the database
            ChannelMaster.database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .setTo(ChannelData(lcss = lcss))
            }
            // channel is active again
            state = StateManager(peerId = peerId, lcssCurrent = lcssStored)

            // send our channel policies again just in case
            sendMessage(getChannelUpdate(true))
          }
        }
      }

      // client is sending an error
      case msg: Error => {
        ChannelMaster.database.update { data =>
          data
            .modify(_.channels.at(peerId).remoteErrors)
            .using(_ + msg)

            // add a local error here so this channel is marked as "Errored" for future purposes
            .modify(_.channels.at(peerId).localErrors)
            .using(
              _ + DetailedError(
                Error(
                  channelId,
                  HostedError.ERR_HOSTED_CLOSED_BY_REMOTE_PEER
                ),
                None,
                "peer sent an error"
              )
            )
        }
      }

      case msg =>
        localLogger.debug.item("msg", msg).msg(s"unhandled")
    }
  }

  def onBlockUpdated(block: BlockHeight): Unit = {
    val expiredOutgoingHtlcs = lcssStored.outgoingHtlcs
      .filter(htlc => htlc.cltvExpiry.toLong < block.toLong)

    if (!expiredOutgoingHtlcs.isEmpty) {
      // if we have any HTLC, we fail the channel
      val err = Error(
        channelId,
        HostedError.ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC
      )
      sendMessage(err)

      // store one error for each htlc failed in this manner
      expiredOutgoingHtlcs.foreach { htlc =>
        ChannelMaster.database.update { data =>
          data
            .modify(_.channels.at(peerId).localErrors)
            .using(
              _ + DetailedError(
                err,
                Some(htlc),
                "outgoing htlc has expired"
              )
            )
        }
      }

      // we also fail them on their upstream node
      expiredOutgoingHtlcs
        .map(out =>
          ChannelMaster.database.data.htlcForwards
            .find((_, to) => to == out)
            .map((from, _) => from)
        )
        .collect { case Some(htlc) => htlc }
        .foreach { in =>
          // resolve htlcs with error for peer
          provideHtlcResult(
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

        }
    }

    // cleanup uncommitted htlcs that may be pending for so long they're now inviable
    state.uncommittedUpdates.collect {
      case m @ FromLocal(htlc: UpdateAddHtlc, _)
          if (htlc.cltvExpiry.blockHeight <= ChannelMaster.currentBlock + 2) => {
        state = state.removeUncommitedUpdate(m)

        // and fail them upstream
        provideHtlcResult(
          htlc.id,
          Some(
            Left(
              Some(
                NormalFailureMessage(
                  IncorrectOrUnknownPaymentDetails(
                    htlc.amountMsat,
                    ChannelMaster.currentBlock
                  )
                )
              )
            )
          )
        )
      }
    }
  }

  // opening a channel, as a client, to another hosted channel provider
  def requestHostedChannel(): Future[String] = {
    if (status != NotOpened) {
      Future.failed(
        new Exception(
          "can't open a channel that is already open."
        )
      )
    } else {
      ChannelMaster.node
        .getAddress()
        .map(Bech32.decodeWitnessAddress(_)._3)
        .flatMap(spk => {
          invoking = Some(spk)
          sendMessage(
            InvokeHostedChannel(
              chainHash = ChannelMaster.chainHash,
              refundScriptPubKey = spk,
              secret = ByteVector.empty
            )
          )
        })
        .map(res => res("status").str)
    }
  }

  // proposing to override a channel state, as a host, to the hosted client peer
  def proposeOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    logger.debug
      .item(status)
      .item("new-local-balance", newLocalBalance)
      .msg("proposing override")

    if (status != Errored && status != Overriding) {
      Future.failed(
        new Exception(
          "can't send to this channel since it is not errored or in overriding state."
        )
      )
    } else if (!currentData.lcss.isHost) {
      Future.failed(
        new Exception(
          "can't send to this channel since we are not the hosts."
        )
      )
    } else {
      val lcssOverride = currentData.proposedOverride
        .getOrElse(
          lcssStored
            .copy(
              incomingHtlcs = List.empty,
              outgoingHtlcs = List.empty,
              localUpdates = lcssStored.localUpdates + 1,
              remoteUpdates = lcssStored.remoteUpdates + 1,
              remoteSigOfLocal = ByteVector64.Zeroes,
              localSigOfRemote = ByteVector64.Zeroes
            )
        )
        .copy(
          localBalanceMsat = newLocalBalance,
          remoteBalanceMsat =
            lcssStored.initHostedChannel.channelCapacityMsat - newLocalBalance,
          blockDay = ChannelMaster.currentBlockDay
        )

      ChannelMaster.database.update { data =>
        data
          .modify(_.channels.at(peerId).proposedOverride)
          .setTo(Some(lcssOverride))
      }

      sendMessage(
        lcssOverride
          .withCurrentBlockDay(ChannelMaster.currentBlockDay)
          .withLocalSigOfRemote(ChannelMaster.node.privateKey)
          .stateOverride
      )
        .map((v: ujson.Value) => v("status").str)
    }
  }

  def getChannelUpdate(channelIsUp: Boolean): ChannelUpdate = {
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = LexicographicalOrdering
        .isLessThan(ChannelMaster.node.publicKey.value, peerId),
      isEnabled = channelIsUp
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              ChannelMaster.chainHash,
              shortChannelId,
              timestamp,
              flags,
              ChannelMaster.config.cltvExpiryDelta,
              ChannelMaster.config.htlcMinimumMsat,
              ChannelMaster.config.feeBase,
              ChannelMaster.config.feeProportionalMillionths,
              ChannelMaster.config.channelCapacityMsat,
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, ChannelMaster.node.privateKey)
    ChannelUpdate(
      signature = sig,
      chainHash = ChannelMaster.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = ChannelMaster.config.cltvExpiryDelta,
      htlcMinimumMsat = ChannelMaster.config.htlcMinimumMsat,
      feeBaseMsat = ChannelMaster.config.feeBase,
      feeProportionalMillionths =
        ChannelMaster.config.feeProportionalMillionths,
      htlcMaximumMsat = ChannelMaster.config.channelCapacityMsat
    )
  }

  def summary: String = {
    val printable = status match {
      case Opening => s"(${openingRefundScriptPubKey.get.toHex})"
      case Active =>
        s"(lcss=$lcssStored, uncommitted=${state.uncommittedUpdates})"
      case Overriding => s"(${currentData.proposedOverride.get})"
      case Errored    => s"(${currentData.localErrors})"
      case _          => ""
    }

    s"Channel[${peerId.toHex.take(7)}]${status.getClass.getSimpleName}$printable"
  }
}
