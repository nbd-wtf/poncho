import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import codecs._

import crypto.Crypto

class MockingNode(seed: Integer) extends NodeInterface {
  def getPrivateKey(): ByteVector32 =
    Crypto.sha256(ByteVector(Array.fill(seed)(5.toByte)))
  def publicKey: ByteVector = Crypto.getPublicKey(getPrivateKey())

  val mockOutgoingPayments =
    scala.collection.mutable.Map.empty[HtlcIdentifier, PaymentStatus]
  def inspectOutgoingPayment(
      identifier: HtlcIdentifier,
      paymentHash: ByteVector32
  ): Future[PaymentStatus] =
    Future(mockOutgoingPayments.get(identifier).getOrElse(None))

  var mockCustomMessageSent =
    List.empty[HostedServerMessage | HostedClientMessage]
  def sendCustomMessage(
      peerId: ByteVector,
      message: HostedServerMessage | HostedClientMessage
  ): Future[ujson.Value] = {
    mockCustomMessageSent = message :: mockCustomMessageSent
    Future(ujson.Str("success"))
  }

  var mockOnionSent = List.empty[(ByteVector32, MilliSatoshi)]
  def sendOnion(
      chan: Channel,
      htlcId: ULong,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiryDelta: CltvExpiryDelta,
      onion: ByteVector
  ): Unit = {
    mockOnionSent = (paymentHash, amount) :: mockOnionSent
  }

  var mockCurrentBlock = BlockHeight(0)
  def getCurrentBlock(): Future[BlockHeight] = Future(mockCurrentBlock)

  def getChainHash(): Future[ByteVector32] = Future(
    ByteVector32(
      ByteVector.fromValidHex(
        "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
      )
    )
  )

  def getAddress(): Future[String] = Future(
    "bc1qza8062nxd2wf25l7sr7854kjrahtsj50ypquwm"
  )

  def main(onInit: () => Unit): Unit = {
    onInit()
  }
}
