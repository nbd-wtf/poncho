import scala.scalanative.unsigned._
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import scodec.bits.ByteVector
import scodec.{DecodeResult}

import codecs._
import crypto.Crypto

object ChannelMaster {
  val channels = mutable.Map.empty[ByteVector, Channel]
  def getChannel(peerId: ByteVector): Channel =
    channels.getOrElseUpdate(peerId, { new Channel(peerId) })

  def channelsJSON: ujson.Arr = {
    val mapHtlc = (htlc: UpdateAddHtlc) =>
      ujson.Obj(
        "id" -> htlc.id.toLong.toInt,
        "amount" -> htlc.amountMsat.toLong.toInt,
        "hash" -> htlc.paymentHash.toHex,
        "cltv" -> htlc.cltvExpiry.toLong.toInt
      )

    ujson.Arr.from(
      channels.toList.map((peerId, channel) => {
        val chandata = channel.state.data

        ujson.Obj(
          "peer_id" -> peerId.toHex,
          "channel_id" -> Utils.getChannelId(Main.node.ourPubKey, peerId).toHex,
          "short_channel_id" -> Utils
            .getShortChannelId(Main.node.ourPubKey, peerId)
            .toString,
          "status" -> channel.state.status.getClass.getSimpleName.toLowerCase,
          "data" -> channel.state.data.lcss.map(lcss =>
            ujson.Obj(
              "blockday" -> lcss.blockDay.toInt,
              "local_errors" -> channel.state.data.localErrors
                .map(err => ujson.Str(err.description)),
              "remote_errors" -> channel.state.data.localErrors
                .map(err => ujson.Str(err.description)),
              "is_host" -> lcss.isHost,
              "balance" -> ujson.Obj(
                "total" -> lcss.initHostedChannel.channelCapacityMsat.toLong.toInt,
                "local" -> lcss.localBalanceMsat.toLong.toInt,
                "remote" -> lcss.remoteBalanceMsat.toLong.toInt
              ),
              "incoming_htlcs" -> ujson.Arr.from(
                lcss.incomingHtlcs.map(mapHtlc)
              ),
              "outgoing_htlcs" -> ujson.Arr.from(
                lcss.outgoingHtlcs.map(mapHtlc)
              )
            )
          )
        )
      })
    )
  }
}
