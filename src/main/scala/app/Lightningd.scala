package app

import java.net.Socket
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

        val rpcAddr =
          s"${req("params")("configuration")("lightning-dir").str}/${req("params")("configuration")("rpc-file").str}"

        Timer.timeout(FiniteDuration(1, "seconds")) { () =>
          System.err.println(s"connect to $rpcAddr")
          UnixSocket.connect(
            rpcAddr,
            ujson.write(
              ujson.Obj(
                "jsonrpc" -> "2.0",
                "id" -> 0,
                "method" -> "getinfo",
                "params" -> ujson.Obj()
              )
            )
          )
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
