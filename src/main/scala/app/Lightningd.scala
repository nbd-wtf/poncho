package app

import java.net.Socket
import scala.util.{Failure, Success}
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.loop.Timer
import castor.Context
import ujson._

import UnixSocket.UnixSocket

class LightningdListener()(implicit ac: castor.Context)
    extends castor.SimpleActor[String] {
  def run(input: String): Unit = {
    System.err.println(s"line: ${input}")
    val req = ujson.read(input)
    req("method").str match {
      case "getmanifest" =>
        System.out.println(
          ujson.write(
            ujson.Obj(
              "jsonrpc" -> "2.0",
              "id" -> req("id").num,
              "result" -> ujson.Obj(
                "dynamic" -> false,
                "options" -> ujson.Arr(),
                "subscriptions" -> ujson.Arr(
                  "sendpay_success",
                  "sendpay_failure",
                  "shutdown"
                ),
                "hooks" -> ujson.Arr(
                  ujson.Obj("name" -> "custommsg"),
                  ujson.Obj("name" -> "htlc_accepted")
                ),
                "rpcmethods" -> ujson.Arr(),
                "notifications" -> ujson.Arr(),
                "featurebits" -> ujson.Obj()
              )
            )
          )
        )
      case "init" => {
        System.out.println(
          ujson.write(
            ujson.Obj(
              "jsonrpc" -> "2.0",
              "id" -> req("id").num,
              "result" -> ujson.Obj()
            )
          )
        )

        val rpcAddr: String =
          s"${req("params")("configuration")("lightning-dir").str}/${req("params")("configuration")("rpc-file").str}"

        val payload =
          ujson.write(
            ujson.Obj(
              "jsonrpc" -> "2.0",
              "id" -> 10000,
              "method" -> "getinfo",
              "params" -> ujson.Obj()
            )
          )
        System.err.println(s"connecting to $rpcAddr")
        UnixSocket
          .call(rpcAddr, payload)
          .future
          .onComplete {
            case Success(value) =>
              System.err.println(s"got this result: $value")
            case Failure(err) => System.err.println(s"got this error: $err")
          }
      }
      case "htlc_accepted"   => {}
      case "custommsg"       => {}
      case "sendpay_success" => {}
      case "sendpay_failure" => {}
      case "shutdown"        => {}
    }
  }
}
