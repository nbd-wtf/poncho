import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs._
import crypto.Crypto
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import scodec.bits.ByteVector
import scodec.codecs._

case class FailureOnion(onion: ByteVector)
case class FailureCode(code: String)
type PaymentFailure = FailureOnion | FailureCode
type PaymentPreimage = ByteVector
type HTLCResult =
  Option[Either[PaymentFailure, PaymentPreimage]]

sealed trait State
case class Inactive() extends State
case class Opening(refundScriptPubKey: ByteVector) extends State
case class Active(
    lcssNext: Option[LastCrossSignedState],
    htlcResults: Map[String, Promise[HTLCResult]]
) extends State

class Channel(peerId: String)(implicit
    ac: castor.Context
) extends castor.SimpleActor[HostedClientMessage] {
  var state: State =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive =>
        Active(lcssNext = None, htlcResults = Map.empty)
      case _ => Inactive()
    }

  def stay = state

  def run(msg: HostedClientMessage): Unit = {
    Main.log(s"[$this] at $state <-- $msg")
    state = (state, msg) match {
      case (Inactive(), msg: InvokeHostedChannel) => {
        // check chain hash
        if (msg.chainHash != Main.chainHash) {
          Main.log(
            s"[${peerId}] sent InvokeHostedChannel for wrong chain: ${msg.chainHash} (current: ${Main.chainHash})"
          )
          Main.node.sendCustomMessage(
            peerId,
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
              Main.node.sendCustomMessage(peerId, chandata.lcss)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
            case None => {
              // reply saying we accept the invoke
              Main.node.sendCustomMessage(peerId, Main.ourInit)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
          }
        }
      }
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
          Main.node.sendCustomMessage(
            peerId,
            Error(
              ChanTools.getChannelId(peerId),
              ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
          Inactive()
        } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
          Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
          Main.node.sendCustomMessage(
            peerId,
            Error(
              ChanTools.getChannelId(peerId),
              ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG
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
                        lcss = lcss
                      )
                    )
                )
            }
          }

          // send our signed state update
          Main.node.sendCustomMessage(peerId, lcss.stateUpdate)

          // send a channel update
          Main.node
            .sendCustomMessage(
              peerId,
              ChanTools.makeChannelUpdate(peerId, lcss)
            )

          Active(None, Map.empty)
        }
      }
      case (Active(_, _), msg: LastCrossSignedState) => {
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
              ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with an invalid signature"
            )
            Error(
              ChanTools.getChannelId(peerId),
              ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          Main.node.sendCustomMessage(peerId, err)
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
                    .setTo(msg)
                }
              }

              msg
            }

          // all good, send the most recent lcss again and then the channel update
          Main.node.sendCustomMessage(peerId, lcssMostRecent)
          Main.node.sendCustomMessage(
            peerId,
            ChanTools.makeChannelUpdate(peerId, lcssMostRecent)
          )
          stay
        }
      }

      case (Active(_, _), msg: InvokeHostedChannel) => {
        // channel already exists, so send last cross-signed-state
        val chandata = Database.data.channels.get(peerId).get
        Main.node.sendCustomMessage(peerId, chandata.lcss)
        stay
      }
      case _ => stay
    }
  }

  def addHTLC(prototype: UpdateAddHtlc): Future[HTLCResult] = {
    var promise = Promise[HTLCResult]()

    state match {
      case Active(lcssNext, htlcResults) => {
        val chandata = Database.data.channels.get(peerId).get

        // create update_add_htlc based on the prototype we've received
        val msg = prototype.copy(id =
          lcssNext
            .map(_.localUpdates)
            .getOrElse(0L)
            .toULong + 1L.toULong
        )

        // create (or modify) new lcss to be our next
        val lcss = lcssNext
          .getOrElse(
            chandata.lcss.copy(
              remoteSigOfLocal = ByteVector64.Zeroes,
              localSigOfRemote = ByteVector64.Zeroes
            )
          )
          .pipe(baseLcssNext =>
            baseLcssNext
              .copy(
                blockDay = Main.currentBlockDay,
                localBalanceMsat =
                  baseLcssNext.localBalanceMsat - msg.amountMsat,
                localUpdates = baseLcssNext.localUpdates + 1,
                outgoingHtlcs = baseLcssNext.outgoingHtlcs :+ msg,
                remoteSigOfLocal = ByteVector64.Zeroes
              )
              .withLocalSigOfRemote(Main.node.getPrivateKey())
          )

        // TODO check if fees are sufficient

        // check if this new potential lcss is not stupid
        if (lcss.localBalanceMsat < MilliSatoshi(0L)) {
          // TODO provide the correct failure message here
          promise.success(Some(Left(FailureCode("2002"))))
        } else {
          // send update_add_htlc
          Main.node
            .sendCustomMessage(peerId, msg)
            .onComplete {
              case Failure(err) =>
                promise.success(None)
              case _ => {}
            }

          // send state_update
          Main.node.sendCustomMessage(peerId, lcss.stateUpdate)

          // update callbacks we're keeping track of
          state = Active(
            lcssNext = Some(lcss),
            htlcResults = htlcResults + (msg.paymentHash.toString -> promise)
          )
        }
      }
      case _ => {}
    }

    promise.future
  }

  def stateOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    state match {
      case Active(lcssNext, _) => {
        val lcssBase =
          lcssNext.getOrElse(Database.data.channels.get(peerId).get.lcss)

        val lcssOverride = lcssBase
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              lcssBase.initHostedChannel.channelCapacityMsat - newLocalBalance,
            incomingHtlcs = Nil,
            outgoingHtlcs = Nil,
            localUpdates = lcssBase.localUpdates + 1,
            remoteUpdates = lcssBase.remoteUpdates + 1,
            blockDay = Main.currentBlockDay,
            remoteSigOfLocal = ByteVector64.Zeroes
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        val msg = StateOverride(
          lcssOverride.blockDay,
          lcssOverride.localBalanceMsat,
          lcssOverride.localUpdates,
          lcssOverride.remoteUpdates,
          lcssOverride.localSigOfRemote
        )

        Main.node
          .sendCustomMessage(peerId, msg)
          .map((v: ujson.Value) => v("status").str)
      }
      case _ => {
        Future { s"can't send to this channel since it is not active." }
      }
    }
  }
}
