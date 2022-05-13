import scala.util.{Failure, Success}
import scala.collection.mutable
import scala.concurrent.duration
import scala.scalanative.loop.Timer
import castor.Context.Simple.global
import scala.concurrent.duration.FiniteDuration

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Channel = new Channel(peerId)

  var currentBlockDay = 0L
  Timer.repeat(FiniteDuration(1, "hour")) { () =>
    {
      CLN
        .rpc("getchaininfo", ujson.Obj())
        .map(_("headercount").num.toLong / 144)
        .onComplete {
          case Success(blockday) => {
            currentBlockDay = blockday
          }
          case Failure(err) => CLN.log(s"failed to get current blockday: $err")
        }
    }
  }
}
