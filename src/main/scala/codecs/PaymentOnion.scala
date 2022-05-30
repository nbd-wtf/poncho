package codecs

import scala.scalanative.unsigned._
import scodec.bits._
import scodec.codecs._
import scodec.{Codec, Err}

import crypto.{PublicKey, PrivateKey}
import CommonCodecs._
import TlvCodecs._
import OnionRoutingCodecs._

/** Tlv types used inside a payment onion. */
sealed trait OnionPaymentPayloadTlv extends Tlv

object OnionPaymentPayloadTlv {

  /** Amount to forward to the next node. */
  case class AmountToForward(amount: MilliSatoshi)
      extends OnionPaymentPayloadTlv

  /** CLTV value to use for the HTLC offered to the next node. */
  case class OutgoingCltv(cltv: CltvExpiry) extends OnionPaymentPayloadTlv

  /** Id of the channel to use to forward a payment to the next node. */
  case class OutgoingChannelId(shortChannelId: Long)
      extends OnionPaymentPayloadTlv

  /** Bolt 11 payment details (only included for the last node).
    *
    * @param secret
    *   payment secret specified in the Bolt 11 invoice.
    * @param totalAmount
    *   total amount in multi-part payments. When missing, assumed to be equal
    *   to AmountToForward.
    */
  case class PaymentData(secret: ByteVector32, totalAmount: MilliSatoshi)
      extends OnionPaymentPayloadTlv

  /** Route blinding lets the recipient provide some encrypted data for each
    * intermediate node in the blinded part of the route. This data cannot be
    * decrypted or modified by the sender and usually contains information to
    * locate the next node without revealing it to the sender.
    */
  case class EncryptedRecipientData(data: ByteVector)
      extends OnionPaymentPayloadTlv

  /** Blinding ephemeral public key that should be used to derive shared secrets
    * when using route blinding.
    */
  case class BlindingPoint(publicKey: PublicKey) extends OnionPaymentPayloadTlv

  /** Id of the next node. */
  case class OutgoingNodeId(nodeId: PublicKey) extends OnionPaymentPayloadTlv

  /** When payment metadata is included in a Bolt 11 invoice, we should send it
    * as-is to the recipient. This lets recipients generate invoices without
    * having to store anything on their side until the invoice is paid.
    */
  case class PaymentMetadata(data: ByteVector) extends OnionPaymentPayloadTlv

  /** Invoice feature bits. Only included for intermediate trampoline nodes when
    * they should convert to a legacy payment because the final recipient
    * doesn't support trampoline.
    */
  case class InvoiceFeatures(features: ByteVector)
      extends OnionPaymentPayloadTlv

  /** Invoice routing hints. Only included for intermediate trampoline nodes
    * when they should convert to a legacy payment because the final recipient
    * doesn't support trampoline.
    */
  // case class InvoiceRoutingInfo(extraHops: List[Bolt11Invoice.ExtraHops])
  //     extends OnionPaymentPayloadTlv

  /** An encrypted trampoline onion packet. */
  case class TrampolineOnion(packet: OnionRoutingPacket)
      extends OnionPaymentPayloadTlv

  /** Pre-image included by the sender of a payment in case of a donation */
  case class KeySend(paymentPreimage: ByteVector32)
      extends OnionPaymentPayloadTlv

}

object PaymentOnion {

  import OnionPaymentPayloadTlv._

  /*
   * We use the following architecture for payment onion payloads:
   *
   *                                                              PerHopPayload
   *                                           _______________________/\_______________
   *                                          /                                        \
   *                                 RelayPayload                                   FinalPayload
   *                     _______________/\_________________                              \______
   *                    /                                  \                                    \
   *           ChannelRelayPayload                          \                                    \
   *         ________/\______________                        \                                    \
   *        /                        \                        \                                    \
   * RelayLegacyPayload     ChannelRelayTlvPayload     NodeRelayPayload                      FinalTlvPayload
   *
   * We also introduce additional traits to separate payloads based on their encoding (PerHopPayloadFormat) and on the
   * type of onion packet they can be used with (PacketType).
   */

  sealed trait PerHopPayloadFormat

  /** Legacy fixed-size 65-bytes onion payload. */
  sealed trait LegacyFormat extends PerHopPayloadFormat

  /** Variable-length onion payload with optional additional tlv records. */
  sealed trait TlvFormat extends PerHopPayloadFormat {
    def records: TlvStream[OnionPaymentPayloadTlv]
  }

  /** Payment onion packet type. */
  sealed trait PacketType

  /** A payment onion packet is used when offering an HTLC to a remote node. */
  sealed trait PaymentPacket extends PacketType

  /** A trampoline onion packet is used to defer route construction to
    * trampoline nodes. It is usually embedded inside a [[PaymentPacket]] in the
    * final node's payload.
    */
  sealed trait TrampolinePacket extends PacketType

  /** Per-hop payload from an HTLC's payment onion (after decryption and
    * decoding).
    */
  sealed trait PerHopPayload

  /** Per-hop payload for an intermediate node. */
  sealed trait RelayPayload extends PerHopPayload with PerHopPayloadFormat {

    /** Amount to forward to the next node. */
    val amountToForward: MilliSatoshi

    /** CLTV value to use for the HTLC offered to the next node. */
    val outgoingCltv: CltvExpiry
  }

  sealed trait ChannelRelayPayload extends RelayPayload with PaymentPacket {

    /** Id of the channel to use to forward a payment to the next node. */
    val outgoingChannelId: Long
  }

  /** Per-hop payload for a final node. */
  sealed trait FinalPayload
      extends PerHopPayload
      with PerHopPayloadFormat
      with TrampolinePacket
      with PaymentPacket {
    val amount: MilliSatoshi
    val expiry: CltvExpiry
    val paymentSecret: ByteVector32
    val totalAmount: MilliSatoshi
    val paymentPreimage: Option[ByteVector32]
    val paymentMetadata: Option[ByteVector]
  }

  case class RelayLegacyPayload(
      outgoingChannelId: Long,
      amountToForward: MilliSatoshi,
      outgoingCltv: CltvExpiry
  ) extends ChannelRelayPayload
      with LegacyFormat

  case class ChannelRelayTlvPayload(records: TlvStream[OnionPaymentPayloadTlv])
      extends ChannelRelayPayload
      with TlvFormat {
    override val amountToForward = records.get[AmountToForward].get.amount
    override val outgoingCltv = records.get[OutgoingCltv].get.cltv
    override val outgoingChannelId =
      records.get[OutgoingChannelId].get.shortChannelId
  }

  object ChannelRelayTlvPayload {
    def apply(
        outgoingChannelId: Long,
        amountToForward: MilliSatoshi,
        outgoingCltv: CltvExpiry
    ): ChannelRelayTlvPayload =
      ChannelRelayTlvPayload(
        TlvStream(
          OnionPaymentPayloadTlv.AmountToForward(amountToForward),
          OnionPaymentPayloadTlv.OutgoingCltv(outgoingCltv),
          OnionPaymentPayloadTlv.OutgoingChannelId(outgoingChannelId)
        )
      )
  }

  case class NodeRelayPayload(records: TlvStream[OnionPaymentPayloadTlv])
      extends RelayPayload
      with TlvFormat
      with TrampolinePacket {
    val amountToForward = records.get[AmountToForward].get.amount
    val outgoingCltv = records.get[OutgoingCltv].get.cltv
    val outgoingNodeId = records.get[OutgoingNodeId].get.nodeId
    // The following fields are only included in the trampoline-to-legacy case.
    val totalAmount = records
      .get[PaymentData]
      .map(_.totalAmount)
      .filter(_.toLong != 0L)
      .getOrElse(amountToForward)
    val paymentSecret = records.get[PaymentData].map(_.secret)
    val paymentMetadata = records.get[PaymentMetadata].map(_.data)
    val invoiceFeatures = records.get[InvoiceFeatures].map(_.features)
    // val invoiceRoutingInfo = records.get[InvoiceRoutingInfo].map(_.extraHops)
  }

  case class FinalTlvPayload(records: TlvStream[OnionPaymentPayloadTlv])
      extends FinalPayload
      with TlvFormat {
    override val amount = records.get[AmountToForward].get.amount
    override val expiry = records.get[OutgoingCltv].get.cltv
    override val paymentSecret = records.get[PaymentData].get.secret
    override val totalAmount = records
      .get[PaymentData]
      .map(_.totalAmount)
      .filter(_.toLong != 0L)
      .getOrElse(amount)
    override val paymentPreimage = records.get[KeySend].map(_.paymentPreimage)
    override val paymentMetadata = records.get[PaymentMetadata].map(_.data)
  }

  def createNodeRelayPayload(
      amount: MilliSatoshi,
      expiry: CltvExpiry,
      nextNodeId: PublicKey
  ): NodeRelayPayload =
    NodeRelayPayload(
      TlvStream(
        AmountToForward(amount),
        OutgoingCltv(expiry),
        OutgoingNodeId(nextNodeId)
      )
    )

  def createSinglePartPayload(
      amount: MilliSatoshi,
      expiry: CltvExpiry,
      paymentSecret: ByteVector32,
      paymentMetadata: Option[ByteVector],
      userCustomTlvs: Seq[GenericTlv] = Nil
  ): FinalPayload = {
    val tlvs = Seq(
      Some(AmountToForward(amount)),
      Some(OutgoingCltv(expiry)),
      Some(PaymentData(paymentSecret, amount)),
      paymentMetadata.map(m => PaymentMetadata(m))
    ).flatten
    FinalTlvPayload(TlvStream(tlvs, userCustomTlvs))
  }

  def createMultiPartPayload(
      amount: MilliSatoshi,
      totalAmount: MilliSatoshi,
      expiry: CltvExpiry,
      paymentSecret: ByteVector32,
      paymentMetadata: Option[ByteVector],
      additionalTlvs: Seq[OnionPaymentPayloadTlv] = Nil,
      userCustomTlvs: Seq[GenericTlv] = Nil
  ): FinalPayload = {
    val tlvs = Seq(
      Some(AmountToForward(amount)),
      Some(OutgoingCltv(expiry)),
      Some(PaymentData(paymentSecret, totalAmount)),
      paymentMetadata.map(m => PaymentMetadata(m))
    ).flatten
    FinalTlvPayload(TlvStream(tlvs ++ additionalTlvs, userCustomTlvs))
  }

  /** Create a trampoline outer payload. */
  def createTrampolinePayload(
      amount: MilliSatoshi,
      totalAmount: MilliSatoshi,
      expiry: CltvExpiry,
      paymentSecret: ByteVector32,
      trampolinePacket: OnionRoutingPacket
  ): FinalPayload = {
    FinalTlvPayload(
      TlvStream(
        AmountToForward(amount),
        OutgoingCltv(expiry),
        PaymentData(paymentSecret, totalAmount),
        TrampolineOnion(trampolinePacket)
      )
    )
  }
}

object PaymentOnionCodecs {

  import OnionPaymentPayloadTlv._
  import PaymentOnion._
  import scodec.bits.HexStringSyntax
  import scodec.codecs._
  import scodec.{Attempt, Codec, DecodeResult, Decoder}

  val paymentOnionPayloadLength = 1300
  val trampolineOnionPayloadLength = 400
  val paymentOnionPacketCodec: Codec[OnionRoutingPacket] =
    OnionRoutingCodecs.onionRoutingPacketCodec(paymentOnionPayloadLength)
  val trampolineOnionPacketCodec: Codec[OnionRoutingPacket] =
    OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnionPayloadLength)

  /** The 1.1 BOLT spec changed the payment onion frame format to use
    * variable-length per-hop payloads. The first bytes contain a varint
    * encoding the length of the payload data (not including the trailing mac).
    * That varint is considered to be part of the payload, so the payload length
    * includes the number of bytes used by the varint prefix.
    */
  val payloadLengthDecoder = Decoder[Long]((bits: BitVector) =>
    varintoverflow
      .decode(bits)
      .map(d =>
        DecodeResult(
          d.value + (bits.length - d.remainder.length) / 8,
          d.remainder
        )
      )
  )

  private val amountToForward: Codec[AmountToForward] =
    ("amount_msat" | ltmillisatoshi).as[AmountToForward]

  private val outgoingCltv: Codec[OutgoingCltv] = ("cltv" | ltu32).xmap(
    cltv => OutgoingCltv(CltvExpiry(cltv)),
    (c: OutgoingCltv) => c.cltv.toLong
  )

  private val outgoingChannelId: Codec[OutgoingChannelId] =
    variableSizeBytesLong(varintoverflow, "short_channel_id" | int64)
      .as[OutgoingChannelId]

  private val paymentData: Codec[PaymentData] = variableSizeBytesLong(
    varintoverflow,
    ("payment_secret" | bytes32) :: ("total_msat" | tmillisatoshi)
  ).as[PaymentData]

  private val encryptedRecipientData: Codec[EncryptedRecipientData] =
    variableSizeBytesLong(varintoverflow, "encrypted_data" | bytes)
      .as[EncryptedRecipientData]

  private val blindingPoint: Codec[BlindingPoint] =
    (("length" | constant(hex"21")) :: ("blinding" | bytes(33)))
      .as[BlindingPoint]

  private val outgoingNodeId: Codec[OutgoingNodeId] =
    (("length" | constant(hex"21")) :: ("node_id" | bytes(33)))
      .as[OutgoingNodeId]

  private val paymentMetadata: Codec[PaymentMetadata] =
    variableSizeBytesLong(varintoverflow, "payment_metadata" | bytes)
      .as[PaymentMetadata]

  private val invoiceFeatures: Codec[InvoiceFeatures] =
    variableSizeBytesLong(varintoverflow, bytes).as[InvoiceFeatures]

  private val trampolineOnion: Codec[TrampolineOnion] =
    variableSizeBytesLong(varintoverflow, trampolineOnionPacketCodec)
      .as[TrampolineOnion]

  private val keySend: Codec[KeySend] =
    variableSizeBytesLong(varintoverflow, bytes32).as[KeySend]

  private val onionTlvCodec = discriminated[OnionPaymentPayloadTlv]
    .by(varint)
    .typecase(2.toULong, amountToForward)
    .typecase(4.toULong, outgoingCltv)
    .typecase(6.toULong, outgoingChannelId)
    .typecase(8.toULong, paymentData)
    .typecase(10.toULong, encryptedRecipientData)
    .typecase(12.toULong, blindingPoint)
    .typecase(16.toULong, paymentMetadata)

  val tlvPerHopPayloadCodec: Codec[TlvStream[OnionPaymentPayloadTlv]] =
    TlvCodecs
      .lengthPrefixedTlvStream[OnionPaymentPayloadTlv](onionTlvCodec)
      .complete

  private val legacyRelayPerHopPayloadCodec: Codec[RelayLegacyPayload] =
    (("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | int64) ::
      ("amt_to_forward" | millisatoshi) ::
      ("outgoing_cltv_value" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12)))
      .as[RelayLegacyPayload]

  val channelRelayPerHopPayloadCodec: Codec[ChannelRelayPayload] =
    fallback(tlvPerHopPayloadCodec, legacyRelayPerHopPayloadCodec).narrow(
      {
        case Left(tlvs) if tlvs.get[AmountToForward].isEmpty =>
          Attempt.failure(MissingRequiredTlv(2.toULong))
        case Left(tlvs) if tlvs.get[OutgoingCltv].isEmpty =>
          Attempt.failure(MissingRequiredTlv(4.toULong))
        case Left(tlvs) if tlvs.get[OutgoingChannelId].isEmpty =>
          Attempt.failure(MissingRequiredTlv(6.toULong))
        case Left(tlvs)    => Attempt.successful(ChannelRelayTlvPayload(tlvs))
        case Right(legacy) => Attempt.successful(legacy)
      },
      {
        case legacy: RelayLegacyPayload   => Right(legacy)
        case ChannelRelayTlvPayload(tlvs) => Left(tlvs)
      }
    )

  val nodeRelayPerHopPayloadCodec: Codec[NodeRelayPayload] =
    tlvPerHopPayloadCodec.narrow(
      {
        case tlvs if tlvs.get[AmountToForward].isEmpty =>
          Attempt.failure(MissingRequiredTlv(2.toULong))
        case tlvs if tlvs.get[OutgoingCltv].isEmpty =>
          Attempt.failure(MissingRequiredTlv(4.toULong))
        case tlvs if tlvs.get[OutgoingNodeId].isEmpty =>
          Attempt.failure(MissingRequiredTlv(66098.toULong))
        case tlvs => Attempt.successful(NodeRelayPayload(tlvs))
      },
      { case NodeRelayPayload(tlvs) =>
        tlvs
      }
    )

  val finalPerHopPayloadCodec: Codec[FinalPayload] =
    tlvPerHopPayloadCodec.narrow(
      {
        case tlvs if tlvs.get[AmountToForward].isEmpty =>
          Attempt.failure(MissingRequiredTlv(2.toULong))
        case tlvs if tlvs.get[OutgoingCltv].isEmpty =>
          Attempt.failure(MissingRequiredTlv(4.toULong))
        case tlvs if tlvs.get[PaymentData].isEmpty =>
          Attempt.failure(MissingRequiredTlv(8.toULong))
        case tlvs => Attempt.successful(FinalTlvPayload(tlvs))
      },
      { case FinalTlvPayload(tlvs) =>
        tlvs
      }
    )

  def paymentOnionPerHopPayloadCodec(
      isLastPacket: Boolean
  ): Codec[PaymentPacket] = if (isLastPacket)
    finalPerHopPayloadCodec.upcast[PaymentPacket]
  else channelRelayPerHopPayloadCodec.upcast[PaymentPacket]

  def trampolineOnionPerHopPayloadCodec(
      isLastPacket: Boolean
  ): Codec[TrampolinePacket] = if (isLastPacket)
    finalPerHopPayloadCodec.upcast[TrampolinePacket]
  else nodeRelayPerHopPayloadCodec.upcast[TrampolinePacket]
}

case class OnionRoutingPacket(
    version: Int,
    publicKey: ByteVector,
    payload: ByteVector,
    hmac: ByteVector32
)

object OnionRoutingCodecs {
  case class MissingRequiredTlv(tag: ULong) extends Err {
    val failureMessage: FailureMessage = InvalidOnionPayload(tag, 0)
    override def message = failureMessage.message
    override def context: List[String] = Nil
    override def pushContext(ctx: String): Err = this
  }

  def onionRoutingPacketCodec(payloadLength: Int): Codec[OnionRoutingPacket] =
    (("version" | uint8) ::
      ("publicKey" | bytes(33)) ::
      ("onionPayload" | bytes(payloadLength)) ::
      ("hmac" | bytes32)).as[OnionRoutingPacket]
}
