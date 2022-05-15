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

  override def run(msg: Msg): Unit = {
    Main.log(s"[$this] at $state <-- $msg")
    super.run(msg)
  }

  case class Inactive()
      extends State({
        case Recv(msg: InvokeHostedChannel) => {
          Database.data.channels.get(peerId) match {
            case Some(chandata) => {
              // channel already exists, so send last cross-signed-state
              Main.node.sendCustomMessage(
                peerId,
                HC_LAST_CROSS_SIGNED_STATE_TAG,
                lastCrossSignedStateCodec
                  .encode(chandata.lcss)
                  .require
                  .toByteVector
              )
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
            case None => {
              // reply saying we accept the invoke
              Main.node.sendCustomMessage(
                peerId,
                HC_INIT_HOSTED_CHANNEL_TAG,
                initHostedChannelCodec
                  .encode(Main.ourInit)
                  .require
                  .toByteVector
              )

              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
          }
        }
        case _ => stay
      })
  case class Opening(refundScriptPubKey: ByteVector)
      extends State({
        case Recv(msg: StateUpdate) => {
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
                    ChannelMaster.getChannelId(peerId),
                    ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY
                  )
                )
                .require
                .toByteVector
            )
            Inactive()
          else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId)))
            Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
            Main.node.sendCustomMessage(
              peerId,
              HC_ERROR_TAG,
              errorCodec
                .encode(
                  Error(
                    ChannelMaster.getChannelId(peerId),
                    ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG
                  )
                )
                .require
                .toByteVector
            )
            Inactive()
          else {
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

            // and send our state update
            Main.node.sendCustomMessage(
              peerId,
              HC_STATE_UPDATE_TAG,
              stateUpdateCodec
                .encode(lcss.stateUpdate)
                .require
                .toByteVector
            )

            Active()
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
