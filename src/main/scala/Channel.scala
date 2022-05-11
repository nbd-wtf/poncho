import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.scalanative.unsigned._
import com.softwaremill.quicklens
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs._
import codecs.HostedChannelTags._
import codecs.HostedChannelCodecs._
import scodec.bits.ByteVector

sealed trait Msg
case class Send(msg: HostedServerMessage) extends Msg
case class Recv(msg: HostedClientMessage) extends Msg

class Channel(peerId: String, data: Option[ChannelData])(implicit
    ac: castor.Context
) extends castor.StateMachineActor[Msg] {
  def ourInit = InitHostedChannel(
    maxHtlcValueInFlightMsat = 100000000L.toULong,
    htlcMinimumMsat = MilliSatoshi(1000L),
    maxAcceptedHtlcs = 12,
    channelCapacityMsat = MilliSatoshi(100000000L),
    initialClientBalanceMsat = MilliSatoshi(0)
  )

  def getCurrentBlockDay(): Future[Long] =
    CLN.rpc("getchaininfo", ujson.Obj()).map(_("headercount").num.toLong)

  def initialState =
    if (data.isDefined && data.get.isActive) Active() else Inactive()

  case class Inactive()
      extends State({
        case Recv(msg: InvokeHostedChannel) => {
          getCurrentBlockDay().foreach(blockDay => {
            val lcss = LastCrossSignedState(
              isHost = true,
              refundScriptPubKey = msg.refundScriptPubKey,
              initHostedChannel = ourInit,
              blockDay = blockDay,
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

            // TODO check if channel exists and send state override instead
            CLN.sendCustomMessage(
              peerId,
              HC_INIT_HOSTED_CHANNEL_TAG,
              initHostedChannelCodec
                .encode(ourInit)
                .require
                .toByteVector
            )
          })

          Opening()
        }
        case _ => Inactive()
      })
  case class Opening()
      extends State({
        case Recv(msg: StateUpdate) => {

          Opening()
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
