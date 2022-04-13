import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.concurrent.{Future}
import scala.util.{Failure, Success}
import castor.Context
import ujson.*

import UnixSocket.UnixSocket

object Main {
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

  val normalIncoming = new NormalIncomingActor()(castor.Context.Simple.global)
  val hostedIncoming = new HostedIncomingActor()(castor.Context.Simple.global)
  val normalOutgoing = new NormalOutgoingActor()(castor.Context.Simple.global)
  val hostedOutgoing = new HostedOutgoingActor()(castor.Context.Simple.global)

  def main(args: Array[String]): Unit = {
    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        System.err.println(s"line: ${line}")
        val req = ujson.read(line)
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

            rpcAddr =
              s"${req("params")("configuration")("lightning-dir").str}/${req("params")("configuration")("rpc-file").str}"
          }
          case "htlc_accepted"   => {}
          case "custommsg"       => {}
          case "sendpay_success" => {}
          case "sendpay_failure" => {}
          case "shutdown"        => {}
        }
      }
    }
  }
}
