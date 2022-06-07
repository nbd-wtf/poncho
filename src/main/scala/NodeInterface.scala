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
  def ourPubKey: ByteVector

  def getPeerFromChannel(scid: ShortChannelId): ByteVector
  def inspectOutgoingPayment(
      peerId: String,
      htlc: UpdateAddHtlc
  ): Future[PaymentStatus]
  def sendCustomMessage(
      peerId: String,
      tag: Int,
      message: ByteVector
  ): Unit
  def sendOnion(
      paymentHash: ByteVector32,
      firstHop: ByteVector,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Future[ujson.Value]
  def getCurrentBlock(): Future[BlockHeight]
  def getChainHash(): Future[ByteVector32]

  def main(): Unit
}
