import scala.scalanative.unsigned._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import castor.Context.Simple.global
import scodec.bits.ByteVector
import scodec.{DecodeResult}

import codecs._
import crypto.Crypto

object ChannelMaster {
  val servers = mutable.Map.empty[String, ChannelServer]
  val clients = mutable.Map.empty[String, ChannelClient]
  def getChannelServer(peerId: String): ChannelServer = {
    servers.getOrElseUpdate(peerId, { new ChannelServer(peerId) })
  }
  def getChannelClient(peerId: String): ChannelClient = {
    clients.getOrElseUpdate(peerId, { new ChannelClient(peerId) })
  }

  def all: Map[String, ChannelData] = Database.data.channels

  def channelsJSON: ujson.Arr = {
    val mapHtlc = (htlc: UpdateAddHtlc) =>
      ujson.Obj(
        "id" -> htlc.id.toLong.toInt,
        "amount" -> htlc.amountMsat.toLong.toInt,
        "hash" -> htlc.paymentHash.toHex,
        "cltv" -> htlc.cltvExpiry.toLong.toInt
      )

    ujson.Arr.from(
      all.toList.map((peerId, chandata) =>
        ujson.Obj(
          "peer_id" -> peerId,
          "channel_id" -> ChanTools.getChannelId(peerId).toHex,
          "short_channel_id" -> ChanTools.getShortChannelId(peerId).toString,
          "status" -> ujson.Obj(
            "blockday" -> chandata.lcss.blockDay.toInt,
            "active" -> chandata.isActive,
            "error" -> chandata.error
              .map(err => ujson.Str(err.description))
              .getOrElse(ujson.Null),
            "is_host" -> chandata.lcss.isHost
          ),
          "balance" -> ujson.Obj(
            "total" -> chandata.lcss.initHostedChannel.channelCapacityMsat.toLong.toInt,
            "local" -> chandata.lcss.localBalanceMsat.toLong.toInt,
            "remote" -> chandata.lcss.remoteBalanceMsat.toLong.toInt
          ),
          "incoming_htlcs" -> ujson.Arr.from(
            chandata.lcss.incomingHtlcs.map(mapHtlc)
          ),
          "outgoing_htlcs" -> ujson.Arr.from(
            chandata.lcss.outgoingHtlcs.map(mapHtlc)
          )
        )
      )
    )
  }
}

object ChanTools {
  def getChannelId(peerId: String): ByteVector32 =
    Utils.getChannelId(Main.node.ourPubKey, ByteVector.fromValidHex(peerId))

  def getShortChannelId(peerId: String): ShortChannelId =
    Utils.getShortChannelId(
      Main.node.ourPubKey,
      ByteVector.fromValidHex(peerId)
    )

  def makeChannelUpdate(
      peerId: String,
      lcss: LastCrossSignedState
  ): ChannelUpdate = {
    val remoteNodeId = ByteVector.fromValidHex(peerId)
    val shortChannelId = getShortChannelId(peerId)
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

  def lcssIsBroken(lcss: LastCrossSignedState): Boolean =
    lcss.localBalanceMsat < MilliSatoshi(0L) ||
      lcss.remoteBalanceMsat < MilliSatoshi(0L) || (
        lcss.initHostedChannel.channelCapacityMsat != (
          lcss.localBalanceMsat + lcss.remoteBalanceMsat + (lcss.outgoingHtlcs ++ lcss.incomingHtlcs)
            .map(_.amountMsat)
            .fold(MilliSatoshi(0L))(_ + _)
        )
      )

  def parseClientOnion(
      add: UpdateAddHtlc
  ): Either[
    Exception | UpdateFailHtlc | UpdateFailMalformedHtlc,
    (PaymentOnion.PaymentPacket, ByteVector)
  ] =
    PaymentOnionCodecs.paymentOnionPacketCodec
      .decode(add.onionRoutingPacket.toBitVector)
      .toEither match {
      case Left(err) =>
        // TODO return something here that indicates we must fail this channel
        Left(Exception(""))
      case Right(onion) =>
        Sphinx.peel(
          Main.node.getPrivateKey(),
          Some(add.paymentHash),
          onion.value
        ) match {
          case Left(badOnion) =>
            Left(
              UpdateFailMalformedHtlc(
                add.channelId,
                add.id,
                badOnion.onionHash,
                badOnion.code
              )
            )
          case Right(
                packet @ Sphinx.DecryptedPacket(payload, nextPacket, _)
              ) =>
            PaymentOnionCodecs
              .paymentOnionPerHopPayloadCodec(packet.isLastPacket)
              .decode(payload.bits)
              .toEither match {
              case Right(DecodeResult(packet, next)) =>
                Right((packet, next.toByteVector))
              case Left(e: OnionRoutingCodecs.MissingRequiredTlv) =>
                Left(
                  UpdateFailHtlc(
                    add.channelId,
                    add.id,
                    Sphinx.FailurePacket
                      .create(packet.sharedSecret, e.failureMessage)
                  )
                )
              case Left(_) =>
                Left(
                  UpdateFailHtlc(
                    add.channelId,
                    add.id,
                    Sphinx.FailurePacket
                      .create(
                        packet.sharedSecret,
                        InvalidOnionPayload(0.toULong, 0)
                      )
                  )
                )
            }
        }
    }
}
