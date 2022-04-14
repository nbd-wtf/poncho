import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import com.softwaremill.quicklens
import castor.Context
import upickle.default._

case class ChannelData(peerId: String, shortChannelId: String)
object ChannelData { implicit val rw: ReadWriter[ChannelData] = macroRW }

sealed trait Msg
case class Connect() extends Msg
case class ReceivedHTLC() extends Msg
case class SendHTLC() extends Msg
case class ReceivedPreimage() extends Msg
case class SendPreimage() extends Msg

class Channel(data: ChannelData)(implicit ac: castor.Context)
    extends castor.StateMachineActor[Msg] {

  def initialState = Offline()

  case class Offline()
      extends State({ case Connect() =>
        Online()
      })
  case class Online()
      extends State({
        case ReceivedHTLC()     => Online()
        case SendHTLC()         => Online()
        case ReceivedPreimage() => Online()
        case SendPreimage()     => Online()
      })
}
