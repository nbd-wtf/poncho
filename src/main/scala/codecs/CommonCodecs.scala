package codecs

import java.net.{Inet4Address, Inet6Address, InetAddress}
import scala.util.Try
import scala.scalanative.unsigned.{ULong, UnsignedRichLong}
import math.Ordering.Implicits.infixOrderingOps
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

import crypto.Hmac256

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
  val uint64: Codec[ULong] = int64.xmap(_.toULong, _.toLong)
  val uint64overflow: Codec[Long] = int64.narrow(
    l =>
      if (l >= 0) Attempt.Successful(l)
      else Attempt.failure(Err(s"overflow for value $l")),
    l => l
  )
  val satoshi: Codec[Satoshi] = uint64overflow.xmapc(l => Satoshi(l))(_.toLong)
  val millisatoshi: Codec[MilliSatoshi] =
    uint64overflow.xmapc(l => MilliSatoshi(l))(_.toLong)
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

    /** When encoding, prepend a valid mac to the output of the given codec.
      * When decoding, verify that a valid mac is prepended.
      */
  def prependmac[A](codec: Codec[A], mac: Hmac256): Codec[A] = Codec[A](
    (a: A) =>
      codec.encode(a).map(bits => mac.mac(bits.toByteVector).bits ++ bits),
    (bits: BitVector) =>
      ("mac" | bytes32).decode(bits) match {
        case Attempt.Successful(DecodeResult(msgMac, remainder))
            if mac.verify(msgMac, remainder.toByteVector) =>
          codec.decode(remainder)
        case Attempt.Successful(_) => Attempt.Failure(scodec.Err("invalid mac"))
        case Attempt.Failure(err)  => Attempt.Failure(err)
      }
  )

  case class Color(r: Byte, g: Byte, b: Byte) {
    override def toString: String =
      f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
  }

  sealed trait NodeAddress {
    def host: String;
    def port: Int;
    override def toString: String = s"$host:$port"
  }
  sealed trait OnionAddress extends NodeAddress
  sealed trait IPAddress extends NodeAddress

  object NodeAddress {

    /** Creates a NodeAddress from a host and port.
      *
      * Note that non-onion hosts will be resolved.
      *
      * We don't attempt to resolve onion addresses (it will be done by the tor
      * proxy), so we just recognize them based on the .onion TLD and rely on
      * their length to separate v2/v3.
      */
    def fromParts(host: String, port: Int): Try[NodeAddress] = Try {
      host match {
        case _ if host.endsWith(".onion") && host.length == 22 =>
          Tor2(host.dropRight(6), port)
        case _ if host.endsWith(".onion") && host.length == 62 =>
          Tor3(host.dropRight(6), port)
        case _ => IPAddress(InetAddress.getByName(host), port)
      }
    }

    private def isPrivate(address: InetAddress): Boolean =
      address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress

    def isPublicIPAddress(address: NodeAddress): Boolean = {
      address match {
        case IPv4(ipv4, _) if !isPrivate(ipv4) => true
        case IPv6(ipv6, _) if !isPrivate(ipv6) => true
        case _                                 => false
      }
    }
  }

  object IPAddress {
    def apply(inetAddress: InetAddress, port: Int): IPAddress =
      inetAddress match {
        case address: Inet4Address => IPv4(address, port)
        case address: Inet6Address => IPv6(address, port)
      }
  }

  case class IPv4(ipv4: Inet4Address, port: Int) extends IPAddress {
    override def host: String = s"${ipv4.getHostAddress()}:${port}"
  }
  case class IPv6(ipv6: Inet6Address, port: Int) extends IPAddress {
    override def host: String = s"${ipv6.getHostAddress()}:${port}"
  }
  case class Tor2(tor2: String, port: Int) extends OnionAddress {
    override def host: String = tor2 + ".onion"
  }
  case class Tor3(tor3: String, port: Int) extends OnionAddress {
    override def host: String = tor3 + ".onion"
  }
}
