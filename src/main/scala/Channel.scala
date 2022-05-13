import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs._
import codecs.HostedChannelTags._
import codecs.HostedChannelCodecs._
import scodec.bits.ByteVector

sealed trait Msg
case class Send(msg: HostedServerMessage) extends Msg
case class Recv(msg: HostedClientMessage) extends Msg

class Channel(peerId: String)(implicit
    ac: castor.Context
) extends castor.StateMachineActor[Msg] {
  def ourInit = InitHostedChannel(
    maxHtlcValueInFlightMsat = 100000000L.toULong,
    htlcMinimumMsat = MilliSatoshi(1000L),
    maxAcceptedHtlcs = 12,
    channelCapacityMsat = MilliSatoshi(100000000L),
    initialClientBalanceMsat = MilliSatoshi(0)
  )

  def initialState =
    Database.data.channels.get(peerId).map(_.isActive) match {
      case Some(true) => Active()
      case _          => Inactive()
    }

  case class Inactive()
      extends State({
        case Recv(msg: InvokeHostedChannel) => {
          Database.data.channels.get(peerId) match {
            case Some(chandata) => { /* TODO channel exists, do something */
              Opening()
            }
            case None => {
              // channel doesn't exist, we're creating a new one now
              val lcss = LastCrossSignedState(
                isHost = true,
                refundScriptPubKey = msg.refundScriptPubKey,
                initHostedChannel = ourInit,
                blockDay = ChannelMaster.currentBlockDay,
                localBalanceMsat = ourInit.initialClientBalanceMsat,
                remoteBalanceMsat =
                  ourInit.channelCapacityMsat - ourInit.initialClientBalanceMsat,
                localUpdates = 0L,
                remoteUpdates = 0L,
                incomingHtlcs = Nil,
                outgoingHtlcs = Nil,
                localSigOfRemote = ByteVector64.Zeroes,
                remoteSigOfLocal = ByteVector64.Zeroes
              ).withLocalSigOfRemote(CLN.getPrivateKey())

              // save channel with lcss
              Database.data
                .modify(_.channels)
                .using(
                  _ +
                    (peerId -> ChannelData(
                      peerId = ByteVector.fromValidHex(peerId),
                      isActive = false,
                      lcss = lcss
                    ))
                )
              Database.save()

              CLN.sendCustomMessage(
                peerId,
                HC_INIT_HOSTED_CHANNEL_TAG,
                initHostedChannelCodec
                  .encode(ourInit)
                  .require
                  .toByteVector
              )

              Opening()
            }
          }
        }
        case _ => Inactive()
      })
  case class Opening()
      extends State({
        case Recv(msg: StateUpdate) => {
          Database.data.channels.get(peerId) match {
            case Some(chandata) =>
              // update our lcss with this, then send our own stateupdate
              Database.data
                .modify(_.channels.at(peerId).lcss)
                .setTo(
                  chandata.lcss.copy(
                    blockDay = msg.blockDay,
                    remoteSigOfLocal = msg.localSigOfRemoteLCSS
                  )
                )

              // check if everything is ok
              if ((msg.blockDay - ChannelMaster.currentBlockDay).abs > 1)
                // TODO error
                Inactive()
              else if (!chandata.lcss.verifyRemoteSig(chandata.peerId))
                // TODO error
                Inactive()
              else {
                Database.save()
                Opening()
              }
            case None =>
              CLN.log("failed to find channel data. this should never happen.")
              Inactive()
          }
        }
        case _ => Opening()
      })
  case class Active()
      extends State({
        case Send(msg: UpdateAddHtlc) => {
          Active()
        }
        case Recv(msg: AskBrandingInfo)         => Active()
        case Recv(msg: ResizeChannel)           => Active()
        case Recv(msg: UpdateAddHtlc)           => Active()
        case Recv(msg: UpdateFailHtlc)          => Active()
        case Recv(msg: UpdateFulfillHtlc)       => Active()
        case Recv(msg: UpdateFailMalformedHtlc) => Active()
        case _                                  => Active()
      })
}
