import scala.concurrent.Future
import scodec.bits.ByteVector

import codecs._
import crypto.Crypto

type PaymentStatus = Option[Either[Option[PaymentFailure], ByteVector32]]

sealed trait PaymentFailure
case class FailureOnion(onion: ByteVector) extends PaymentFailure
case class NormalFailureMessage(message: FailureMessage) extends PaymentFailure

case class FromLocal(upd: ChannelModifier)
case class FromRemote(upd: ChannelModifier)

trait Channel[
    Recv <: HostedServerMessage | HostedClientMessage,
    Send <: HostedServerMessage | HostedClientMessage
](
    peerId: ByteVector
) extends castor.SimpleActor[Recv] {
  trait State

  var state: State

  def addHTLC(
      incoming: MilliSatoshi,
      prototype: UpdateAddHtlc
  ): Future[PaymentStatus]

  def sendMessage: Send => Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, _)

  def stay = state

  lazy val channelId = Utils.getChannelId(Main.node.ourPubKey, peerId)

  lazy val shortChannelId = Utils.getShortChannelId(Main.node.ourPubKey, peerId)

  def getChannelUpdate: ChannelUpdate = {
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(Main.node.ourPubKey, peerId),
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
}
