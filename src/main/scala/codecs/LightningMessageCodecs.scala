package codecs

import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}
import codecs.CommonCodecs._

object LightningMessageCodecs {
  val featuresCodec: Codec[Features[Feature]] =
    varsizebinarydata.xmap[Features[Feature]](
      { bytes => Features(bytes) },
      { features => features.toByteVector }
    )

  val initFeaturesCodec: Codec[Features[InitFeature]] =
    featuresCodec.xmap[Features[InitFeature]](_.initFeatures(), _.unscoped())

  /** For historical reasons, features are divided into two feature bitmasks. We
    * only send from the second one, but we allow receiving in both.
    */
  val combinedFeaturesCodec: Codec[Features[InitFeature]] =
    (("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata))
      .as[(ByteVector, ByteVector)]
      .xmap[Features[InitFeature]](
        { case (gf, lf) =>
          val length = gf.length.max(lf.length)
          Features(gf.padLeft(length) | lf.padLeft(length)).initFeatures()
        },
        { features => (ByteVector.empty, features.toByteVector) }
      )

  val errorCodec: Codec[Error] = (("channelId" | bytes32) ::
    ("data" | varsizebinarydata) ::
    ("tlvStream" | ErrorTlv.errorTlvCodec)).as[Error]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (("channelId" | bytes32) ::
    ("id" | uint64) ::
    ("amountMsat" | millisatoshi) ::
    ("paymentHash" | bytes32) ::
    ("expiry" | cltvExpiry) ::
    ("onionRoutingPacket" | bytes) ::
    ("tlvStream" | UpdateAddHtlcTlv.addHtlcTlvCodec)).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] =
    (("channelId" | bytes32) ::
      ("id" | uint64) ::
      ("paymentPreimage" | bytes32) ::
      ("tlvStream" | UpdateFulfillHtlcTlv.updateFulfillHtlcTlvCodec))
      .as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (("channelId" | bytes32) ::
    ("id" | uint64) ::
    ("reason" | varsizebinarydata) ::
    ("tlvStream" | UpdateFailHtlcTlv.updateFailHtlcTlvCodec)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec
      : Codec[UpdateFailMalformedHtlc] = (("channelId" | bytes32) ::
    ("id" | uint64) ::
    ("onionHash" | bytes32) ::
    ("failureCode" | uint16) ::
    ("tlvStream" | UpdateFailMalformedHtlcTlv.updateFailMalformedHtlcTlvCodec))
    .as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (("channelId" | bytes32) ::
    ("signature" | bytes64) ::
    ("htlcSignatures" | listofsignatures) ::
    ("tlvStream" | CommitSigTlv.commitSigTlvCodec)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (("channelId" | bytes32) ::
    ("perCommitmentSecret" | bytes32) ::
    ("nextPerCommitmentPoint" | bytes) ::
    ("tlvStream" | RevokeAndAckTlv.revokeAndAckTlvCodec)).as[RevokeAndAck]

  val channelAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | bytes) ::
      ("nodeId2" | bytes) ::
      ("bitcoinKey1" | bytes) ::
      ("bitcoinKey2" | bytes) ::
      ("tlvStream" | ChannelAnnouncementTlv.channelAnnouncementTlvCodec)

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] =
    (("nodeSignature1" | bytes64) ::
      ("nodeSignature2" | bytes64) ::
      ("bitcoinSignature1" | bytes64) ::
      ("bitcoinSignature2" | bytes64) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  private case class MessageFlags(optionChannelHtlcMax: Boolean)

  private val messageFlagsCodec =
    ("messageFlags" | (ignore(7) :: bool)).as[MessageFlags]

  val reverseBool: Codec[Boolean] = bool.xmap[Boolean](b => !b, b => !b)

  /** BOLT 7 defines a 'disable' bit and a 'direction' bit, but it's easier to
    * understand if we take the reverse.
    */
  val channelFlagsCodec =
    ("channelFlags" | (ignore(6) :: reverseBool :: reverseBool))
      .as[ChannelUpdate.ChannelFlags]

  val channelUpdateChecksumCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      (messageFlagsCodec
        .consume({ messageFlags =>
          channelFlagsCodec ::
            ("cltvExpiryDelta" | cltvExpiryDelta) ::
            ("htlcMinimumMsat" | millisatoshi) ::
            ("feeBaseMsat" | millisatoshi32) ::
            ("feeProportionalMillionths" | uint32) ::
            ("htlcMaximumMsat" | conditional(
              messageFlags.optionChannelHtlcMax,
              millisatoshi
            ))
        })({
          // The purpose of this is to tell scodec how to derive the message flags from the data, so we can remove that field
          // from the codec definition and the case class, making it purely a serialization detail.
          case (_, _, _, _, _, htlcMaximumMsatOpt) =>
            MessageFlags(optionChannelHtlcMax = htlcMaximumMsatOpt.isDefined)
        }))

  val channelUpdateWitnessCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | timestampSecond) ::
      (messageFlagsCodec
        .consume({ messageFlags =>
          channelFlagsCodec ::
            ("cltvExpiryDelta" | cltvExpiryDelta) ::
            ("htlcMinimumMsat" | millisatoshi) ::
            ("feeBaseMsat" | millisatoshi32) ::
            ("feeProportionalMillionths" | uint32) ::
            ("htlcMaximumMsat" | conditional(
              messageFlags.optionChannelHtlcMax,
              millisatoshi
            )) ::
            ("tlvStream" | ChannelUpdateTlv.channelUpdateTlvCodec)
        })({
          // same comment above
          case (_, _, _, _, _, htlcMaximumMsatOpt, _) =>
            MessageFlags(optionChannelHtlcMax = htlcMaximumMsatOpt.isDefined)
        }))

  val channelUpdateCodec: Codec[ChannelUpdate] = (("signature" | bytes64) ::
    channelUpdateWitnessCodec).as[ChannelUpdate]

}
