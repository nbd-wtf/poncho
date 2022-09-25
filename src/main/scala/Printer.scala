import scala.util.chaining._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scodec.bits.ByteVector
import ujson._
import scoin.ShortChannelId
import scoin.ln._
import scoin.hc._

object Printer {
  def hcDetail(
      peerId: ByteVector,
      data: ChannelData,
      chan: Channel
  ): Future[ujson.Value] = {
    val channel = ChannelMaster.channels.get(peerId)

    val simple = hcSimple((peerId, data))
    simple.value.remove("their_balance")
    simple.value.remove("total_updates")

    val basic = basicChannelData(data)

    moreChannelData(chan, data).map { more =>
      simple.value += "data" -> ujson.Obj(basic.value ++ more.value)
      simple
    }
  }

  def hcSimple(chan: (ByteVector, ChannelData)): ujson.Obj = {
    val (peerId, data) = chan
    val channel = ChannelMaster.channels.get(peerId)

    ujson.Obj(
      "peer_id" -> peerId.toHex,
      "channel_id" -> HostedChannelHelpers
        .getChannelId(ChannelMaster.node.publicKey.value, peerId)
        .toHex,
      "short_channel_id" -> HostedChannelHelpers
        .getShortChannelId(ChannelMaster.node.publicKey.value, peerId)
        .toString,
      "their_balance" -> data.lcss.remoteBalanceMsat.toLong.toInt,
      "total_updates" -> (data.lcss.localUpdates + data.lcss.remoteUpdates).toInt,
      "status" -> channel
        .map(_.status.getClass.getSimpleName.toLowerCase)
        .getOrElse("offline")
    )
  }

  private def basicChannelData(data: ChannelData): ujson.Obj =
    ujson.Obj(
      "is_host" -> data.lcss.isHost,
      "blockday" -> data.lcss.blockDay.toInt,
      "balance" -> ujson.Obj(
        "total" -> data.lcss.initHostedChannel.channelCapacityMsat.toLong.toInt,
        "local" -> data.lcss.localBalanceMsat.toLong.toInt,
        "remote" -> data.lcss.remoteBalanceMsat.toLong.toInt
      ),
      "updates" -> ujson.Obj(
        "local" -> data.lcss.localUpdates.toInt,
        "remote" -> data.lcss.remoteUpdates.toInt
      ),
      "errors" -> ujson.Obj(
        "local" -> data.localErrors
          .map(dtlerr => ujson.Str(dtlerr.toString)),
        "remote" -> data.remoteErrors
          .map(err => ujson.Str(err.toString))
      )
    )

  private def moreChannelData(
      channel: Channel,
      data: ChannelData
  ): Future[ujson.Obj] = for {
    incoming <- Future.sequence(
      data.lcss.incomingHtlcs.map(mapHtlc(channel.shortChannelId, _, true))
    )
    outgoing <- Future.sequence(
      data.lcss.outgoingHtlcs.map(mapHtlc(channel.shortChannelId, _))
    )
  } yield ujson.Obj(
    "htlcs" -> ujson.Obj(
      "incoming" -> ujson.Arr.from(incoming),
      "outgoing" -> ujson.Arr.from(outgoing)
    ),
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
  )

  private def mapHtlc(
      scid: ShortChannelId,
      htlc: UpdateAddHtlc,
      fetchOutgoingStatus: Boolean = false
  ): Future[ujson.Value] =
    val base = ujson.Obj(
      "id" -> htlc.id.toLong.toInt,
      "amount" -> htlc.amountMsat.toLong.toInt,
      "hash" -> htlc.paymentHash.toHex,
      "cltv" -> htlc.cltvExpiry.toLong.toInt,
      "released_uncommitted_preimage" -> ChannelMaster.database.data.preimages
        .get(htlc.paymentHash)
        .map(_.toHex)
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
        .map { result =>
          base.value += "upstream_status" -> result
          base
        }
    } else Future.successful(base)
}
