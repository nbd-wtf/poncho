package app

import ujson._

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
        System.err.println("got init")
        System.out.println(
          ujson.write(
            ujson.Obj(
              "jsonrpc" -> "2.0",
              "id" -> req("id").num,
              "result" -> ujson.Obj()
            )
          )
        )
      }
      case "htlc_accepted"   => {}
      case "custommsg"       => {}
      case "sendpay_success" => {}
      case "sendpay_failure" => {}
      case "shutdown"        => {}
    }
  }
}
