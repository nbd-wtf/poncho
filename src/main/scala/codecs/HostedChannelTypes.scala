package codecs

import java.nio.{ByteBuffer, ByteOrder}
import scala.scalanative.unsigned._
import scodec.bits._
import scodec.codecs._

import crypto.Crypto

sealed trait HostedChannelMessage

case class InvokeHostedChannel(
    chainHash: ByteVector32,
    refundScriptPubKey: ByteVector,
    secret: ByteVector = ByteVector.empty
) extends HostedChannelMessage {
  val finalSecret: ByteVector = secret.take(128)
}

case class InitHostedChannel(
    maxHtlcValueInFlightMsat: ULong,
    htlcMinimumMsat: MilliSatoshi,
    maxAcceptedHtlcs: Int,
    channelCapacityMsat: MilliSatoshi,
    initialClientBalanceMsat: MilliSatoshi,
    features: List[Int] = Nil
) extends HostedChannelMessage

case class HostedChannelBranding(
    rgbColor: Color,
    pngIcon: Option[ByteVector],
    contactInfo: String
) extends HostedChannelMessage

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
) extends HostedChannelMessage {
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
        Protocol.writeULong(
          initHostedChannel.channelCapacityMsat.toLong,
          ByteOrder.LITTLE_ENDIAN
        ) ++
        Protocol.writeULong(
          initHostedChannel.initialClientBalanceMsat.toLong,
          ByteOrder.LITTLE_ENDIAN
        ) ++
        Protocol.writeUInt32(blockDay, ByteOrder.LITTLE_ENDIAN) ++
        Protocol
          .writeULong(localBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol
          .writeULong(remoteBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
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

  def stateUpdate: StateUpdate =
    StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote)

  def verifyRemoteSig(pubKey: ByteVector): Boolean =
    Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: ByteVector32): LastCrossSignedState = {
    val localSignature = Crypto.sign(reverse.hostedSigHash, priv)
    copy(localSigOfRemote = localSignature)
  }
}

case class StateUpdate(
    blockDay: Long,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedChannelMessage

case class StateOverride(
    blockDay: Long,
    localBalanceMsat: MilliSatoshi,
    localUpdates: Long,
    remoteUpdates: Long,
    localSigOfRemoteLCSS: ByteVector64
) extends HostedChannelMessage

case class AnnouncementSignature(
    nodeSignature: ByteVector64,
    wantsReply: Boolean
) extends HostedChannelMessage

case class ResizeChannel(
    newCapacity: Satoshi,
    clientSig: ByteVector64 = ByteVector64.Zeroes
) extends HostedChannelMessage {
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

case class AskBrandingInfo(chainHash: ByteVector32) extends HostedChannelMessage

// PHC

case class QueryPublicHostedChannels(chainHash: ByteVector32)
    extends HostedChannelMessage

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32)
    extends HostedChannelMessage

// Queries

case class QueryPreimages(hashes: List[ByteVector32] = Nil)
    extends HostedChannelMessage

case class ReplyPreimages(preimages: List[ByteVector32] = Nil)
    extends HostedChannelMessage
