package codecs

import scala.reflect.ClassTag
import scala.scalanative.unsigned.ULong
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec

import codecs.CommonCodecs._
import codecs.TlvCodecs._

trait Tlv

/** Generic tlv type we fallback to if we don't understand the incoming tlv.
  *
  * @param tag
  *   tlv tag.
  * @param value
  *   tlv value (length is implicit, and encoded as a varint).
  */
case class GenericTlv(tag: ULong, value: ByteVector) extends Tlv

/** A tlv stream is a collection of tlv records. A tlv stream is constrained to
  * a specific tlv namespace that dictates how to parse the tlv records. That
  * namespace is provided by a trait extending the top-level tlv trait.
  *
  * @param records
  *   known tlv records.
  * @param unknown
  *   unknown tlv records.
  * @tparam T
  *   the stream namespace is a trait extending the top-level tlv trait.
  */
case class TlvStream[T <: Tlv](
    records: Iterable[T],
    unknown: Iterable[GenericTlv] = Nil
) {

  /** @tparam R
    *   input type parameter, must be a subtype of the main TLV type
    * @return
    *   the TLV record of type that matches the input type parameter if any
    *   (there can be at most one, since BOLTs specify that TLV records are
    *   supposed to be unique)
    */
  def get[R <: T: ClassTag]: Option[R] = records.collectFirst { case r: R => r }
}

object TlvStream {
  def empty[T <: Tlv]: TlvStream[T] = TlvStream[T](Nil, Nil)
  def apply[T <: Tlv](records: T*): TlvStream[T] = TlvStream(records, Nil)
}

sealed trait ChannelAnnouncementTlv extends Tlv

object ChannelAnnouncementTlv {
  val channelAnnouncementTlvCodec: Codec[TlvStream[ChannelAnnouncementTlv]] =
    tlvStream(discriminated[ChannelAnnouncementTlv].by(varint))
}

sealed trait ChannelUpdateTlv extends Tlv
object ChannelUpdateTlv {
  val channelUpdateTlvCodec: Codec[TlvStream[ChannelUpdateTlv]] = tlvStream(
    discriminated[ChannelUpdateTlv].by(varint)
  )
}

sealed trait UpdateAddHtlcTlv extends Tlv

object UpdateAddHtlcTlv {
  val addHtlcTlvCodec: Codec[TlvStream[UpdateAddHtlcTlv]] = tlvStream(
    discriminated[UpdateAddHtlcTlv].by(varint)
  )
}

sealed trait UpdateFulfillHtlcTlv extends Tlv

object UpdateFulfillHtlcTlv {
  val updateFulfillHtlcTlvCodec: Codec[TlvStream[UpdateFulfillHtlcTlv]] =
    tlvStream(discriminated[UpdateFulfillHtlcTlv].by(varint))
}

sealed trait UpdateFailHtlcTlv extends Tlv

object UpdateFailHtlcTlv {
  val updateFailHtlcTlvCodec: Codec[TlvStream[UpdateFailHtlcTlv]] = tlvStream(
    discriminated[UpdateFailHtlcTlv].by(varint)
  )
}

sealed trait UpdateFailMalformedHtlcTlv extends Tlv

object UpdateFailMalformedHtlcTlv {
  val updateFailMalformedHtlcTlvCodec
      : Codec[TlvStream[UpdateFailMalformedHtlcTlv]] = tlvStream(
    discriminated[UpdateFailMalformedHtlcTlv].by(varint)
  )
}
