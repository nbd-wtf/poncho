import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import castor.Context.Simple.global

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Channel = {
    actors.getOrElseUpdate(peerId, { new Channel(peerId) })
  }
}
