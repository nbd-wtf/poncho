package codecs

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import scala.scalanative.unsigned._
import scodec.bits._
import scodec.codecs._
import scodec.Codec

import crypto.Crypto
import codecs.Protocol
import codecs.TlvCodecs._
import codecs.CommonCodecs._
import codecs.HostedChannelTags._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._

sealed trait HostedClientMessage
sealed trait HostedServerMessage
sealed trait HostedGossipMessage
sealed trait HostedPreimageMessage
sealed trait ChannelModifier

case class InvokeHostedChannel(
    chainHash: ByteVector32,
    refundScriptPubKey: ByteVector,
    secret: ByteVector = ByteVector.empty
) extends HostedClientMessage {
  val finalSecret: ByteVector = secret.take(128)
}

case class InitHostedChannel(
    maxHtlcValueInFlightMsat: ULong,
    htlcMinimumMsat: MilliSatoshi,
    maxAcceptedHtlcs: Int,
    channelCapacityMsat: MilliSatoshi,
    initialClientBalanceMsat: MilliSatoshi,
    features: List[Int] = Nil
) extends HostedServerMessage

case class HostedChannelBranding(
    rgbColor: Color,
    pngIcon: Option[ByteVector],
    contactInfo: String
) extends HostedServerMessage

case class LastCrossSignedState(
    isHost: Boolean,
    refundScriptPubKey: ByteVector,
    initHostedChannel: InitHostedChannel,
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    remoteBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    incomingHtlcs: List[UpdateAddHtlc],
    outgoingHtlcs: List[UpdateAddHtlc],
    remoteSigOfLocal: ByteVector64,
    localSigOfRemote: ByteVector64
) extends HostedServerMessage
    with HostedClientMessage {
  def totalUpdates: Long = localUpdates + remoteUpdates

  lazy val reverse: LastCrossSignedState =
    copy(
      isHost = !isHost,
      localUpdates = remoteUpdates,
      remoteUpdates = localUpdates,
      localBalanceMsat = remoteBalanceMsat,
      remoteBalanceMsat = localBalanceMsat,
      remoteSigOfLocal = localSigOfRemote,
      localSigOfRemote = remoteSigOfLocal,
      incomingHtlcs = outgoingHtlcs,
      outgoingHtlcs = incomingHtlcs
    )

  lazy val hostedSigHash: ByteVector32 = {
    val inPayments = incomingHtlcs.map(add =>
      LightningMessageCodecs.updateAddHtlcCodec
        .encode(add)
        .require
        .toByteVector
    )
    val outPayments = outgoingHtlcs.map(add =>
      LightningMessageCodecs.updateAddHtlcCodec
        .encode(add)
        .require
        .toByteVector
    )
    val hostFlag = if (isHost) 1 else 0

    val message = refundScriptPubKey ++
      Protocol.writeUInt64(
        initHostedChannel.channelCapacityMsat.toLong,
        ByteOrder.LITTLE_ENDIAN
      ) ++
      Protocol.writeUInt64(
        initHostedChannel.initialClientBalanceMsat.toLong,
        ByteOrder.LITTLE_ENDIAN
      ) ++
      Protocol.writeUInt32(blockDay, ByteOrder.LITTLE_ENDIAN) ++
      Protocol
        .writeUInt64(localBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
      Protocol
        .writeUInt64(remoteBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
      Protocol.writeUInt32(localUpdates, ByteOrder.LITTLE_ENDIAN) ++
      Protocol.writeUInt32(remoteUpdates, ByteOrder.LITTLE_ENDIAN) ++
      inPayments.foldLeft(ByteVector.empty) { case (acc, htlc) =>
        acc ++ htlc
      } ++
      outPayments.foldLeft(ByteVector.empty) { case (acc, htlc) =>
        acc ++ htlc
      } :+
      hostFlag.toByte

    Crypto.sha256(message)
  }

  def verifyRemoteSig(pubKey: ByteVector): Boolean =
    Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: ByteVector32): LastCrossSignedState = {
    val localSignature = Crypto.sign(reverse.hostedSigHash, priv)
    copy(localSigOfRemote = localSignature)
  }

  def stateUpdate: StateUpdate =
    StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote)

  def stateOverride: StateOverride =
    StateOverride(
      blockDay,
      localBalanceMsat,
      localUpdates,
      remoteUpdates,
      localSigOfRemote
    )
}

case class StateUpdate(
    blockDay: Long,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedServerMessage
    with HostedClientMessage {
  def totalUpdates: Long = localUpdates + remoteUpdates
}

case class StateOverride(
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedServerMessage

case class AnnouncementSignature(
    nodeSignature: ByteVector64,
    wantsReply: Boolean
) extends HostedGossipMessage

case class ResizeChannel(
    newCapacity: Satoshi,
    clientSig: ByteVector64 = ByteVector64.Zeroes
) extends HostedClientMessage {
  def isRemoteResized(remote: LastCrossSignedState): Boolean =
    newCapacity.toMilliSatoshi == remote.initHostedChannel.channelCapacityMsat

  def sign(priv: ByteVector32): ResizeChannel = ResizeChannel(
    clientSig = Crypto.sign(Crypto.sha256(sigMaterial), priv),
    newCapacity = newCapacity
  )

  def verifyClientSig(pubKey: ByteVector): Boolean =
    Crypto.verifySignature(Crypto.sha256(sigMaterial), clientSig, pubKey)

  lazy val sigMaterial: ByteVector = {
    val bin = new Array[Byte](8)
    val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(newCapacity.toLong)
    ByteVector.view(bin)
  }
  lazy val newCapacityMsatU64: ULong = newCapacity.toMilliSatoshi.toLong.toULong
}

case class AskBrandingInfo(chainHash: ByteVector32) extends HostedClientMessage

case class QueryPublicHostedChannels(chainHash: ByteVector32)
    extends HostedGossipMessage {}

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32)
    extends HostedGossipMessage {}

// Queries
case class QueryPreimages(hashes: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {}

case class ReplyPreimages(preimages: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {}

// BOLT messages (used with nonstandard tag numbers)
case class Error(
    channelId: ByteVector32,
    data: ByteVector,
    tlvStream: TlvStream[ErrorTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage {
  def toAscii: String = if (data.toArray.forall(ch => ch >= 32 && ch < 127))
    new String(data.toArray, StandardCharsets.US_ASCII)
  else "n/a"

  def description: String = {
    val tag = data.take(4)
    val postTagData = data.drop(4)

    Error.knownHostedCodes.get(tag.toHex) match {
      case Some(code) if postTagData.isEmpty => s"hosted-code=$code"
      case Some(code) =>
        s"hosted-code=$code, extra=${this.copy(data = postTagData).toAscii}"
      case None => toAscii
    }
  }
}

object Error {
  final val ERR_HOSTED_WRONG_BLOCKDAY = "0001"
  final val ERR_HOSTED_WRONG_LOCAL_SIG = "0002"
  final val ERR_HOSTED_WRONG_REMOTE_SIG = "0003"
  final val ERR_HOSTED_CLOSED_BY_REMOTE_PEER = "0004"
  final val ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC = "0005"
  final val ERR_HOSTED_HTLC_EXTERNAL_FULFILL = "0006"
  final val ERR_HOSTED_CHANNEL_DENIED = "0007"
  final val ERR_HOSTED_MANUAL_SUSPEND = "0008"
  final val ERR_HOSTED_INVALID_RESIZE = "0009"
  final val ERR_MISSING_CHANNEL = "0010"

  val knownHostedCodes: Map[String, String] = Map(
    ERR_HOSTED_WRONG_BLOCKDAY -> "ERR_HOSTED_WRONG_BLOCKDAY",
    ERR_HOSTED_WRONG_LOCAL_SIG -> "ERR_HOSTED_WRONG_LOCAL_SIG",
    ERR_HOSTED_WRONG_REMOTE_SIG -> "ERR_HOSTED_WRONG_REMOTE_SIG",
    ERR_HOSTED_CLOSED_BY_REMOTE_PEER -> "ERR_HOSTED_CLOSED_BY_REMOTE_PEER",
    ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC -> "ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC",
    ERR_HOSTED_HTLC_EXTERNAL_FULFILL -> "ERR_HOSTED_HTLC_EXTERNAL_FULFILL",
    ERR_HOSTED_CHANNEL_DENIED -> "ERR_HOSTED_CHANNEL_DENIED",
    ERR_HOSTED_MANUAL_SUSPEND -> "ERR_HOSTED_MANUAL_SUSPEND",
    ERR_HOSTED_INVALID_RESIZE -> "ERR_HOSTED_INVALID_RESIZE",
    ERR_MISSING_CHANNEL -> "ERR_MISSING_CHANNEL"
  )

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
) extends HostedClientMessage
    with HostedServerMessage
    with ChannelModifier

case class UpdateFulfillHtlc(
    channelId: ByteVector32,
    id: ULong,
    paymentPreimage: ByteVector32,
    tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage
    with ChannelModifier

case class UpdateFailHtlc(
    channelId: ByteVector32,
    id: ULong,
    reason: ByteVector,
    tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage
    with ChannelModifier

case class UpdateFailMalformedHtlc(
    channelId: ByteVector32,
    id: ULong,
    onionHash: ByteVector32,
    failureCode: Int,
    tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty
) extends HostedClientMessage
    with HostedServerMessage
    with ChannelModifier

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
) extends HostedGossipMessage
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
) extends HostedServerMessage
    with HostedClientMessage
    with HostedGossipMessage {
  def messageFlags: Byte = if (htlcMaximumMsat.isDefined) 1 else 0
  def toStringShort: String =
    s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"
}

object ChannelUpdate {
  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)
  object ChannelFlags {}
}
