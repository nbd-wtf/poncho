import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.{Future}
import scala.util.{Failure, Success}
import ujson._

import UnixSocket.UnixSocket
import scodec.bits.ByteVector
import codecs.HostedChannelCodecs._
import codecs._

object CLN {
  var rpcAddr: String = ""
  def rpc(method: String, params: ujson.Obj): Future[ujson.Value] = {
    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> 0,
          "method" -> method,
          "params" -> params
        )
      )

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(ujson.read(_))
      .flatMap(read =>
        if (read("error").objOpt.isDefined) {
          Future.failed(Exception(read("error")("message").str))
        } else {
          Future.successful(("result"))
        }
      )
  }

  def answer(req: ujson.Value)(result: ujson.Value): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "result" -> result
        )
      )
    )
  }

  def log(message: String): Unit = {
    if (Main.isDev) {
      System.err.println(
        Console.BOLD + "> " +
          Console.BLUE + "poncho" + Console.RESET +
          Console.BOLD + ": " + Console.RESET +
          Console.GREEN + message + Console.RESET
      )
    } else {
      System.out.println(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "method" -> "log",
          "params" -> ujson.Obj(
            "message" -> message
          )
        )
      )
    }
  }

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val data = req("params")
    val reply = answer(req)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> false,
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "custommsg"),
              ujson.Obj("name" -> "htlc_accepted")
            ),
            "rpcmethods" -> ujson.Arr(),
            "notifications" -> ujson.Arr(),
            "featurebits" -> ujson.Obj(
              "init" -> 32972 /* hosted_channels */ .toHexString,
              "node" -> 32972 /* hosted_channels */ .toHexString
            )
          )
        )
      case "init" => {
        reply(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> req("id").num,
            "result" -> ujson.Obj()
          )
        )

        rpcAddr =
          s"${data("configuration")("lightning-dir").str}/${data("configuration")("rpc-file").str}"
      }
      case "htlc_accepted" => {
        val htlc = data("htlc")
        val onion = data("onion")

        (for {
          chandata <- Database.data.channels.values
            .find(_.shortChannelId == onion("short_channel_id").str)
          peer <- ChannelMaster.getChannelActor(chandata.peerId)

          paymentHash <- ByteVector32.fromHex(htlc("payment_hash").str)
          onionRoutingPacket <- ByteVector.fromHex(
            onion("next_onion").str
          )
          amount <- Try(htlc("amount").str.dropRight(4).toInt).toOption

          msg = UpdateAddHtlc(
            channelId = chandata.channelId,
            id = 0L.toULong,
            amountMsat = MilliSatoshi(amount),
            paymentHash = paymentHash,
            cltvExpiry =
              CltvExpiry(BlockHeight(htlc("cltv_expiry").num.toLong)),
            onionRoutingPacket = onionRoutingPacket
          )
        } yield (peer, msg)) match {
          case Some((peer, msg)) => peer.send(Send(msg))
          case _ => {
            val hash = htlc("payment_hash").str
            val scid = onion("short_channel_id").str
            log(
              s"not handling this htlc: $hash=>scid"
            )
            reply(ujson.Obj("result" -> "continue"))
          }
        }
      }
      case "custommsg" => {
        val peerId = data("peer_id").str
        val tagH = data("payload").str.take(4)

        (for {
          tagV <- ByteVector.fromHex(tagH)
          tag = tagV.toInt(signed = false)
          payload <- ByteVector.fromHex(data("payload").str.drop(4))
          msg <- decodeClientMessage(tag, payload).toOption
          peer <- ChannelMaster.getChannelActor(peerId)
        } yield (peer, msg)) match {
          case Some((peer, msg)) => peer.send(Recv(msg))
          case _ => {
            log(s"unhandled custommsg [$tagH] from $peerId")
          }
        }
      }
      case "sendpay_success" => {
        log(s"sendpay_success: $data")
      }
      case "sendpay_failure" => {
        log(s"sendpay_failure: $data")
      }
      case "connect" => {
        val id = data("id").str
        val address = data("address").str
        log(s"$id connected: $address")
      }
      case "disconnect" => {
        val id = data("id").str
        log(s"$id disconnected")
      }
    }
  }
}
