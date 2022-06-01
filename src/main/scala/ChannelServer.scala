import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}
import scodec.{DecodeResult, Attempt}

import codecs._
import crypto.Crypto
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import codecs.Sphinx.peel
import scodec.bits.ByteVector
import scodec.codecs._

// TODO questions:
// should we fail all pending incoming htlcs on our actual normal node side whenever we fail the channel (i.e. send an Error message -- or receive an error message?)

case class FailureOnion(onion: ByteVector)
case class FailureCode(code: String)
type PaymentFailure = FailureOnion | FailureCode
type PaymentPreimage = ByteVector32
type HTLCResult =
  Option[Either[PaymentFailure, PaymentPreimage]]

class ChannelServer(peerId: String)(implicit
    ac: castor.Context
) extends castor.SimpleActor[HostedClientMessage] {
  sealed trait State
  case class Inactive() extends State
  case class Opening(refundScriptPubKey: ByteVector) extends State
  case class Active(
      lcssNext: Option[LastCrossSignedState] = None,
      htlcResults: Map[ULong, Promise[HTLCResult]] = Map.empty,
      pendingMessages: List[
        UpdateFailHtlc | UpdateFailMalformedHtlc | UpdateAddHtlc
      ] = List.empty
  ) extends State
  case class Errored(lcssNext: Option[LastCrossSignedState]) extends State
  case class Overriding(target: LastCrossSignedState) extends State

  var state: State =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive => Active()
      case _                                   => Inactive()
    }

  def stay = state

  def sendMessage: HostedServerMessage => Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, _)

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
              ChanTools.getChannelId(peerId),
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
            s"[${peerId}] sent StateUpdate with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
          )
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
          Inactive()
        } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
          Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
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
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

          Active()
        }
      }

      // a client was just turned on and is sending this to sync states
      case (Active(_, _, _), msg: LastCrossSignedState) => {
        val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(ByteVector.fromValidHex(peerId))

        if (!isLocalSigOk || !isRemoteSigOk) {
          val err = if (!isLocalSigOk) {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with a signature that isn't ours"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with an invalid signature"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          sendMessage(err)
          Database.update { data =>
            {
              data
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
            }
          }
          Inactive()
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
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcssMostRecent))
          stay
        }
      }

      // a client has lost its channel state but we have it
      case (Active(_, _, _), msg: InvokeHostedChannel) => {
        // channel already exists, so send last cross-signed-state
        val chandata = Database.data.channels.get(peerId).get
        sendMessage(chandata.lcss)
        stay
      }

      // client is fulfilling an HTLC we've sent
      case (
            active @ Active(maybeLcssNext, htlcResults, x),
            msg: UpdateFulfillHtlc
          ) => {
        Main.log(s"got fulfill!")

        // create (or modify) new lcss to be our next
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

        // find the htlc
        lcssNextBase.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            Main.log(s"resolving htlc ${htlc.paymentHash}")

            val lcssNext =
              lcssNextBase
                .copy(
                  remoteBalanceMsat =
                    lcssNextBase.remoteBalanceMsat + htlc.amountMsat,
                  remoteUpdates = lcssNextBase.remoteUpdates + 1,
                  outgoingHtlcs =
                    lcssNextBase.outgoingHtlcs.filterNot(_.id == htlc.id),
                  remoteSigOfLocal = ByteVector64.Zeroes,
                  localSigOfRemote = ByteVector64.Zeroes
                )

            // check if this new potential lcss is not stupid
            if (ChanTools.lcssIsBroken(lcssNext)) {
              // TODO we should get a proper error here and fail the channel
              Main.log(s"lcssNext is broken: $lcssNext")
              stay
            } else {
              // call our htlc callback so our node is notified
              // we do this to guarantee our money as soon as possible
              System.err.println(s"results pending: $htlcResults")
              System.err.println(s"that other thing: $x")
              htlcResults
                .get(htlc.id)
                .foreach(_.success(Some(Right(msg.paymentPreimage))))

              // update state (lcssNext, plus remove this from the callbacks we're keeping track of)
              active.copy(
                lcssNext = Some(lcssNext),
                htlcResults = htlcResults
                  .filterNot((hash, _) => hash == htlc.paymentHash)
              )
            }
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
            active @ Active(maybeLcssNext, _, pendingMessages),
            msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          ) => {
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

        val htlcId = msg match {
          case x: UpdateFailHtlc          => x.id;
          case x: UpdateFailMalformedHtlc => x.id
        }

        // TODO check if 'reason' is not empty

        // we just update our local next state here and wait to actually tell the node
        // to fail the payment when we get their signed StateUpdate
        lcssNextBase.outgoingHtlcs.find(_.id == htlcId) match {
          case Some(htlc) => {
            val lcssNext = lcssNextBase.copy(
              localBalanceMsat =
                lcssNextBase.localBalanceMsat + htlc.amountMsat,
              remoteUpdates = lcssNextBase.remoteUpdates + 1,
              outgoingHtlcs =
                lcssNextBase.outgoingHtlcs.filterNot(_.id == htlc.id),
              remoteSigOfLocal = ByteVector64.Zeroes,
              localSigOfRemote = ByteVector64.Zeroes
            )

            if (ChanTools.lcssIsBroken(lcssNext)) {
              // TODO send an error to this invalid client?
              stay
            } else {
              active.copy(
                lcssNext = Some(lcssNext),
                pendingMessages = pendingMessages :+ msg
              )
            }
          }
          case None => {
            Main.log(s"client has failed an HTLC we don't know: ${msg}")
            stay
          }
        }
      }

      // client is sending an htlc through us
      case (
            active @ Active(maybeLcssNext, _, pendingMessages),
            msg: UpdateAddHtlc
          ) => {
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

          // TODO check if fees are ok

          // we just update our local next state here and wait to actually tell the node
          // to send the payment when we get their signed StateUpdate
        val lcssNext = lcssNextBase.copy(
          remoteBalanceMsat = lcssNextBase.remoteBalanceMsat - msg.amountMsat,
          remoteUpdates = lcssNextBase.remoteUpdates + 1,
          incomingHtlcs = lcssNextBase.incomingHtlcs :+ msg,
          remoteSigOfLocal = ByteVector64.Zeroes,
          localSigOfRemote = ByteVector64.Zeroes
        )

        if (ChanTools.lcssIsBroken(lcssNext)) {
          // TODO send an error to this invalid client?
          stay
        } else {
          active.copy(lcssNext = Some(lcssNext))
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // TODO this should account for situations in which peer is behind us (ignore?) and for when we're behind (keep track of the forward state?)
      case (
            Active(Some(lcssNext), htlcResults, pendingMessages),
            msg: StateUpdate
          ) => {
        Main.log(s"updating our local state after a transition")
        // this won't be triggered if we don't have a pending next lcss

        if (
          msg.remoteUpdates == lcssNext.localUpdates &&
          msg.localUpdates == lcssNext.remoteUpdates &&
          msg.blockDay == lcssNext.blockDay
        ) {
          Main.log("it seems ok")
          // it seems we and the client are now even

          // relay pending messages to node
          pendingMessages.foreach {
            // i.e. and fail htlcs if any
            case fail: UpdateFailHtlc =>
              htlcResults
                .get(fail.id)
                .foreach(_.success(Some(Left(FailureCode("2002")))))
            case fail: UpdateFailMalformedHtlc =>
              htlcResults
                .get(fail.id)
                .foreach(_.success(Some(Left(FailureCode("2002")))))
            case add: UpdateAddHtlc => {
              // and send payments to the external world

              // decode onion
              PaymentOnionCodecs.paymentOnionPacketCodec
                .decode(add.onionRoutingPacket.toBitVector)
                .toEither match {
                case Left(_) => {
                  // TODO reply with UpdateFailMalformedHtlc
                }
                case Right(onion) =>
                  Sphinx.peel(
                    Main.node.getPrivateKey(),
                    Some(add.paymentHash),
                    onion.value
                  ) match {
                    case Left(badOnion) => Left(badOnion)
                    case Right(
                          p @ Sphinx.DecryptedPacket(payload, nextPacket, _)
                        ) =>
                      PaymentOnionCodecs
                        .paymentOnionPerHopPayloadCodec(p.isLastPacket)
                        .decode(payload.bits) match {
                        case Attempt.Successful(
                              DecodeResult(perHopPayload, _)
                            ) =>
                          Right((perHopPayload, nextPacket))
                        case Attempt.Failure(
                              e: OnionRoutingCodecs.MissingRequiredTlv
                            ) =>
                          Left(e.failureMessage)
                        case Attempt.Failure(_) =>
                          Left(InvalidOnionPayload(0.toULong, 0))
                      }
                  } match {
                    case Left(failureMessage) => {
                      // TODO reply with failure
                    }
                    case Right((payload: PaymentOnion.FinalTlvPayload, _)) => {
                      // TODO we're receiving the payment? this is weird but possible.
                      // figure out how to handle this later, it probably involves checking our parent node invoices.
                      // could also be a trampoline, so when we want to support that we'll have to look again at the
                      // eclair code to see how they're doing it.
                    }
                    case Right(
                          (
                            payload: PaymentOnion.ChannelRelayPayload,
                            next
                          )
                        ) => {
                      // TODO check if fee and cltv delta are correct
                      // TODO get next peer, send onion
                      System.err.println(s"got an onion: $payload")
                      // Main.node.getPeerFromChannel(payload.outgoingChannelId)
                      // Main.node.sendOnion()
                    }
                  }
              }
            }
          }

          // save this cross-signed state
          val lcss = lcssNext
            .copy(remoteSigOfLocal = msg.localSigOfRemoteLCSS)
            .withLocalSigOfRemote(Main.node.getPrivateKey())
          if (lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Main.log(s"saving on db: $lcss")
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }

            // send our state update
            sendMessage(lcss.stateUpdate)

            // keep new Active state except for the htlcResults promises we keep tracking
            // (but cleaned up of the ones that were already fulfilled)
            Active(
              htlcResults = htlcResults.filterNot((_, promise) =>
                promise.future.isCompleted
              )
            )
          } else stay
        } else stay
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
          if (lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }

            // send our channel policies again just in case
            sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

            // channel is active again
            Active()
          } else stay
        } else stay
      }

      // client is sending an error
      case (Active(maybeLcssNext, _, _), msg: Error) => {
        Database.update { data =>
          data.modify(_.channels.at(peerId).error).setTo(Some(msg))
        }
        Errored(lcssNext = maybeLcssNext)
      }

      case _ => stay
    }
    Main.log(s"    ~ afterwards $state ~")
  }

  def addHTLC(prototype: UpdateAddHtlc): Future[HTLCResult] = {
    var promise = Promise[HTLCResult]()

    Main.log(s"forwarding payment $state <-- $prototype")
    state match {
      case active @ Active(maybeLcssNext, htlcResults, _) => {
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

        if (
          lcssNextBase.incomingHtlcs.exists(
            _.paymentHash == prototype.paymentHash
          )
        ) {
          // reject htlc as outgoing if it's already incoming, sanity check
          Main.log(
            s"${prototype.paymentHash} is already incoming, can't add it as outgoing"
          )
          promise.success(None)
        } else if (
          lcssNextBase.outgoingHtlcs.exists(
            _.paymentHash == prototype.paymentHash
          )
        ) {
          // do not add htlc to state if it's already there (otherwise the state will be invalid)
          // this is likely to be hit on restarts as the node will replay pending htlcs on us
          Main.log("won't forward the htlc as it's already there")

          // but we still want to update the callbacks we're keeping track of (because we've restarted!)
          val add = lcssNextBase.outgoingHtlcs
            .find(_.paymentHash == prototype.paymentHash)
            .get
          state = active.copy(
            lcssNext = Some(lcssNextBase),
            htlcResults = htlcResults + (add.id -> promise)
          )
        } else {
          // the default case in which we add a new htlc
          // create update_add_htlc based on the prototype we've received
          val msg = prototype
            .copy(id = lcssNextBase.localUpdates.toULong + 1L.toULong)

          // prepare modification to new lcss to be our next
          def prepareNextLcss(
              lcssBase: LastCrossSignedState
          ): LastCrossSignedState = lcssBase
            .copy(
              blockDay = Main.currentBlockDay,
              localBalanceMsat = lcssBase.localBalanceMsat - msg.amountMsat,
              localUpdates = lcssBase.localUpdates + 1,
              outgoingHtlcs = lcssBase.outgoingHtlcs :+ msg,
              remoteSigOfLocal = ByteVector64.Zeroes
            )
            .withLocalSigOfRemote(Main.node.getPrivateKey())

          // TODO check if fees are sufficient

          // check if this new potential lcss is not stupid
          if (ChanTools.lcssIsBroken(prepareNextLcss(lcssNextBase))) {
            // TODO provide the correct failure message here
            promise.success(Some(Left(FailureCode("2002"))))
          } else {
            // will send update_add_htlc to hosted client
            //
            // but first we update the callbacks we're keeping track of
            state = active.copy(htlcResults = htlcResults + (msg.id -> promise))
            Main.log(s"sending update_add_htlc, tracking htlcResults: $state")

            sendMessage(msg)
              .onComplete {
                case Success(_) =>
                  state match {
                    case active @ Active(maybeLcssNext, _, _) => {
                      // only here we create, commit and send the next state update
                      val lcssNext = prepareNextLcss(
                        maybeLcssNext
                          .orElse(
                            Database.data.channels.get(peerId).map(_.lcss)
                          )
                          .get
                      )

                      // prepare next
                      state = active.copy(lcssNext = Some(lcssNext))

                      // send state_update
                      Main.log(
                        s"sending state_update, tracking next lcss: $lcssNext"
                      )
                      sendMessage(lcssNext.stateUpdate)
                    }
                    case _ => {
                      // some error must have happened if we arrive here
                      Main.log(
                        "channel changed from Active to something else in the course of sending the update_add_htlc"
                      )
                    }
                  }
                case Failure(err) => {
                  Main.log(s"failed to send update_add_htlc to $peerId: $err")
                  promise.success(None)
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
  }

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
