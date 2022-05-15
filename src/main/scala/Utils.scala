import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.annotation.tailrec
import scodec.bits.ByteVector

import crypto.Crypto
import codecs._

object Utils {
  @tailrec
  final def isLessThan(a: ByteVector, b: ByteVector): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else (a.head & 0xff) < (b.head & 0xff)
  }

  def makeChannelUpdate(
      lcss: LastCrossSignedState,
      remoteNodeId: ByteVector,
      shortChannelId: ShortChannelId
  ): ChannelUpdate = {
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(Main.node.ourPubKey, remoteNodeId),
      isEnabled = true
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              Main.chainHash,
              shortChannelId,
              timestamp,
              flags,
              Main.config.cltvExpiryDelta,
              Main.ourInit.htlcMinimumMsat,
              Main.config.feeBase,
              Main.config.feeProportionalMillionths,
              Some(Main.ourInit.channelCapacityMsat),
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, Main.node.getPrivateKey())
    ChannelUpdate(
      signature = sig,
      chainHash = Main.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = Main.config.cltvExpiryDelta,
      htlcMinimumMsat = Main.ourInit.htlcMinimumMsat,
      feeBaseMsat = Main.config.feeBase,
      feeProportionalMillionths = Main.config.feeProportionalMillionths,
      htlcMaximumMsat = Some(Main.ourInit.channelCapacityMsat)
    )
  }

  def getShortChannelId(peer1: ByteVector, peer2: ByteVector): ShortChannelId =
    ShortChannelId(
      List
        .fill(8)(
          Protocol.uint64(
            new ByteArrayInputStream(
              pubkeysCombined(peer1, peer2).toArray
            ),
            ByteOrder.BIG_ENDIAN
          )
        )
        .sum
    )

  def getChannelId(peer1: ByteVector, peer2: ByteVector): ByteVector32 =
    Crypto.sha256(pubkeysCombined(peer1, peer2))

  def pubkeysCombined(
      pubkey1: ByteVector,
      pubkey2: ByteVector
  ): ByteVector =
    if (Utils.isLessThan(pubkey1, pubkey2)) pubkey1 ++ pubkey2
    else pubkey2 ++ pubkey1
}
