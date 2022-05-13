import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.Future

import scodec.bits.ByteVector
import scodec.codecs.uint16
import codecs.HostedChannelCodecs._
import codecs._

trait NodeInterface {
  def getPrivateKey(): ByteVector32
  lazy val ourPubKey: ByteVector

  def sendCustomMessage(
      peerId: String,
      tag: Int,
      message: ByteVector
  ): Unit
  def getCurrentBlockDay(): Future[Long]
  def getChainHash(): Future[ByteVector32]

  def main(): Unit
}
