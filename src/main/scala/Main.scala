import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.scalanative.loop.{Poll, Timer}
import scala.scalanative.unsigned.given
import scala.concurrent.duration.FiniteDuration
import upickle.default._

import codecs.ByteVector32
import codecs.{InitHostedChannel, MilliSatoshi}
import codecs.CltvExpiryDelta

case class Config(
    cltvExpiryDelta: CltvExpiryDelta,
    feeBase: MilliSatoshi,
    feeProportionalMillionths: Long
)

object Main {
  import Picklers.given
  val isDev = true
  val node = new CLN()

  log(s"database is at: ${Database.path}")
  log(s"starting with data: ${write(Database.data)}")

  def main(args: Array[String]): Unit = {
    node.main()
  }

  val ourInit = InitHostedChannel(
    maxHtlcValueInFlightMsat = 100000000L.toULong,
    htlcMinimumMsat = MilliSatoshi(1000L),
    maxAcceptedHtlcs = 12,
    channelCapacityMsat = MilliSatoshi(100000000L),
    initialClientBalanceMsat = MilliSatoshi(0)
  )
  val config = Config(
    cltvExpiryDelta = CltvExpiryDelta(144),
    feeBase = MilliSatoshi(1000L),
    feeProportionalMillionths = 1000L
  )

  var currentBlockDay = 0L
  def getCurrentBlockDay() = {
    node
      .getCurrentBlockDay()
      .onComplete {
        case Success(blockday) => {
          currentBlockDay = blockday
          log(s"got current blockday: $blockday")
        }
        case Failure(err) => log(s"failed to get current blockday: $err")
      }
  }
  getCurrentBlockDay()
  Timer.repeat(FiniteDuration(1, scala.concurrent.duration.HOURS))(
    getCurrentBlockDay
  )

  var chainHash = ByteVector32.Zeroes
  node
    .getChainHash()
    .onComplete {
      case Success(chainHash) => {
        Main.chainHash = chainHash
        log(s"got chain hash: $chainHash")
      }
      case Failure(err) => log(s"failed to get chainhash: $err")
    }

  def log(message: String): Unit = {
    if (node.isInstanceOf[CLN] && !Main.isDev) {
      System.out.println(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "method" -> "log",
          "params" -> ujson.Obj(
            "message" -> message
          )
        )
      )
    } else {
      System.err.println(
        Console.BOLD + "> " +
          Console.BLUE + "poncho" + Console.RESET +
          Console.BOLD + ": " + Console.RESET +
          Console.GREEN + message + Console.RESET
      )
    }
  }
}
