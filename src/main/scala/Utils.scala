import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.scalanative.unsigned._
import scala.annotation.tailrec
import scodec.bits.{ByteVector, BitVector}
import scoin._
import scoin.ln._
import scoin.Crypto.{PublicKey, PrivateKey}

class PonchoException(s: String) extends java.lang.Exception {
  override def toString(): String = s
}

object Utils {
  def generateFeatureBits(indexes: Set[Int]): String = {
    var buf = BitVector.fill(indexes.max + 1)(high = false).bytes.bits
    indexes.foreach { i => buf = buf.set(i) }
    buf.reverse.bytes.toHex
  }

  case class OnionParseResult(
      packet: PaymentOnion.PaymentPacket,
      nextOnion: ByteVector,
      sharedSecret: ByteVector32
  )

  def parseClientOnion(
      privateKey: PrivateKey,
      htlc: UpdateAddHtlc
  ): Either[
    Exception | FailureMessage,
    OnionParseResult
  ] =
    Sphinx.peel(
      privateKey,
      Some(htlc.paymentHash),
      htlc.onionRoutingPacket
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
          case _ => Left(InvalidOnionPayload(UInt64(0), 0))
        }
      }
    }

  def getOutgoingData(
      privateKey: PrivateKey,
      htlc: UpdateAddHtlc
  ): Option[(ShortChannelId, MilliSatoshi, CltvExpiry, ByteVector)] =
    parseClientOnion(privateKey, htlc) match {
      case Right(
            OnionParseResult(
              payload: PaymentOnion.ChannelRelayTlvPayload,
              nextOnion: ByteVector,
              _
            )
          ) =>
        Some(
          (
            payload.outgoingChannelId,
            payload.amountToForward,
            payload.outgoingCltv,
            nextOnion
          )
        )
      case _ => None
    }
}
