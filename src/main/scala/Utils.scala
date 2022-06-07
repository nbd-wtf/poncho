import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.scalanative.unsigned._
import scala.annotation.tailrec
import scodec.bits.ByteVector

import crypto.Crypto
import codecs._

class PonchoException(s: String) extends java.lang.Exception {
  override def toString(): String = s
}

object Utils {
  @tailrec
  final def isLessThan(a: ByteVector, b: ByteVector): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else (a.head & 0xff) < (b.head & 0xff)
  }

  def getShortChannelId(peer1: ByteVector, peer2: ByteVector): ShortChannelId =
    val stream = new ByteArrayInputStream(
      pubkeysCombined(peer1, peer2).toArray
    )
    def getChunk(): Long = Protocol.uint64(
      stream,
      ByteOrder.BIG_ENDIAN
    )
    ShortChannelId(
      List
        .fill(8)(getChunk())
        .sum
    )

  def getChannelId(peer1: ByteVector, peer2: ByteVector): ByteVector32 =
    Crypto.sha256(pubkeysCombined(peer1, peer2))

  def pubkeysCombined(
      pubkey1: ByteVector,
      pubkey2: ByteVector
  ): ByteVector =
    if (Utils.isLessThan(pubkey1, pubkey2)) pubkey1 ++ pubkey2
    else pubkey2 ++ pubkey1

  case class OnionParseResult(
      packet: PaymentOnion.PaymentPacket,
      nextOnion: ByteVector,
      sharedSecret: ByteVector32
  )

  def parseClientOnion(add: UpdateAddHtlc): Either[
    Exception | FailureMessage,
    OnionParseResult
  ] =
    PaymentOnionCodecs.paymentOnionPacketCodec
      .decode(add.onionRoutingPacket.toBitVector)
      .toEither
      .map(_.value) match {
      case Left(err) =>
        // return something here that indicates we must fail this channel
        Left(Exception("unparseable onion"))
      case Right(onion) =>
        Sphinx.peel(
          Main.node.getPrivateKey(),
          Some(add.paymentHash),
          onion
        ) match {
          case Left(badOnion) => Left(badOnion)
          case Right(
                dp @ Sphinx.DecryptedPacket(payload, nextPacket, sharedSecret)
              ) => {
            val decodedOurOnion = PaymentOnionCodecs
              .paymentOnionPerHopPayloadCodec(dp.isLastPacket)
              .decode(payload.bits)
              .toEither
              .map(_.value)
            val encodedNextOnion = PaymentOnionCodecs.paymentOnionPacketCodec
              .encode(nextPacket)
              .toEither
              .map(_.toByteVector)

            (decodedOurOnion, encodedNextOnion) match {
              case (Right(packet), Right(nextOnion)) =>
                Right(OnionParseResult(packet, nextOnion, sharedSecret))
              case (Left(e: OnionRoutingCodecs.MissingRequiredTlv), _) =>
                Left(e.failureMessage)
              case _ => Left(InvalidOnionPayload(0.toULong, 0))
            }
          }
        }
    }
}
