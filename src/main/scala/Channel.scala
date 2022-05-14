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
import crypto.Crypto
import codecs.HostedChannelTags._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait Msg
case class Send(msg: HostedServerMessage) extends Msg
case class Recv(msg: HostedClientMessage) extends Msg

class Channel(peerId: String)(implicit
    ac: castor.Context
) extends castor.StateMachineActor[Msg] {

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
                initHostedChannel = Main.ourInit,
                blockDay = Main.currentBlockDay,
                localBalanceMsat = Main.ourInit.initialClientBalanceMsat,
                remoteBalanceMsat =
                  Main.ourInit.channelCapacityMsat - Main.ourInit.initialClientBalanceMsat,
                localUpdates = 0L,
                remoteUpdates = 0L,
                incomingHtlcs = Nil,
                outgoingHtlcs = Nil,
                localSigOfRemote = ByteVector64.Zeroes,
                remoteSigOfLocal = ByteVector64.Zeroes
              )

              // save channel with lcss
              Database.update { data =>
                data
                  .modify(_.channels)
                  .using(
                    _ +
                      (peerId -> ChannelData(
                        peerId = ByteVector.fromValidHex(peerId),
                        isActive = false,
                        lcss = lcss
                      ))
                  )
              }
              Database.save()

              Main.node.sendCustomMessage(
                peerId,
                HC_INIT_HOSTED_CHANNEL_TAG,
                initHostedChannelCodec
                  .encode(Main.ourInit)
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
            case Some(chandata) => {
              // update our lcss with this, then send our own stateupdate
              Database.update { data =>
                data
                  .modify(_.channels.at(peerId).lcss)
                  .setTo(
                    chandata.lcss
                      .copy(
                        blockDay = msg.blockDay,
                        remoteSigOfLocal = msg.localSigOfRemoteLCSS
                      )
                      .withLocalSigOfRemote(Main.node.getPrivateKey())
                  )
              }

              // check if everything is ok
              if ((msg.blockDay - Main.currentBlockDay).abs > 1)
                Main.log(
                  s"[${peerId}] sent StateUpdate with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
                )
                Main.node.sendCustomMessage(
                  peerId,
                  HC_ERROR_TAG,
                  errorCodec
                    .encode(
                      Error(
                        chandata.channelId,
                        ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY
                      )
                    )
                    .require
                    .toByteVector
                )
                Inactive()
              else if (!chandata.lcss.verifyRemoteSig(chandata.peerId))
                Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
                Main.node.sendCustomMessage(
                  peerId,
                  HC_ERROR_TAG,
                  errorCodec
                    .encode(
                      Error(
                        chandata.channelId,
                        ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG
                      )
                    )
                    .require
                    .toByteVector
                )
                Inactive()
              else {
                // all good, save lcss
                Database.save()

                // and send our state update
                Main.node.sendCustomMessage(
                  peerId,
                  HC_STATE_UPDATE_TAG,
                  stateUpdateCodec
                    .encode(chandata.lcss.stateUpdate)
                    .require
                    .toByteVector
                )

                Active()
              }
            }
            case None => {
              Main.log(
                s"failed to find channel data for $peerId when Opening. this should never happen."
              )
              Inactive()
            }
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

        // these are only for PHC
        case Recv(msg: ChannelUpdate)       => Active()
        case Recv(msg: ChannelAnnouncement) => Active()

        case _ => Active()
      })
}
