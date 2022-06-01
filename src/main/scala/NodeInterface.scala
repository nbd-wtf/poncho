import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.Future

import scodec.bits.ByteVector
import scodec.codecs.uint16

import codecs.HostedChannelCodecs._
import codecs._
import crypto.{PublicKey, PrivateKey}

trait NodeInterface {
  def getPrivateKey(): PrivateKey
  def ourPubKey: PublicKey

  def getPeerFromChannel(scid: ShortChannelId): ByteVector
  def sendCustomMessage(
      peerId: String,
      tag: Int,
      message: ByteVector
  ): Unit
  def sendOnion(
      paymentHash: ByteVector32,
      firstHop: PublicKey,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Future[ujson.Value]
  def getCurrentBlockDay(): Future[Long]
  def getChainHash(): Future[ByteVector32]

  def main(): Unit
}
