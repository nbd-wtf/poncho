package codecs

import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

import codecs._
import codecs.CommonCodecs._

object TlvCodecs {
  // high range types are greater than or equal 2^16, see https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#type-length-value-format
  private val TLV_TYPE_HIGH_RANGE = 65536L.toULong

  /** Truncated uint64 (0 to 8 bytes unsigned integer). The encoder
    * minimally-encodes every value, and the decoder verifies that values are
    * minimally-encoded. Note that this codec can only be used at the very end
    * of a TLV record.
    */
  val tu64: Codec[ULong] = Codec(
    (u: ULong) => {
      val b = (u: @unchecked) match {
        case u if u < 0x01L.toULong => ByteVector.empty
        case u if u < 0x0100L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(1)
        case u if u < 0x010000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(2)
        case u if u < 0x01000000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(3)
        case u if u < 0x0100000000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(4)
        case u if u < 0x010000000000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(5)
        case u if u < 0x01000000000000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(6)
        case u if u < 0x0100000000000000L.toULong =>
          ByteVector.fromLong(u.toLong).takeRight(7)
        case u if u <= ULong.MaxValue =>
          ByteVector.fromLong(u.toLong).takeRight(8)
      }
      Attempt.successful(b.bits)
    },
    b =>
      b.length match {
        case l if l <= 0 =>
          minimalvalue(uint64, 0x00L.toULong).decode(b.padLeft(64))
        case l if l <= 8 =>
          minimalvalue(uint64, 0x01L.toULong).decode(b.padLeft(64))
        case l if l <= 16 =>
          minimalvalue(uint64, 0x0100L.toULong).decode(b.padLeft(64))
        case l if l <= 24 =>
          minimalvalue(uint64, 0x010000L.toULong).decode(b.padLeft(64))
        case l if l <= 32 =>
          minimalvalue(uint64, 0x01000000.toULong).decode(b.padLeft(64))
        case l if l <= 40 =>
          minimalvalue(uint64, 0x0100000000L.toULong).decode(b.padLeft(64))
        case l if l <= 48 =>
          minimalvalue(uint64, 0x010000000000L.toULong).decode(b.padLeft(64))
        case l if l <= 56 =>
          minimalvalue(uint64, 0x01000000000000L.toULong).decode(b.padLeft(64))
        case l if l <= 64 =>
          minimalvalue(uint64, 0x0100000000000000L.toULong).decode(
            b.padLeft(64)
          )
        case _ =>
          Attempt.failure(
            Err(s"too many bytes to decode for truncated uint64 (${b.toHex})")
          )
      }
  )

  val tu64overflow: Codec[Long] = tu64.exmap(
    u =>
      if (u <= ULong.MaxValue) Attempt.Successful(u.toLong)
      else Attempt.Failure(Err(s"overflow for value $u")),
    l =>
      if (l >= 0) Attempt.Successful(l.toULong)
      else Attempt.Failure(Err(s"uint64 must be positive (actual=$l)"))
  )

  val tmillisatoshi: Codec[MilliSatoshi] =
    tu64overflow.xmap(l => MilliSatoshi(l), m => m.toLong)

  /** Truncated uint32 (0 to 4 bytes unsigned integer). */
  val tu32: Codec[Long] = tu64.exmap(
    {
      case i if i > 0xffffffffL.toULong => Attempt.Failure(Err("tu32 overflow"))
      case i                            => Attempt.Successful(i.toLong)
    },
    l => Attempt.Successful(l.toULong)
  )

  /** Truncated uint16 (0 to 2 bytes unsigned integer). */
  val tu16: Codec[Int] = tu32.exmap(
    {
      case i if i > 0xffff => Attempt.Failure(Err("tu16 overflow"))
      case i               => Attempt.Successful(i.toInt)
    },
    l => Attempt.Successful(l)
  )

  val ltu64: Codec[ULong] = variableSizeBytes(uint8, tu64)

  /** Length-prefixed truncated long (1 to 9 bytes unsigned integer). */
  val ltu64overflow: Codec[Long] = variableSizeBytes(uint8, tu64overflow)

  /** Length-prefixed truncated millisatoshi (1 to 9 bytes unsigned). */
  val ltmillisatoshi: Codec[MilliSatoshi] =
    variableSizeBytes(uint8, tmillisatoshi)

  /** Length-prefixed truncated uint32 (1 to 5 bytes unsigned integer). */
  val ltu32: Codec[Long] = variableSizeBytes(uint8, tu32)

  /** Length-prefixed truncated uint16 (1 to 3 bytes unsigned integer). */
  val ltu16: Codec[Int] = variableSizeBytes(uint8, tu16)

  private def validateGenericTlv(g: GenericTlv): Attempt[GenericTlv] = {
    if (g.tag < TLV_TYPE_HIGH_RANGE && g.tag.toLong % 2 == 0) {
      Attempt.Failure(Err("unknown even tlv type"))
    } else {
      Attempt.Successful(g)
    }
  }

  val genericTlv: Codec[GenericTlv] =
    (("tag" | varint) :: variableSizeBytesLong(varintoverflow, bytes))
      .as[GenericTlv]
      .exmap(validateGenericTlv, validateGenericTlv)

  private def tag[T <: Tlv](
      codec: DiscriminatorCodec[T, ULong],
      record: Either[GenericTlv, T]
  ): ULong = record match {
    case Left(generic) => generic.tag
    case Right(tlv)    => tag(codec, tlv)
  }

  private def tag[T <: Tlv](
      codec: DiscriminatorCodec[T, ULong],
      record: T
  ): ULong =
    codec.encode(record).flatMap(bits => varint.decode(bits)).require.value

  private def validateStream[T <: Tlv](
      codec: DiscriminatorCodec[T, ULong],
      records: List[Either[GenericTlv, T]]
  ): Attempt[TlvStream[T]] = {
    val tags = records.map(r => tag(codec, r))
    if (tags.length != tags.distinct.length) {
      Attempt.Failure(Err("tlv streams must not contain duplicate records"))
    } else if (tags != tags.sorted) {
      Attempt.Failure(
        Err("tlv records must be ordered by monotonically-increasing types")
      )
    } else {
      Attempt.Successful(
        TlvStream(
          records.collect { case Right(tlv) => tlv },
          records.collect { case Left(generic) => generic }
        )
      )
    }
  }

  /** A tlv stream codec relies on an underlying tlv codec. This allows tlv
    * streams to have different namespaces, increasing the total number of tlv
    * types available.
    *
    * @param codec
    *   codec used for the tlv records contained in the stream.
    * @tparam T
    *   stream namespace.
    */
  def tlvStream[T <: Tlv](
      codec: DiscriminatorCodec[T, ULong]
  ): Codec[TlvStream[T]] = list(discriminatorFallback(genericTlv, codec)).exmap(
    records => validateStream(codec, records),
    (stream: TlvStream[T]) => {
      val records =
        (stream.records.map(Right(_)) ++ stream.unknown.map(Left(_))).toList
      val tags = records.map(r => tag(codec, r))
      if (tags.length != tags.distinct.length) {
        Attempt.Failure(Err("tlv streams must not contain duplicate records"))
      } else {
        Attempt.Successful(tags.zip(records).sortBy(_._1).map(_._2))
      }
    }
  )

  /** When used inside a message, most of the time a tlv stream needs to specify
    * its length. Note that some messages will have an independent length field
    * and won't need this codec.
    *
    * @param codec
    *   codec used for the tlv records contained in the stream.
    * @tparam T
    *   stream namespace.
    */
  def lengthPrefixedTlvStream[T <: Tlv](
      codec: DiscriminatorCodec[T, ULong]
  ): Codec[TlvStream[T]] =
    variableSizeBytesLong(varintoverflow, tlvStream(codec))
}
