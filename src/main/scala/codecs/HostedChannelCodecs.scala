package codecs

import scodec.codecs._
import scodec.bits._
import scodec.{Attempt, Err}
import codecs.CommonCodecs._
import codecs.HostedChannelTags._
import codecs.LightningMessageCodecs._

object HostedChannelCodecs {
  val invokeHostedChannelCodec = (
    // @formatter:off
    (bytes32 withContext "chainHash") ::
    (varsizebinarydata withContext "refundScriptPubKey") ::
    (varsizebinarydata withContext "secret")
    // @formatter:on
  ).as[InvokeHostedChannel]

  val initHostedChannelCodec = (
    // @formatter:off
    (uint64 withContext "maxHtlcValueInFlightMsat") ::
    (millisatoshi withContext "htlcMinimumMsat") ::
    (uint16 withContext "maxAcceptedHtlcs") ::
    (millisatoshi withContext "channelCapacityMsat") ::
    (millisatoshi withContext "initialClientBalanceMsat") ::
    (listOfN(uint16, uint16) withContext "features")
    // @formatter:on
  ).as[InitHostedChannel]

  val hostedChannelBrandingCodec = (
    // @formatter:off
    (rgb withContext "rgbColor") ::
    (optional(bool8, varsizebinarydata) withContext "pngIcon") ::
    (variableSizeBytes(uint16, utf8) withContext "contactInfo")
    // @formatter:on
  ).as[HostedChannelBranding]

  lazy val lastCrossSignedStateCodec = (
    // @formatter:off
    (bool8 withContext "isHost") ::
    (varsizebinarydata withContext "refundScriptPubKey") ::
    (lengthDelimited(initHostedChannelCodec) withContext "initHostedChannel") ::
    (uint32 withContext "blockDay") ::
    (millisatoshi withContext "localBalanceMsat") ::
    (millisatoshi withContext "remoteBalanceMsat") ::
    (uint32 withContext "localUpdates") ::
    (uint32 withContext "remoteUpdates") ::
    (listOfN(
      uint16,
      lengthDelimited(updateAddHtlcCodec)
    ) withContext "incomingHtlcs") ::
    (listOfN(
      uint16,
      lengthDelimited(updateAddHtlcCodec)
    ) withContext "outgoingHtlcs") ::
    (bytes64 withContext "remoteSigOfLocal") ::
    (bytes64 withContext "localSigOfRemote")
    // @formatter:on
  ).as[LastCrossSignedState]

  val stateUpdateCodec = (
    // @formatter:off
    (uint32 withContext "blockDay") ::
    (uint32 withContext "localUpdates") ::
    (uint32 withContext "remoteUpdates") ::
    (bytes64 withContext "localSigOfRemoteLCSS")
    // @formatter:on
  ).as[StateUpdate]

  val stateOverrideCodec = (
    // @formatter:off
    (uint32 withContext "blockDay") ::
    (millisatoshi withContext "localBalanceMsat") ::
    (uint32 withContext "localUpdates") ::
    (uint32 withContext "remoteUpdates") ::
    (bytes64 withContext "localSigOfRemoteLCSS")
    // @formatter:ofn
  ).as[StateOverride]

  val announcementSignatureCodec = (
    // @formatter:off
    (bytes64 withContext "nodeSignature") ::
    (bool8 withContext "wantsReply")
    // @formatter:on
  ).as[AnnouncementSignature]

  val resizeChannelCodec = (
    // @formatter:off
    (satoshi withContext "newCapacity") ::
    (bytes64 withContext "clientSig")
    // @formatter:on
  ).as[ResizeChannel]

  val askBrandingInfoCodec =
    (bytes32 withContext "chainHash").as[AskBrandingInfo]

  val queryPublicHostedChannelsCodec =
    (bytes32 withContext "chainHash").as[QueryPublicHostedChannels]

  val replyPublicHostedChannelsEndCodec =
    (bytes32 withContext "chainHash").as[ReplyPublicHostedChannelsEnd]

  val queryPreimagesCodec =
    (listOfN(uint16, bytes32) withContext "hashes").as[QueryPreimages]

  val replyPreimagesCodec =
    (listOfN(uint16, bytes32) withContext "preimages").as[ReplyPreimages]

  def decodeServerMessage(
      tag: Int,
      data: ByteVector
  ): Attempt[HostedServerMessage] = {
    val bitVector = data.toBitVector
    val decodeAttempt = tag match {
      case HC_STATE_UPDATE_TAG   => stateUpdateCodec.decode(bitVector)
      case HC_STATE_OVERRIDE_TAG => stateOverrideCodec.decode(bitVector)
      case HC_INIT_HOSTED_CHANNEL_TAG =>
        initHostedChannelCodec.decode(bitVector)
      case HC_LAST_CROSS_SIGNED_STATE_TAG =>
        lastCrossSignedStateCodec.decode(bitVector)
      case HC_HOSTED_CHANNEL_BRANDING_TAG =>
        hostedChannelBrandingCodec.decode(bitVector)
      case HC_ERROR_TAG            => errorCodec.decode(bitVector)
      case HC_UPDATE_ADD_HTLC_TAG  => updateAddHtlcCodec.decode(bitVector)
      case HC_UPDATE_FAIL_HTLC_TAG => updateFailHtlcCodec.decode(bitVector)
      case HC_UPDATE_FULFILL_HTLC_TAG =>
        updateFulfillHtlcCodec.decode(bitVector)
      case HC_UPDATE_FAIL_MALFORMED_HTLC_TAG =>
        updateFailMalformedHtlcCodec.decode(bitVector)
      case tag =>
        Attempt failure Err(s"unknown tag for message from server=$tag")
    }

    decodeAttempt.map(_.value)
  }

  def decodeClientMessage(
      tag: Int,
      data: ByteVector
  ): Attempt[HostedClientMessage] = {
    val bitVector = data.toBitVector
    val decodeAttempt = tag match {
      case HC_STATE_UPDATE_TAG      => stateUpdateCodec.decode(bitVector)
      case HC_RESIZE_CHANNEL_TAG    => resizeChannelCodec.decode(bitVector)
      case HC_ASK_BRANDING_INFO_TAG => askBrandingInfoCodec.decode(bitVector)
      case HC_INVOKE_HOSTED_CHANNEL_TAG =>
        invokeHostedChannelCodec.decode(bitVector)
      case HC_LAST_CROSS_SIGNED_STATE_TAG =>
        lastCrossSignedStateCodec.decode(bitVector)
      case HC_ERROR_TAG            => errorCodec.decode(bitVector)
      case HC_UPDATE_ADD_HTLC_TAG  => updateAddHtlcCodec.decode(bitVector)
      case HC_UPDATE_FAIL_HTLC_TAG => updateFailHtlcCodec.decode(bitVector)
      case HC_UPDATE_FULFILL_HTLC_TAG =>
        updateFulfillHtlcCodec.decode(bitVector)
      case HC_UPDATE_FAIL_MALFORMED_HTLC_TAG =>
        updateFailMalformedHtlcCodec.decode(bitVector)
      case tag =>
        Attempt failure Err(s"unknown tag for message from client=$tag")
    }

    decodeAttempt.map(_.value)
  }

  def encodeServerMessage(message: HostedServerMessage): (Int, ByteVector) = {
    val (tag, result) = message match {
      case msg: InitHostedChannel =>
        (HC_INIT_HOSTED_CHANNEL_TAG, initHostedChannelCodec.encode(msg))
      case msg: HostedChannelBranding =>
        (HC_HOSTED_CHANNEL_BRANDING_TAG, hostedChannelBrandingCodec.encode(msg))
      case msg: LastCrossSignedState =>
        (HC_LAST_CROSS_SIGNED_STATE_TAG, lastCrossSignedStateCodec.encode(msg))
      case msg: StateUpdate =>
        (HC_STATE_UPDATE_TAG, stateUpdateCodec.encode(msg))
      case msg: StateOverride =>
        (HC_STATE_OVERRIDE_TAG, stateOverrideCodec.encode(msg))
      case msg: Error =>
        (HC_ERROR_TAG, errorCodec.encode(msg))
      case msg: UpdateAddHtlc =>
        (HC_UPDATE_ADD_HTLC_TAG, updateAddHtlcCodec.encode(msg))
      case msg: UpdateFulfillHtlc =>
        (HC_UPDATE_FULFILL_HTLC_TAG, updateFulfillHtlcCodec.encode(msg))
      case msg: UpdateFailHtlc =>
        (HC_UPDATE_FAIL_HTLC_TAG, updateFailHtlcCodec.encode(msg))
      case msg: UpdateFailMalformedHtlc =>
        (
          HC_UPDATE_FAIL_MALFORMED_HTLC_TAG,
          updateFailMalformedHtlcCodec.encode(msg)
        )
      case msg: ChannelUpdate =>
        (PHC_UPDATE_SYNC_TAG, channelUpdateCodec.encode(msg))
    }

    (tag, result.require.toByteVector)
  }

  def encodeClientMessage(message: HostedClientMessage): (Int, ByteVector) = {
    val (tag, result) = message match {
      case msg: InvokeHostedChannel =>
        (HC_INVOKE_HOSTED_CHANNEL_TAG, invokeHostedChannelCodec.encode(msg))
      case msg: LastCrossSignedState =>
        (HC_LAST_CROSS_SIGNED_STATE_TAG, lastCrossSignedStateCodec.encode(msg))
      case msg: AskBrandingInfo =>
        (HC_ASK_BRANDING_INFO_TAG, askBrandingInfoCodec.encode(msg))
      case msg: ResizeChannel =>
        (HC_RESIZE_CHANNEL_TAG, resizeChannelCodec.encode(msg))
      case msg: Error =>
        (HC_ERROR_TAG, errorCodec.encode(msg))
      case msg: StateUpdate =>
        (HC_STATE_UPDATE_TAG, stateUpdateCodec.encode(msg))
      case msg: UpdateAddHtlc =>
        (HC_UPDATE_ADD_HTLC_TAG, updateAddHtlcCodec.encode(msg))
      case msg: UpdateFulfillHtlc =>
        (HC_UPDATE_FULFILL_HTLC_TAG, updateFulfillHtlcCodec.encode(msg))
      case msg: UpdateFailHtlc =>
        (HC_UPDATE_FAIL_HTLC_TAG, updateFailHtlcCodec.encode(msg))
      case msg: UpdateFailMalformedHtlc =>
        (
          HC_UPDATE_FAIL_MALFORMED_HTLC_TAG,
          updateFailMalformedHtlcCodec.encode(msg)
        )
      case msg: ChannelUpdate =>
        (PHC_UPDATE_SYNC_TAG, channelUpdateCodec.encode(msg))
    }

    (tag, result.require.toByteVector)
  }
}
