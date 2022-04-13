package codecs

import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

object CommonCodecs {
  // byte-aligned boolean codec
  val bool8: Codec[Boolean] = bool(8)
}
