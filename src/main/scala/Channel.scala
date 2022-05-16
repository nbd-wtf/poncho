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
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait Msg
case class Send(msg: HostedServerMessage[_]) extends Msg
case class Recv(msg: HostedClientMessage[_]) extends Msg

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
          // check chain hash
          if (msg.chainHash != Main.chainHash) {
            Main.log(
              s"[${peerId}] sent InvokeHostedChannel for wrong chain: ${msg.chainHash} (current: ${Main.chainHash})"
            )

            val err = Error(
              ChannelMaster.getChannelId(peerId),
              s"invalid chainHash (local=${Main.chainHash} remote=${msg.chainHash})"
            )
            Main.node.sendCustomMessage(
              peerId,
              err.tag,
              err.codec.encode(err).require.toByteVector
            )
            stay
          } else {
            // chain hash is ok, proceed
            Database.data.channels.get(peerId) match {
              case Some(chandata) => {
                // channel already exists, so send last cross-signed-state
                Main.node.sendCustomMessage(
                  peerId,
                  chandata.lcss.tag,
                  chandata.lcss.codec
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
                  Main.ourInit.tag,
                  Main.ourInit.codec
                    .encode(Main.ourInit)
                    .require
                    .toByteVector
                )

                Opening(refundScriptPubKey = msg.refundScriptPubKey)
              }
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
          if ((msg.blockDay - Main.currentBlockDay).abs > 1) {
            Main.log(
              s"[${peerId}] sent StateUpdate with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
            )
            val err =
              Error(
                ChannelMaster.getChannelId(peerId),
                ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY
              )
            Main.node.sendCustomMessage(
              peerId,
              err.tag,
              err.codec
                .encode(err)
                .require
                .toByteVector
            )
            Inactive()
          } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
            val err =
              Error(
                ChannelMaster.getChannelId(peerId),
                ErrorCodes.ERR_HOSTED_WRONG_REMOTE_SIG
              )
            Main.node.sendCustomMessage(
              peerId,
              err.tag,
              err.codec
                .encode(err)
                .require
                .toByteVector
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

            // and send our state update
            Main.node.sendCustomMessage(
              peerId,
              lcss.stateUpdate.tag,
              lcss.stateUpdate.codec
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
