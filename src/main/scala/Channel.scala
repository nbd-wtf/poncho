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
  def stay = state

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
              Opening(invoke = msg)
            }
            case None => {
              // save channel with no lcss
              Database.update { data =>
                {
                  data
                    .modify(_.channels)
                    .using(
                      _ +
                        (peerId -> ChannelData(
                          peerId = ByteVector.fromValidHex(peerId),
                          isActive = false,
                          lcss = None
                        ))
                    )
                }
              }
              Database.save()

              // reply saying we accept the invoke
              Main.node.sendCustomMessage(
                peerId,
                HC_INIT_HOSTED_CHANNEL_TAG,
                initHostedChannelCodec
                  .encode(Main.ourInit)
                  .require
                  .toByteVector
              )

              Opening(invoke = msg)
            }
          }
        }
        case _ => stay
      })
  case class Opening(invoke: InvokeHostedChannel)
      extends State({
        case Recv(msg: StateUpdate) => {
          Database.data.channels.get(peerId) match {
            case Some(chandata) => {
              // update our lcss with this, then send our own stateupdate
              Database.update { data =>
                data
                  .modify(_.channels.at(peerId).lcss)
                  .setTo(
                    Some(
                      LastCrossSignedState(
                        isHost = true,
                        refundScriptPubKey = invoke.refundScriptPubKey,
                        initHostedChannel = Main.ourInit,
                        blockDay = msg.blockDay,
                        localBalanceMsat =
                          Main.ourInit.initialClientBalanceMsat,
                        remoteBalanceMsat =
                          Main.ourInit.channelCapacityMsat - Main.ourInit.initialClientBalanceMsat,
                        localUpdates = 0L,
                        remoteUpdates = 0L,
                        incomingHtlcs = Nil,
                        outgoingHtlcs = Nil,
                        localSigOfRemote = ByteVector64.Zeroes,
                        remoteSigOfLocal = msg.localSigOfRemoteLCSS
                      )
                        .withLocalSigOfRemote(Main.node.getPrivateKey())
                    )
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
              else if (!chandata.lcss.get.verifyRemoteSig(chandata.peerId))
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
                    .encode(chandata.lcss.get.stateUpdate)
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
        case _ => stay
      })
  case class Active()
      extends State({
        case Send(msg: UpdateAddHtlc) => {
          Active()
        }

        case Recv(msg: AskBrandingInfo)         => stay
        case Recv(msg: ResizeChannel)           => stay
        case Recv(msg: UpdateAddHtlc)           => stay
        case Recv(msg: UpdateFailHtlc)          => stay
        case Recv(msg: UpdateFulfillHtlc)       => stay
        case Recv(msg: UpdateFailMalformedHtlc) => stay

        // these are only for PHC
        case Recv(msg: ChannelUpdate)       => stay
        case Recv(msg: ChannelAnnouncement) => stay

        case _ => stay
      })
}
