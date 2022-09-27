import java.nio.file.{Path, Paths}
import scala.scalanative.unsigned._
import upickle.default._
import scodec.bits.ByteVector
import scoin._
import scoin.ln._
import scoin.hc._

object Picklers {
  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))
  given ReadWriter[ByteVector32] =
    readwriter[String]
      .bimap[ByteVector32](_.toHex, ByteVector32.fromValidHex(_))
  given ReadWriter[ByteVector64] =
    readwriter[String]
      .bimap[ByteVector64](_.toHex, ByteVector64.fromValidHex(_))
  given ReadWriter[MilliSatoshi] =
    readwriter[Long].bimap[MilliSatoshi](_.toLong, MilliSatoshi(_))
  given ReadWriter[Satoshi] =
    readwriter[Long].bimap[Satoshi](_.toLong, Satoshi(_))
  given ReadWriter[ShortChannelId] =
    readwriter[String].bimap[ShortChannelId](_.toString, ShortChannelId(_))
  given ReadWriter[CltvExpiry] =
    readwriter[Long].bimap[CltvExpiry](_.toLong, CltvExpiry(_))
  given ReadWriter[CltvExpiryDelta] =
    readwriter[Int].bimap[CltvExpiryDelta](_.toInt, CltvExpiryDelta(_))
  given ReadWriter[UInt64] =
    readwriter[Long].bimap[UInt64](_.toLong, UInt64(_))
  given ReadWriter[OnionRoutingPacket] =
    readwriter[String].bimap[OnionRoutingPacket](
      orp =>
        PaymentOnionCodecs.paymentOnionPacketCodec
          .encode(orp)
          .toOption
          .get
          .toHex,
      hex =>
        PaymentOnionCodecs.paymentOnionPacketCodec
          .decode(ByteVector.fromValidHex(hex).toBitVector)
          .toOption
          .get
          .value
    )

  given ReadWriter[Path] =
    readwriter[String]
      .bimap[Path](
        _.toAbsolutePath.toString,
        Paths.get(_)
      )

  given ReadWriter[LastCrossSignedState] = macroRW
  given ReadWriter[InitHostedChannel] = macroRW
  given ReadWriter[UpdateAddHtlc] = macroRW
  given ReadWriter[Error] = macroRW

  type UpdateAddHtlcTlvStream = TlvStream[UpdateAddHtlcTlv]
  given ReadWriter[UpdateAddHtlcTlvStream] =
    readwriter[List[Int]]
      .bimap[TlvStream[UpdateAddHtlcTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )
  type ErrorTlvStream = TlvStream[ErrorTlv] // hack
  given ReadWriter[ErrorTlvStream] =
    readwriter[List[Int]]
      .bimap[TlvStream[ErrorTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )

  given ReadWriter[Data] = macroRW
  given ReadWriter[HtlcIdentifier] = macroRW
  given ReadWriter[ChannelData] = macroRW
  given ReadWriter[DetailedError] = macroRW

  given ReadWriter[Config] = macroRW
}
