import scala.util.chaining._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scodec.bits.ByteVector
import io.circe._
import io.circe.syntax._
import scoin.ShortChannelId
import scoin.ln._
import scoin.hc._

import Picklers.given

object Printer {
  def hcDetail(
      peerId: ByteVector,
      data: ChannelData,
      chan: Channel
  ): Future[Json] = {
    val channel = ChannelMaster.channels.get(peerId)

    val simple = hcSimple((peerId, data))
      .remove("their_balance")
      .remove("total_updates")

    val basic = basicChannelData(data)

    moreChannelData(chan, data).map { more =>
      simple.add("data", basic.deepMerge(more).asJson).asJson
    }
  }

  def hcSimple(chan: (ByteVector, ChannelData)): JsonObject = {
    val (peerId, data) = chan
    val channel = ChannelMaster.channels.get(peerId)

    JsonObject(
      "peer_id" := peerId.toHex,
      "channel_id" :=
        hostedChannelId(
          ChannelMaster.node.publicKey.value,
          peerId
        ).toHex,
      "short_channel_id" :=
        hostedShortChannelId(
          ChannelMaster.node.publicKey.value,
          peerId
        ).toString,
      "their_balance" := data.lcss.remoteBalance.toLong,
      "total_updates" := (data.lcss.localUpdates + data.lcss.remoteUpdates).toInt,
      "status" := channel
        .map(_.status.getClass.getSimpleName.toLowerCase)
        .getOrElse("offline")
    )
  }

  private def basicChannelData(data: ChannelData): JsonObject =
    JsonObject(
      "is_host" := data.lcss.isHost,
      "blockday" := data.lcss.blockDay.toInt,
      "balance" := Json.obj(
        "total" := data.lcss.initHostedChannel.channelCapacity.toLong,
        "local" := data.lcss.localBalance.toLong,
        "remote" := data.lcss.remoteBalance.toLong
      ),
      "updates" := Json.obj(
        "local" := data.lcss.localUpdates.toInt,
        "remote" := data.lcss.remoteUpdates.toInt
      ),
      "acceptingResize" := data.acceptingResize.map(_.toLong),
      "errors" := Json.obj(
        "local" := data.localErrors.map(dtlerr => dtlerr.toString),
        "remote" := data.remoteErrors.map(err => err.asText)
      ),
      "proposedOverride" := data.proposedOverride
        .map(_.localBalance.toLong)
    )

  private def moreChannelData(
      channel: Channel,
      data: ChannelData
  ): Future[JsonObject] = for {
    incoming <- Future.sequence(
      data.lcss.incomingHtlcs.map(mapHtlc(channel.shortChannelId, _, true))
    )
    outgoing <- Future.sequence(
      data.lcss.outgoingHtlcs.map(mapHtlc(channel.shortChannelId, _))
    )
  } yield JsonObject(
    "htlcs" := Json.obj(
      "incoming" := incoming,
      "outgoing" := outgoing
    ),
    "uncommitted_updates" := channel.state.uncommittedUpdates
      .groupBy {
        case FromLocal(_, _) => "from_us"
        case FromRemote(_)   => "from_them"
      }
      .mapValues(_.groupBy {
        case FromLocal(upd, _) => upd.getClass().getSimpleName()
        case FromRemote(upd)   => upd.getClass().getSimpleName()
      }
        .map(_.size))
      .map(_.size)
  )

  private def mapHtlc(
      scid: ShortChannelId,
      htlc: UpdateAddHtlc,
      isIncoming: Boolean = false
  ): Future[Json] = {
    val releasedPreimage =
      ChannelMaster.database.data.preimages.get(htlc.paymentHash)
    val forwardedToAnotherHC = ChannelMaster.database.data.htlcForwards
      .get(HtlcIdentifier(scid, htlc.id))

    val base = JsonObject(
      "id" := htlc.id.toLong,
      "amount" := htlc.amountMsat.toLong,
      "hash" := htlc.paymentHash.toHex,
      "cltv" := htlc.cltvExpiry.toLong,
      "released_uncommitted_preimage" := releasedPreimage.map(_.toHex),
      "relayed_to_hc" := forwardedToAnotherHC
    )

    if (
      isIncoming &&
      releasedPreimage.isEmpty &&
      forwardedToAnotherHC.isEmpty
    ) {
      ChannelMaster.node
        .inspectOutgoingPayment(
          HtlcIdentifier(scid, htlc.id),
          htlc.paymentHash
        )
        .recover(err => s"error: ${err.toString}")
        .map {
          case None                  => "pending"
          case Some(Left(_))         => "failed"
          case Some(Right(preimage)) => s"succeeded: $preimage"
        }
        .map(result => base.add("upstream_status", result.asJson).asJson)
    } else Future.successful(base.asJson)
  }
}
