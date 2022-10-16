import scala.util.chaining._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scodec.bits.ByteVector
import io.circe._
import io.circe.syntax._
import scoin.ShortChannelId
import scoin.ln._
import scoin.hc._

object Printer {
  def hcDetail(
      peerId: ByteVector,
      data: ChannelData,
      chan: Channel
  ): Future[Json] = {
    val channel = ChannelMaster.channels.get(peerId)

    val simple = hcSimple((peerId, data))
    simple.remove("their_balance")
    simple.remove("total_updates")

    val basic = basicChannelData(data)

    moreChannelData(chan, data).map { more =>
      simple.add("data", basic.deepMerge(more).asJson).asJson
    }
  }

  def hcSimple(chan: (ByteVector, ChannelData)): JsonObject = {
    val (peerId, data) = chan
    val channel = ChannelMaster.channels.get(peerId)

    JsonObject(
      "peer_id" -> peerId.toHex.asJson,
      "channel_id" ->
        hostedChannelId(
          ChannelMaster.node.publicKey.value,
          peerId
        ).toHex.asJson,
      "short_channel_id" ->
        hostedShortChannelId(
          ChannelMaster.node.publicKey.value,
          peerId
        ).toString.asJson,
      "their_balance" -> data.lcss.remoteBalance.toLong.asJson,
      "total_updates" -> (data.lcss.localUpdates + data.lcss.remoteUpdates).toInt.asJson,
      "status" -> channel
        .map(_.status.getClass.getSimpleName.toLowerCase)
        .getOrElse("offline")
        .asJson
    )
  }

  private def basicChannelData(data: ChannelData): JsonObject =
    JsonObject(
      "is_host" -> data.lcss.isHost.asJson,
      "blockday" -> data.lcss.blockDay.toInt.asJson,
      "balance" -> JsonObject(
        "total" -> data.lcss.initHostedChannel.channelCapacity.toLong.asJson,
        "local" -> data.lcss.localBalance.toLong.asJson,
        "remote" -> data.lcss.remoteBalance.toLong.asJson
      ).asJson,
      "updates" -> JsonObject(
        "local" -> data.lcss.localUpdates.toInt.asJson,
        "remote" -> data.lcss.remoteUpdates.toInt.asJson
      ).asJson,
      "acceptingResize" -> data.acceptingResize.map(_.toLong).asJson,
      "errors" -> JsonObject(
        "local" -> data.localErrors
          .map(dtlerr => dtlerr.toString)
          .asJson,
        "remote" -> data.remoteErrors
          .map(err => err.toString)
          .asJson
      ).asJson,
      "proposedOverride" -> data.proposedOverride
        .map(_.localBalance.toLong)
        .asJson
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
    "htlcs" -> JsonObject(
      "incoming" -> incoming.asJson,
      "outgoing" -> outgoing.asJson
    ).asJson,
    "uncommitted_updates" -> channel.state.uncommittedUpdates
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
      .asJson
  )

  private def mapHtlc(
      scid: ShortChannelId,
      htlc: UpdateAddHtlc,
      fetchOutgoingStatus: Boolean = false
  ): Future[Json] =
    val base = JsonObject(
      "id" -> htlc.id.toLong.asJson,
      "amount" -> htlc.amountMsat.toLong.asJson,
      "hash" -> htlc.paymentHash.toHex.asJson,
      "cltv" -> htlc.cltvExpiry.toLong.asJson,
      "released_uncommitted_preimage" -> ChannelMaster.database.data.preimages
        .get(htlc.paymentHash)
        .map(_.toHex)
        .asJson
    )

    if (fetchOutgoingStatus) {
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
