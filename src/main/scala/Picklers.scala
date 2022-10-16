import java.nio.file.{Path, Paths}
import scala.math.BigInt
import scala.util.Try
import scodec.bits.ByteVector
import io.circe.{Error => _, _}
import io.circe.generic.semiauto._
import scoin._
import scoin.ln._
import scoin.hc._

object Picklers {
  given Encoder[ByteVector] =
    Encoder.encodeString.contramap(_.toHex)
  given Decoder[ByteVector] =
    Decoder.decodeString.emapTry(s => Try(ByteVector.fromValidHex(s)))

  given Encoder[ByteVector32] =
    Encoder[ByteVector].contramap(_.bytes)
  given Decoder[ByteVector32] =
    Decoder[ByteVector].emapTry(s => Try(ByteVector32(s)))

  given KeyEncoder[ByteVector] =
    KeyEncoder.encodeKeyString.contramap(_.toHex)
  given KeyDecoder[ByteVector] =
    KeyDecoder.decodeKeyString.map(ByteVector.fromValidHex(_))

  given KeyEncoder[ByteVector32] =
    KeyEncoder[ByteVector].contramap(_.bytes)
  given KeyDecoder[ByteVector32] =
    KeyDecoder[ByteVector].map(s => ByteVector32(s))

  given Encoder[ByteVector64] =
    Encoder[ByteVector].contramap(_.bytes)
  given Decoder[ByteVector64] =
    Decoder[ByteVector].emapTry(s => Try(ByteVector64(s)))

  given Encoder[MilliSatoshi] =
    Encoder.encodeBigInt.contramap(msat => BigInt(msat.toLong))
  given Decoder[MilliSatoshi] =
    Decoder.decodeBigInt.emapTry(s => Try(MilliSatoshi(s.toLong)))

  given Encoder[Satoshi] =
    Encoder.encodeBigInt.contramap(sat => BigInt(sat.toLong))
  given Decoder[Satoshi] =
    Decoder.decodeBigInt.emapTry(s => Try(Satoshi(s.toLong)))

  given Encoder[ShortChannelId] =
    Encoder.encodeString.contramap(_.toString)
  given Decoder[ShortChannelId] =
    Decoder.decodeString.emapTry(s => Try(ShortChannelId(s)))

  given Encoder[CltvExpiry] =
    Encoder.encodeInt.contramap(_.toLong.toInt)
  given Decoder[CltvExpiry] =
    Decoder.decodeInt.emapTry(s => Try(CltvExpiry(BlockHeight(s.toLong))))

  given Encoder[CltvExpiryDelta] =
    Encoder.encodeInt.contramap(_.toInt)
  given Decoder[CltvExpiryDelta] =
    Decoder.decodeInt.emapTry(s => Try(CltvExpiryDelta(s)))

  given Encoder[OnionRoutingPacket] =
    Encoder.encodeString.contramap(orp =>
      PaymentOnionCodecs.paymentOnionPacketCodec
        .encode(orp)
        .toOption
        .get
        .toHex
    )
  given Decoder[OnionRoutingPacket] =
    Decoder.decodeString.emapTry(s =>
      Try(
        PaymentOnionCodecs.paymentOnionPacketCodec
          .decode(ByteVector.fromValidHex(s).toBitVector)
          .toOption
          .get
          .value
      )
    )

  given Encoder[Path] =
    Encoder.encodeString.contramap(_.toAbsolutePath.toString)
  given Decoder[Path] =
    Decoder.decodeString.emapTry(s => Try(Paths.get(s)))

  given Encoder[UpdateAddHtlc] = deriveEncoder
  given Decoder[UpdateAddHtlc] = deriveDecoder

  given Encoder[Error] = deriveEncoder
  given Decoder[Error] = deriveDecoder

  given Encoder[LastCrossSignedState] = deriveEncoder
  given Decoder[LastCrossSignedState] = new Decoder[LastCrossSignedState] {
    final def apply(c: HCursor): Decoder.Result[LastCrossSignedState] = for {
      isHost <- c.downField("isHost").as[Boolean]
      refundScriptPubKey <- c.downField("refundScriptPubKey").as[ByteVector]
      initHostedChannel <- c
        .downField("initHostedChannel")
        .as[InitHostedChannel]
      blockDay <- c.downField("blockDay").as[Long]
      localBalance <- c
        .downField("localBalance")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("localBalanceMsat")
            .as[MilliSatoshi]
        )
      remoteBalance <- c
        .downField("remoteBalance")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("remoteBalanceMsat")
            .as[MilliSatoshi]
        )
      localUpdates <- c.downField("localUpdates").as[Long]
      remoteUpdates <- c.downField("remoteUpdates").as[Long]
      incomingHtlcs <- c.downField("incomingHtlcs").as[List[UpdateAddHtlc]]
      outgoingHtlcs <- c.downField("outgoingHtlcs").as[List[UpdateAddHtlc]]
      remoteSigOfLocal <- c.downField("remoteSigOfLocal").as[ByteVector64]
      localSigOfRemote <- c.downField("localSigOfRemote").as[ByteVector64]
    } yield LastCrossSignedState(
      isHost = isHost,
      refundScriptPubKey = refundScriptPubKey,
      initHostedChannel = initHostedChannel,
      blockDay = blockDay,
      localBalance = localBalance,
      remoteBalance = remoteBalance,
      localUpdates = localUpdates,
      remoteUpdates = remoteUpdates,
      incomingHtlcs = incomingHtlcs,
      outgoingHtlcs = outgoingHtlcs,
      remoteSigOfLocal = remoteSigOfLocal,
      localSigOfRemote = localSigOfRemote
    )
  }

  given Encoder[InitHostedChannel] = deriveEncoder
  given Decoder[InitHostedChannel] = new Decoder[InitHostedChannel] {
    final def apply(c: HCursor): Decoder.Result[InitHostedChannel] = for {
      maxHtlcValueInFlight <- c
        .downField("maxHtlcValueInFlight")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("maxHtlcValueInFlightMsat")
            .as[MilliSatoshi]
        )
      htlcMinimum <- c
        .downField("htlcMinimum")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("htlcMinimumMsat")
            .as[MilliSatoshi]
        )
      maxAcceptedHtlcs <- c.downField("maxAcceptedHtlcs").as[Int]
      channelCapacity <- c
        .downField("channelCapacity")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("channelCapacityMsat")
            .as[MilliSatoshi]
        )
      initialClientBalance <- c
        .downField("initialClientBalance")
        .as[MilliSatoshi]
        .orElse(
          c
            .downField("initialClientBalanceMsat")
            .as[MilliSatoshi]
        )
      features <- c.downField("features").as[List[Int]]
    } yield InitHostedChannel(
      maxHtlcValueInFlight = maxHtlcValueInFlight,
      htlcMinimum = htlcMinimum,
      maxAcceptedHtlcs = maxAcceptedHtlcs,
      channelCapacity = channelCapacity,
      initialClientBalance = initialClientBalance,
      features = features
    )
  }

  type UpdateAddHtlcTlvStream = TlvStream[UpdateAddHtlcTlv] // hack
  given Encoder[UpdateAddHtlcTlvStream] =
    new Encoder {
      final def apply(a: UpdateAddHtlcTlvStream): Json = Json.arr()
    }
  given Decoder[UpdateAddHtlcTlvStream] =
    new Decoder {
      final def apply(c: HCursor): Decoder.Result[UpdateAddHtlcTlvStream] =
        Right(TlvStream.empty[UpdateAddHtlcTlv])
    }

  type ErrorTlvStream = TlvStream[ErrorTlv] // hack
  given Encoder[ErrorTlvStream] =
    new Encoder {
      final def apply(a: ErrorTlvStream): Json = Json.arr()
    }
  given Decoder[ErrorTlvStream] =
    new Decoder {
      final def apply(c: HCursor): Decoder.Result[ErrorTlvStream] =
        Right(TlvStream.empty[ErrorTlv])
    }

  given Encoder[HtlcIdentifier] = deriveEncoder
  given Decoder[HtlcIdentifier] = deriveDecoder

  given Encoder[ChannelData] = deriveEncoder
  given Decoder[ChannelData] = deriveDecoder

  given Encoder[DetailedError] = deriveEncoder
  given Decoder[DetailedError] = deriveDecoder

  given Encoder[Config] = deriveEncoder
  given Decoder[Config] = deriveDecoder
}
