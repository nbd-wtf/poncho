import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.loop.EventLoop.loop
import scala.concurrent.{Future}
import scala.util.{Failure, Success}
import ujson._

import UnixSocket.UnixSocket
import scodec.bits.ByteVector

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

  def answer(req: ujson.Value, result: ujson.Value): Unit = {
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
          Console.BLUE + "pokemon" + Console.RESET +
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

    req("method").str match {
      case "getmanifest" =>
        answer(
          req,
          ujson.Obj(
            "dynamic" -> false,
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure"
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
        answer(
          req,
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
        Database.data.channels.values
          .find(_.shortChannelId == data("onion")("short_channel_id").str)
          .map(_.peerId)
          .flatMap(ChannelMaster.getChannelActor(_))
          .map(_.send(SendHTLC())) // TODO
      }
      case "custommsg" => {
        val peerId = data("peer_id").str
        val tag = ByteVector.fromHex(data("payload").str.take(4))
        val payload = data("payload").str.drop(4)

        // TODO
        val action = tag match {
          case _ => ReceivedHTLC()
        }

        ChannelMaster
          .getChannelActor(peerId)
          .map(_.send(action))
      }
      case "sendpay_success" => {}
      case "sendpay_failure" => {}
    }
  }
}
