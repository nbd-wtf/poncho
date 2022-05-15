import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import castor.Context.Simple.global
import scodec.bits.ByteVector

import codecs.{ShortChannelId, ByteVector32}

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Channel = {
    actors.getOrElseUpdate(peerId, { new Channel(peerId) })
  }

  def getChannelId(peerId: String): ByteVector32 =
    Utils.getChannelId(Main.node.ourPubKey, ByteVector.fromValidHex(peerId))

  def getShortChannelId(peerId: String): ShortChannelId =
    Utils.getShortChannelId(
      Main.node.ourPubKey,
      ByteVector.fromValidHex(peerId)
    )
}
