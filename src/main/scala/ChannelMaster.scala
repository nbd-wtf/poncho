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
  val servers = mutable.Map.empty[ByteVector, ChannelServer]
  val clients = mutable.Map.empty[ByteVector, ChannelClient]
  def getChannel(peerId: ByteVector, isHost: Boolean): Channel[_, _] =
    if isHost then getChannelServer(peerId) else getChannelClient(peerId)
  def getChannelServer(peerId: ByteVector): ChannelServer =
    servers.getOrElseUpdate(peerId, { new ChannelServer(peerId) })
  def getChannelClient(peerId: ByteVector): ChannelClient =
    clients.getOrElseUpdate(peerId, { new ChannelClient(peerId) })

  def all: Map[ByteVector, ChannelData] = Database.data.channels

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
          "peer_id" -> peerId.toHex,
          "channel_id" -> Utils.getChannelId(Main.node.ourPubKey, peerId).toHex,
          "short_channel_id" -> Utils
            .getShortChannelId(Main.node.ourPubKey, peerId)
            .toString,
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
