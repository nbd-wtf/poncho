import scala.collection.mutable
import castor.Context.Simple.global

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Channel =
    (actors.get(peerId), Database.data.channels.get(peerId)) match {
      case (Some(actor), _) => actor
      case (None, Some(chandata)) => {
        val actor = new Channel(peerId, Some(chandata))
        actors += (peerId -> actor)
        CLN.log(s"creating actor $peerId: $actor")
        actor
      }
      case (None, None) => {
        val actor = new Channel(peerId, None)
        actors += (peerId -> actor)
        CLN.log(s"creating temporary actor $peerId: $actor")
        actor
      }
    }
}
