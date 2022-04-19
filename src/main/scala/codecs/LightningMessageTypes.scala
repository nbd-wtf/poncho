package codecs

import java.net.{Inet4Address, Inet6Address, InetAddress}
import java.nio.charset.StandardCharsets
import scala.scalanative.unsigned._
import scala.util.Try
import scodec.codecs._
import scodec.Codec
import scodec.bits.ByteVector

import codecs.CommonCodecs._
import codecs.TlvCodecs._

sealed trait LightningMessage extends Serializable
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait AnnouncementMessage extends RoutingMessage // <- not in the spec
sealed trait HasTimestamp extends LightningMessage {
  def timestamp: TimestampSecond
}
sealed trait HasTemporaryChannelId extends LightningMessage {
  def temporaryChannelId: ByteVector32
} // <- not in the spec
sealed trait HasChannelId extends LightningMessage {
  def channelId: ByteVector32
} // <- not in the spec
sealed trait HasChainHash extends LightningMessage {
  def chainHash: ByteVector32
} // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
sealed trait HtlcSettlementMessage extends UpdateMessage {
  def id: ULong
} // <- not in the spec

case class Error(
    channelId: ByteVector32,
    data: ByteVector,
    tlvStream: TlvStream[ErrorTlv] = TlvStream.empty
) extends SetupMessage
    with HasChannelId {
  def toAscii: String = if (data.toArray.forall(ch => ch >= 32 && ch < 127))
    new String(data.toArray, StandardCharsets.US_ASCII)
  else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error =
    Error(channelId, ByteVector.view(msg.getBytes(StandardCharsets.US_ASCII)))
}

sealed trait ErrorTlv extends Tlv

object ErrorTlv {
  val errorTlvCodec: Codec[TlvStream[ErrorTlv]] = tlvStream(
    discriminated[ErrorTlv].by(varint)
  )
}

case class UpdateAddHtlc(
    channelId: ByteVector32,
    id: ULong,
    amountMsat: MilliSatoshi,
    paymentHash: ByteVector32,
    cltvExpiry: CltvExpiry,
    onionRoutingPacket: ByteVector,
    tlvStream: TlvStream[UpdateAddHtlcTlv] = TlvStream.empty
) extends HtlcMessage
    with UpdateMessage
    with HasChannelId

case class UpdateFulfillHtlc(
    channelId: ByteVector32,
    id: ULong,
    paymentPreimage: ByteVector32,
    tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty
) extends HtlcMessage
    with UpdateMessage
    with HasChannelId
    with HtlcSettlementMessage

case class UpdateFailHtlc(
    channelId: ByteVector32,
    id: ULong,
    reason: ByteVector,
    tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty
) extends HtlcMessage
    with UpdateMessage
    with HasChannelId
    with HtlcSettlementMessage

case class UpdateFailMalformedHtlc(
    channelId: ByteVector32,
    id: ULong,
    onionHash: ByteVector32,
    failureCode: Int,
    tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty
) extends HtlcMessage
    with UpdateMessage
    with HasChannelId
    with HtlcSettlementMessage

case class CommitSig(
    channelId: ByteVector32,
    signature: ByteVector64,
    htlcSignatures: List[ByteVector64],
    tlvStream: TlvStream[CommitSigTlv] = TlvStream.empty
) extends HtlcMessage
    with HasChannelId

case class RevokeAndAck(
    channelId: ByteVector32,
    perCommitmentSecret: ByteVector32,
    nextPerCommitmentPoint: ByteVector,
    tlvStream: TlvStream[RevokeAndAckTlv] = TlvStream.empty
) extends HtlcMessage
    with HasChannelId

case class ChannelAnnouncement(
    nodeSignature1: ByteVector64,
    nodeSignature2: ByteVector64,
    bitcoinSignature1: ByteVector64,
    bitcoinSignature2: ByteVector64,
    features: Features[Feature],
    chainHash: ByteVector32,
    shortChannelId: ShortChannelId,
    nodeId1: ByteVector,
    nodeId2: ByteVector,
    bitcoinKey1: ByteVector,
    bitcoinKey2: ByteVector,
    tlvStream: TlvStream[ChannelAnnouncementTlv] = TlvStream.empty
) extends RoutingMessage
    with AnnouncementMessage
    with HasChainHash

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

case class ChannelUpdate(
    signature: ByteVector64,
    chainHash: ByteVector32,
    shortChannelId: ShortChannelId,
    timestamp: TimestampSecond,
    channelFlags: ChannelUpdate.ChannelFlags,
    cltvExpiryDelta: CltvExpiryDelta,
    htlcMinimumMsat: MilliSatoshi,
    feeBaseMsat: MilliSatoshi,
    feeProportionalMillionths: Long,
    htlcMaximumMsat: Option[MilliSatoshi],
    tlvStream: TlvStream[ChannelUpdateTlv] = TlvStream.empty
) extends RoutingMessage
    with AnnouncementMessage
    with HasTimestamp
    with HasChainHash {
  def messageFlags: Byte = if (htlcMaximumMsat.isDefined) 1 else 0
  def toStringShort: String =
    s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"
}

object ChannelUpdate {
  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)

  object ChannelFlags {}
}
