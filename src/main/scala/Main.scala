import scala.util.{Failure, Success}
import scala.scalanative.loop.{Poll, Timer}
import scala.scalanative.unsigned.given
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scodec.bits.ByteVector
import upickle.default._

import codecs.{
  CltvExpiryDelta,
  BlockHeight,
  ByteVector32,
  InitHostedChannel,
  MilliSatoshi
}
import codecs.ShortChannelId

case class Config(
    cltvExpiryDelta: CltvExpiryDelta,
    feeBase: MilliSatoshi,
    feeProportionalMillionths: Long
)

object Main {
  import Picklers.given
  val isDev = true
  val node: NodeInterface = new CLN()

  logger.info.item(Database.path).msg(s"database found")

  def main(args: Array[String]): Unit = {
    node.main(() => {
      // wait for this callback so we know the RPC is ready and we can call these things
      setChainHash()
      updateCurrentBlock()
      Timer.repeat(FiniteDuration(1, "minutes")) { () =>
        updateCurrentBlock()
      }

      // as the node starts c-lightning will reply the htlc_accepted HTLCs on us,
      // but we must do the same with the hosted-to-hosted HTLCs that are pending manually
      for {
        (sourcePeerId, sourceChannelData) <- Database.data.channels
        in <- sourceChannelData.lcss.map(_.incomingHtlcs).getOrElse(List.empty)
        sourcePeer = ChannelMaster.getChannel(sourcePeerId)
        (scid, amount, cltvExpiry, nextOnion) <- Utils.getOutgoingData(in)
        out <- Database.data.htlcForwards.get(
          HtlcIdentifier(sourcePeer.shortChannelId, in.id)
        )
        (targetPeerId, targetChannelData) <- Database.data.channels.find(
          (p, _) => Utils.getShortChannelId(Main.node.ourPubKey, p) == scid
        )
        targetPeer = ChannelMaster.getChannel(targetPeerId)
        _ = targetPeer
          .addHTLC(
            incoming = HtlcIdentifier(
              Utils.getShortChannelId(Main.node.ourPubKey, sourcePeerId),
              in.id
            ),
            incomingAmount = in.amountMsat,
            outgoingAmount = amount,
            paymentHash = in.paymentHash,
            cltvExpiry = cltvExpiry,
            nextOnion = nextOnion
          )
          .foreach { status =>
            sourcePeer.gotPaymentResult(in.id, status)
          }
      } yield ()
    })
  }

  val ourInit = InitHostedChannel(
    maxHtlcValueInFlightMsat = 100000000L.toULong,
    htlcMinimumMsat = MilliSatoshi(1000L),
    maxAcceptedHtlcs = 12,
    channelCapacityMsat = MilliSatoshi(100000000L),
    initialClientBalanceMsat = MilliSatoshi(0)
  )
  val config = Config(
    cltvExpiryDelta = CltvExpiryDelta(143),
    feeBase = MilliSatoshi(1000L),
    feeProportionalMillionths = 1000L
  )

  var currentBlock = BlockHeight(0L)
  def currentBlockDay: Long = currentBlock.toLong / 144
  def updateCurrentBlock() = {
    node
      .getCurrentBlock()
      .onComplete {
        case Success(block) => {
          if (block > Main.currentBlock) {
            Main.currentBlock = block
            logger.info.item(block).msg("updated current block")

            ChannelMaster.channels.values.foreach(_.onBlockUpdated(block))
          }
        }
        case Failure(err) =>
          logger.warn.item(err).msg("failed to get current blockday")
      }
  }

  var chainHash = ByteVector32.Zeroes
  def setChainHash() = {
    node
      .getChainHash()
      .onComplete {
        case Success(chainHash) => Main.chainHash = chainHash
        case Failure(err) => logger.err.item(err).msg("failed to get chainhash")
      }
  }

  def log(message: String): Unit = logger.debug.msg(message)

  def logger: nlog.Logger = {
    def printer(message: String): Unit =
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

    new nlog.Logger(printer = printer)
  }
}
