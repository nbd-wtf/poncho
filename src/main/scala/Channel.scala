import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.Future
import scala.util.{Failure, Success}
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

type FailureOnion = ByteVector
type Preimage = ByteVector
type HTLCCallback = Option[Either[FailureOnion, Preimage]] => Unit

sealed trait Msg
case class Send(
    msg: HostedServerMessage,
    callback: HTLCCallback
) extends Msg
case class Recv(msg: HostedClientMessage) extends Msg

class Channel(peerId: String)(implicit
    ac: castor.Context
) extends castor.StateMachineActor[Msg] {
  def stay = state

  def initialState =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive =>
        Active(lcssNext = None, cbs = Map.empty)
      case _ => Inactive()
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
            Main.node.sendCustomMessage(
              peerId,
              Error(
                ChannelMaster.getChannelId(peerId),
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
            Main.node.sendCustomMessage(
              peerId,
              Error(
                ChannelMaster.getChannelId(peerId),
                ErrorCodes.ERR_HOSTED_WRONG_BLOCKDAY
              )
            )
            Inactive()
          } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
            Main.node.sendCustomMessage(
              peerId,
              Error(
                ChannelMaster.getChannelId(peerId),
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
                ChannelMaster.makeChannelUpdate(peerId, lcss)
              )

            Active(None, Map.empty)
          }
        }
        case _ => stay
      })
  case class Active(
      lcssNext: Option[LastCrossSignedState],
      cbs: Map[String, HTLCCallback]
  ) extends State({ input =>
        {
          // channel is active, which means we must have a database entry necessarily
          val chandata = Database.data.channels.get(peerId).get
          input match {
            case Recv(msg: InvokeHostedChannel) => {
              // channel already exists, so send last cross-signed-state
              Main.node.sendCustomMessage(peerId, chandata.lcss)
              stay
            }

            case Recv(msg: LastCrossSignedState) => {
              val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
              val isRemoteSigOk =
                msg.reverse.verifyRemoteSig(ByteVector.fromValidHex(peerId))

              if (!isLocalSigOk || !isRemoteSigOk) {
                val err = if (!isLocalSigOk) {
                  Main.log(
                    s"[${peerId}] sent LastCrossSignedState with a signature that isn't ours"
                  )
                  Error(
                    ChannelMaster.getChannelId(peerId),
                    ErrorCodes.ERR_HOSTED_WRONG_LOCAL_SIG
                  )
                } else {
                  Main.log(
                    s"[${peerId}] sent LastCrossSignedState with an invalid signature"
                  )
                  Error(
                    ChannelMaster.getChannelId(peerId),
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
                val lcssMostRecent = if (
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
                  ChannelMaster.makeChannelUpdate(peerId, lcssMostRecent)
                )
                stay
              }
            }

            case Send(msg: UpdateAddHtlc, cb: HTLCCallback) => {
              // send update_add_htlc
              Main.node.sendCustomMessage(
                peerId,
                msg.copy(id =
                  lcssNext
                    .map(_.localUpdates)
                    .getOrElse(0L)
                    .toULong + 1L.toULong
                ),
                () => {
                  cb(None)
                }
              )

              // create new lcss to be our next
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

              // send state_update
              Main.node.sendCustomMessage(peerId, lcss.stateUpdate)

              // update callbacks we're keeping track of
              Active(
                lcssNext = Some(lcss),
                cbs = cbs + (msg.paymentHash.toString -> cb)
              )
            }

            case Recv(msg: StateUpdate) => {
              stay
            }

            case Recv(msg: UpdateFailHtlc)          => stay
            case Recv(msg: UpdateFulfillHtlc)       => stay
            case Recv(msg: UpdateFailMalformedHtlc) => stay

            case Recv(msg: AskBrandingInfo) => stay
            case Recv(msg: ResizeChannel)   => stay
            case Recv(msg: UpdateAddHtlc)   => stay

            // these are only for PHC
            case Recv(msg: ChannelUpdate)       => stay
            case Recv(msg: ChannelAnnouncement) => stay

            case _ => stay
          }
        }
      })
}
