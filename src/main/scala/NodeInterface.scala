import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.Future
import scodec.bits.ByteVector
import scodec.codecs.uint16
import scoin._

import codecs.HostedChannelCodecs._
import codecs._

trait NodeInterface {
  def privateKey: ByteVector32
  def publicKey: ByteVector

  def inspectOutgoingPayment(
      identifier: HtlcIdentifier,
      paymentHash: ByteVector32
  ): Future[PaymentStatus]

  def sendCustomMessage(
      peerId: ByteVector,
      message: HostedServerMessage | HostedClientMessage
  ): Future[ujson.Value]

  def sendOnion(
      chan: Channel,
      htlcId: ULong,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Unit

  def getAddress(): Future[String]
  def getCurrentBlock(): Future[BlockHeight]
  def getBlockByHeight(height: BlockHeight): Future[Block]
  def getChainHash(): Future[ByteVector32]

  def main(onInit: () => Unit): Unit
}
