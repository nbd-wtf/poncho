import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import com.softwaremill.quicklens
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs.*
import codecs.{HostedClientMessage}

case class ChannelData(
    peerId: String,
    channelId: ByteVector32,
    shortChannelId: String,
    isActive: Boolean
)
object ChannelData { implicit val rw: ReadWriter[ChannelData] = macroRW }

sealed trait Msg
case class Send(msg: HostedServerMessage) extends Msg
case class Recv(msg: HostedClientMessage) extends Msg

class Channel(data: ChannelData)(implicit ac: castor.Context)
    extends castor.StateMachineActor[Msg] {

  def initialState = if (data.isActive) Active() else Inactive()

  case class Inactive()
      extends State({
        case Recv(msg: InvokeHostedChannel) => {
          Inactive()
        }
        case _ => Inactive()
      })
  case class Active()
      extends State({
        case Send(msg: UpdateAddHtlc)           => Active()
        case Recv(msg: AskBrandingInfo)         => Active()
        case Recv(msg: ResizeChannel)           => Active()
        case Recv(msg: UpdateAddHtlc)           => Active()
        case Recv(msg: UpdateFailHtlc)          => Active()
        case Recv(msg: UpdateFulfillHtlc)       => Active()
        case Recv(msg: UpdateFailMalformedHtlc) => Active()
        case _                                  => Active()
      })
}
