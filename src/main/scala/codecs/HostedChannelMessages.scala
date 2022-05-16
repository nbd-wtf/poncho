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

sealed trait HostedClientMessage[A] {
  def tag: Int
  def codec: Codec[A]
}

sealed trait HostedServerMessage[A] {
  def tag: Int
  def codec: Codec[A]
}

sealed trait HostedGossipMessage
sealed trait HostedPreimageMessage

case class InvokeHostedChannel(
    chainHash: ByteVector32,
    refundScriptPubKey: ByteVector,
    secret: ByteVector = ByteVector.empty
) extends HostedClientMessage[InvokeHostedChannel] {
  val finalSecret: ByteVector = secret.take(128)

  def tag = HC_INVOKE_HOSTED_CHANNEL_TAG
  def codec = invokeHostedChannelCodec
}

case class InitHostedChannel(
    maxHtlcValueInFlightMsat: ULong,
    htlcMinimumMsat: MilliSatoshi,
    maxAcceptedHtlcs: Int,
    channelCapacityMsat: MilliSatoshi,
    initialClientBalanceMsat: MilliSatoshi,
    features: List[Int] = Nil
) extends HostedServerMessage[InitHostedChannel] {
  def tag = HC_INIT_HOSTED_CHANNEL_TAG
  def codec = initHostedChannelCodec
}

case class HostedChannelBranding(
    rgbColor: Color,
    pngIcon: Option[ByteVector],
    contactInfo: String
) extends HostedServerMessage[HostedChannelBranding] {
  def tag = HC_HOSTED_CHANNEL_BRANDING_TAG
  def codec = hostedChannelBrandingCodec
}

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
) extends HostedServerMessage[LastCrossSignedState]
    with HostedClientMessage[LastCrossSignedState] {
  def tag = HC_LAST_CROSS_SIGNED_STATE_TAG
  def codec = lastCrossSignedStateCodec

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

    Crypto.sha256(
      refundScriptPubKey ++
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
    )
  }

  def verifyRemoteSig(pubKey: ByteVector): Boolean =
    Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: ByteVector32): LastCrossSignedState = {
    val localSignature = Crypto.sign(reverse.hostedSigHash, priv)
    copy(localSigOfRemote = localSignature)
  }

  lazy val stateUpdate: StateUpdate =
    StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote)
}

case class StateUpdate(
    blockDay: Long,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedServerMessage[StateUpdate]
    with HostedClientMessage[StateUpdate] {
  def tag = HC_STATE_UPDATE_TAG
  def codec = stateUpdateCodec
}

case class StateOverride(
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedServerMessage[StateOverride] {
  def tag = HC_STATE_OVERRIDE_TAG
  def codec = stateOverrideCodec
}

case class AnnouncementSignature(
    nodeSignature: ByteVector64,
    wantsReply: Boolean
) extends HostedGossipMessage

case class ResizeChannel(
    newCapacity: Satoshi,
    clientSig: ByteVector64 = ByteVector64.Zeroes
) extends HostedClientMessage[ResizeChannel] {
  def tag = HC_RESIZE_CHANNEL_TAG
  def codec = resizeChannelCodec

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

case class AskBrandingInfo(chainHash: ByteVector32)
    extends HostedClientMessage[AskBrandingInfo] {
  def tag = HC_ASK_BRANDING_INFO_TAG
  def codec = askBrandingInfoCodec
}

// PHC
case class QueryPublicHostedChannels(chainHash: ByteVector32)
    extends HostedGossipMessage {
  def tag = HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG
  def codec = queryPublicHostedChannelsCodec
}

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32)
    extends HostedGossipMessage {
  def tag = HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG
  def codec = replyPublicHostedChannelsEndCodec
}

// Queries
case class QueryPreimages(hashes: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {
  def tag = HC_QUERY_PREIMAGES_TAG
  def codec = queryPreimagesCodec
}

case class ReplyPreimages(preimages: List[ByteVector32] = Nil)
    extends HostedPreimageMessage {
  def tag = HC_REPLY_PREIMAGES_TAG
  def codec = replyPreimagesCodec
}

// BOLT messages (used with nonstandard tag numbers)
case class Error(
    channelId: ByteVector32,
    data: ByteVector,
    tlvStream: TlvStream[ErrorTlv] = TlvStream.empty
) extends HostedClientMessage[Error]
    with HostedServerMessage[Error] {
  def tag = HC_ERROR_TAG
  def codec = errorCodec

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
) extends HostedClientMessage[UpdateAddHtlc]
    with HostedServerMessage[UpdateAddHtlc] {
  def tag = HC_UPDATE_ADD_HTLC_TAG
  def codec = updateAddHtlcCodec
}

case class UpdateFulfillHtlc(
    channelId: ByteVector32,
    id: ULong,
    paymentPreimage: ByteVector32,
    tlvStream: TlvStream[UpdateFulfillHtlcTlv] = TlvStream.empty
) extends HostedClientMessage[UpdateFulfillHtlc]
    with HostedServerMessage[UpdateFulfillHtlc] {
  def tag = HC_UPDATE_FULFILL_HTLC_TAG
  def codec = updateFulfillHtlcCodec
}

case class UpdateFailHtlc(
    channelId: ByteVector32,
    id: ULong,
    reason: ByteVector,
    tlvStream: TlvStream[UpdateFailHtlcTlv] = TlvStream.empty
) extends HostedClientMessage[UpdateFailHtlc]
    with HostedServerMessage[UpdateFailHtlc] {
  def tag = HC_UPDATE_FAIL_HTLC_TAG
  def codec = updateFailHtlcCodec
}

case class UpdateFailMalformedHtlc(
    channelId: ByteVector32,
    id: ULong,
    onionHash: ByteVector32,
    failureCode: Int,
    tlvStream: TlvStream[UpdateFailMalformedHtlcTlv] = TlvStream.empty
) extends HostedClientMessage[UpdateFailMalformedHtlc]
    with HostedServerMessage[UpdateFailMalformedHtlc] {
  def tag = HC_UPDATE_FAIL_MALFORMED_HTLC_TAG
  def codec = updateFailMalformedHtlcCodec
}

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
) extends HostedGossipMessage {
  def gossipTag = PHC_ANNOUNCE_GOSSIP_TAG
  def syncTag = PHC_ANNOUNCE_SYNC_TAG
  def codec = channelAnnouncementCodec
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
) extends HostedGossipMessage {
  def gossipTag = PHC_UPDATE_GOSSIP_TAG
  def syncTag = PHC_UPDATE_SYNC_TAG
  def codec = channelUpdateCodec

  def messageFlags: Byte = if (htlcMaximumMsat.isDefined) 1 else 0
  def toStringShort: String =
    s"cltvExpiryDelta=$cltvExpiryDelta,feeBase=$feeBaseMsat,feeProportionalMillionths=$feeProportionalMillionths"
}

object ChannelUpdate {
  case class ChannelFlags(isEnabled: Boolean, isNode1: Boolean)
  object ChannelFlags {}
}
