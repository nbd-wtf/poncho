package codecs

import scodec.codecs._
import scodec.{Attempt, Err}

object HostedChannelMessages {
  final val HC_INVOKE_HOSTED_CHANNEL_TAG = 65535
  final val HC_INIT_HOSTED_CHANNEL_TAG = 65533
  final val HC_LAST_CROSS_SIGNED_STATE_TAG = 65531
  final val HC_STATE_UPDATE_TAG = 65529
  final val HC_STATE_OVERRIDE_TAG = 65527
  final val HC_HOSTED_CHANNEL_BRANDING_TAG = 65525
  final val HC_ANNOUNCEMENT_SIGNATURE_TAG = 65523
  final val HC_RESIZE_CHANNEL_TAG = 65521
  final val HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG = 65519
  final val HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG = 65517
  final val HC_QUERY_PREIMAGES_TAG = 65515
  final val HC_REPLY_PREIMAGES_TAG = 65513
  final val HC_ASK_BRANDING_INFO = 65511

  final val PHC_ANNOUNCE_GOSSIP_TAG = 64513
  final val PHC_ANNOUNCE_SYNC_TAG = 64511
  final val PHC_UPDATE_GOSSIP_TAG = 64509
  final val PHC_UPDATE_SYNC_TAG = 64507

  final val HC_UPDATE_ADD_HTLC_TAG = 63505
  final val HC_UPDATE_FULFILL_HTLC_TAG = 63503
  final val HC_UPDATE_FAIL_HTLC_TAG = 63501
  final val HC_UPDATE_FAIL_MALFORMED_HTLC_TAG = 63499
  final val HC_ERROR_TAG = 63497

  val hostedMessageTags: Set[Int] =
    Set(
      HC_INVOKE_HOSTED_CHANNEL_TAG,
      HC_INIT_HOSTED_CHANNEL_TAG,
      HC_LAST_CROSS_SIGNED_STATE_TAG,
      HC_STATE_UPDATE_TAG,
      HC_STATE_OVERRIDE_TAG,
      HC_HOSTED_CHANNEL_BRANDING_TAG,
      HC_ANNOUNCEMENT_SIGNATURE_TAG,
      HC_RESIZE_CHANNEL_TAG,
      HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG,
      HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG,
      HC_ASK_BRANDING_INFO
    )

  val preimageQueryTags: Set[Int] =
    Set(HC_QUERY_PREIMAGES_TAG, HC_REPLY_PREIMAGES_TAG)
  val announceTags: Set[Int] = Set(
    PHC_ANNOUNCE_GOSSIP_TAG,
    PHC_ANNOUNCE_SYNC_TAG,
    PHC_UPDATE_GOSSIP_TAG,
    PHC_UPDATE_SYNC_TAG
  )
  val chanIdMessageTags: Set[Int] = Set(
    HC_UPDATE_ADD_HTLC_TAG,
    HC_UPDATE_FULFILL_HTLC_TAG,
    HC_UPDATE_FAIL_HTLC_TAG,
    HC_UPDATE_FAIL_MALFORMED_HTLC_TAG,
    HC_ERROR_TAG
  )

  val invokeHostedChannelCodec = {
    (bytes32 withContext "chainHash") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (varsizebinarydata withContext "secret")
  }.as[InvokeHostedChannel]

  val initHostedChannelCodec = {
    (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (millisatoshi withContext "htlcMinimumMsat") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (millisatoshi withContext "channelCapacityMsat") ::
      (millisatoshi withContext "initialClientBalanceMsat") ::
      (listOfN(uint16, uint16) withContext "features")
  }.as[InitHostedChannel]

  val hostedChannelBrandingCodec = {
    (rgb withContext "rgbColor") ::
      (optional(bool8, varsizebinarydata) withContext "pngIcon") ::
      (variableSizeBytes(uint16, utf8) withContext "contactInfo")
  }.as[HostedChannelBranding]

  lazy val lastCrossSignedStateCodec = {
    (bool8 withContext "isHost") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (lengthDelimited(
        initHostedChannelCodec
      ) withContext "initHostedChannel") ::
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
  }.as[LastCrossSignedState]

  val stateUpdateCodec = {
    (uint32 withContext "blockDay") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateUpdate]

  val stateOverrideCodec = {
    (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateOverride]

  val announcementSignatureCodec = {
    (bytes64 withContext "nodeSignature") ::
      (bool8 withContext "wantsReply")
  }.as[AnnouncementSignature]

  val resizeChannelCodec = {
    (satoshi withContext "newCapacity") ::
      (bytes64 withContext "clientSig")
  }.as[ResizeChannel]

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

  def decodeHostedMessage(
      tag: Int,
      data: List[Byte]
  ): Attempt[HostedChannelMessage] = {
    val bitVector = data.toBitVector

    val decodeAttempt = tag match {
      case HC_STATE_UPDATE_TAG   => stateUpdateCodec.decode(bitVector)
      case HC_STATE_OVERRIDE_TAG => stateOverrideCodec.decode(bitVector)
      case HC_RESIZE_CHANNEL_TAG => resizeChannelCodec.decode(bitVector)
      case HC_ASK_BRANDING_INFO  => askBrandingInfoCodec.decode(bitVector)
      case HC_INIT_HOSTED_CHANNEL_TAG =>
        initHostedChannelCodec.decode(bitVector)
      case HC_INVOKE_HOSTED_CHANNEL_TAG =>
        invokeHostedChannelCodec.decode(bitVector)
      case HC_LAST_CROSS_SIGNED_STATE_TAG =>
        lastCrossSignedStateCodec.decode(bitVector)
      case HC_ANNOUNCEMENT_SIGNATURE_TAG =>
        announcementSignatureCodec.decode(bitVector)
      case HC_HOSTED_CHANNEL_BRANDING_TAG =>
        hostedChannelBrandingCodec.decode(bitVector)
      case HC_QUERY_PUBLIC_HOSTED_CHANNELS_TAG =>
        queryPublicHostedChannelsCodec.decode(bitVector)
      case HC_REPLY_PUBLIC_HOSTED_CHANNELS_END_TAG =>
        replyPublicHostedChannelsEndCodec.decode(bitVector)
      case HC_QUERY_PREIMAGES_TAG => queryPreimagesCodec.decode(bitVector)
      case HC_REPLY_PREIMAGES_TAG => replyPreimagesCodec.decode(bitVector)
    }

    decodeAttempt.map(_.value)
  }
}
