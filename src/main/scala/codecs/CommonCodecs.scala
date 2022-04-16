package codecs

import scala.scalanative.unsigned.{ULong, UnsignedRichLong}
import math.Ordering.Implicits.infixOrderingOps
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

object CommonCodecs {
  def discriminatorWithDefault[A](
      discriminator: Codec[A],
      fallback: Codec[A]
  ): Codec[A] = new Codec[A] {
    def sizeBound: SizeBound = discriminator.sizeBound | fallback.sizeBound

    def encode(e: A): Attempt[BitVector] =
      discriminator.encode(e).recoverWith { case _ => fallback.encode(e) }

    def decode(b: BitVector): Attempt[DecodeResult[A]] =
      discriminator.decode(b).recoverWith {
        case _: KnownDiscriminatorType[_]#UnknownDiscriminator =>
          fallback.decode(b)
      }
  }

  val bool8: Codec[Boolean] = bool(8)
  val uint64: Codec[ULong] = long(8).xmap(_.toULong, _.toLong)
  val satoshi: Codec[Satoshi] = long(8).xmapc(l => Satoshi(l))(_.toLong)
  val millisatoshi: Codec[MilliSatoshi] =
    long(8).xmapc(l => MilliSatoshi(l))(_.toLong)
  val millisatoshi32: Codec[MilliSatoshi] =
    uint32.xmapc(l => MilliSatoshi(l))(_.toLong)
  val timestampSecond: Codec[TimestampSecond] =
    uint32.xmapc(TimestampSecond(_))(_.toLong)

  def minimalvalue[A: Ordering](codec: Codec[A], min: A): Codec[A] =
    codec.exmap(
      {
        case i if i < min =>
          Attempt.failure(Err("value was not minimally encoded"))
        case i => Attempt.successful(i)
      },
      Attempt.successful
    )

  val varint: Codec[ULong] = discriminatorWithDefault(
    discriminated[ULong]
      .by(uint8L)
      .subcaseP(0xff) { case i if i >= 0x100000000L.toULong => i }(
        minimalvalue(uint64, 0x100000000L.toULong)
      )
      .subcaseP(0xfe) { case i if i >= 0x10000.toULong => i }(
        minimalvalue(
          uint32.xmap(_.toLong.toULong, _.toLong.toInt),
          0x10000.toLong.toULong
        )
      )
      .subcaseP(0xfd) { case i if i >= 0xfd.toULong => i }(
        minimalvalue(
          uint16.xmap(_.toLong.toULong, _.toLong.toInt),
          0xfd.toLong.toULong
        )
      ),
    uint8L.xmap(_.toLong.toULong, _.toLong.toInt)
  )

  val varintoverflow: Codec[Long] = varint.narrow(
    l =>
      if (l <= Long.MaxValue.toULong) Attempt.successful(l.toLong)
      else Attempt.failure(Err(s"overflow for value $l")),
    l => l.toULong
  )

  val bytes32: Codec[ByteVector32] = limitedSizeBytes(
    32,
    bytesStrict(32).xmap(d => ByteVector32(d), d => d.bytes)
  )

  val bytes64: Codec[ByteVector64] = limitedSizeBytes(
    64,
    bytesStrict(64).xmap(d => ByteVector64(d), d => d.bytes)
  )

  val sha256: Codec[ByteVector32] = bytes32

  val varsizebinarydata: Codec[ByteVector] = variableSizeBytes(uint16, bytes)

  val listofsignatures: Codec[List[ByteVector64]] = listOfN(uint16, bytes64)

  val shortchannelid: Codec[ShortChannelId] =
    int64.xmap(l => ShortChannelId(l), s => s.toLong)

  val rgb: Codec[Color] = bytes(3).xmap(
    buf => Color(buf(0), buf(1), buf(2)),
    t => ByteVector(t.r, t.g, t.b)
  )

  def zeropaddedstring(size: Int): Codec[String] =
    fixedSizeBytes(size, utf8).xmap(s => s.takeWhile(_ != '\u0000'), s => s)

  /** All LN protocol message must be stored as length-delimited, because they
    * may have arbitrary trailing data
    */
  def lengthDelimited[T](codec: Codec[T]): Codec[T] =
    variableSizeBytesLong(varintoverflow, codec)

  val blockHeight: Codec[BlockHeight] =
    uint32.xmapc(l => BlockHeight(l))(_.toLong)

  val cltvExpiry: Codec[CltvExpiry] = blockHeight.as[CltvExpiry]

  val cltvExpiryDelta: Codec[CltvExpiryDelta] =
    uint16.xmapc(CltvExpiryDelta.apply)(_.toInt)
}
