import scala.collection.mutable
import castor.Context.Simple.global

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Option[Channel] =
    Database.data.channels
      .get(peerId)
      .map(chanData =>
        actors.get(peerId) match {
          case Some(chan) => chan
          case None => {
            val actor = new Channel(chanData)
            actors += (peerId -> actor)
            actor
          }
        }
      )
}
