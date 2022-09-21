import scala.scalanative.loop.Timer
import scala.scalanative.unsigned.given
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.util.chaining._
import scala.collection.mutable
import scodec.bits.ByteVector
import upickle.default._
import com.softwaremill.quicklens._
import scodec.bits.ByteVector
import scodec.{DecodeResult}
import scoin._
import scoin.ln._
import scoin.hc._

object ChannelMaster {
  import Picklers.given

  val node: NodeInterface = new CLN()
  val database = new Database()
  val preimageCatcher = new BlockchainPreimageCatcher()

  var isReady: Boolean = false
  var temporarySecrets: List[String] = List.empty

  val config = Config
    .fromFile(database.path)
    .getOrElse(Config.defaults)

  val channels = mutable.Map.empty[ByteVector, Channel]
  def getChannel(peerId: ByteVector): Channel =
    channels.getOrElseUpdate(peerId, { new Channel(peerId) })

  val logger = new nlog.Logger {
    override val threshold =
      if ChannelMaster.config.isDev then nlog.Debug else nlog.Info
    override def print(
        level: nlog.Level,
        items: nlog.Items,
        msg: String
    ): Unit = {
      val lvl = {
        val (color, name) = level match {
          case nlog.Debug => (Console.GREEN_B, "DBG")
          case nlog.Info  => (Console.BLUE_B, "INF")
          case nlog.Warn  => (Console.YELLOW_B, "WRN")
          case nlog.Err   => (Console.RED_B, "ERR")
        }
        Console.BLACK + color + "[" + name + "]" + Console.RESET
      }
      val its =
        items
          .map {
            case (l: String, it: Any) =>
              Console.BLUE + s"$l=" + Console.RESET + s"$it"
            case it =>
              Console.YELLOW + "{" + Console.RESET
                + s"$it"
                + Console.YELLOW + "}" + Console.RESET
          }
          .mkString(" ")
      val sep =
        if items.size > 0 && msg.size > 0 then
          Console.YELLOW + " ~ " + Console.RESET
        else ""

      val text = s"$lvl ${msg}${sep}${its}"

      if (node.isInstanceOf[CLN] && !ChannelMaster.config.isDev) {
        System.out.println(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "method" -> "log",
            "params" -> ujson.Obj(
              "message" -> text
            )
          )
        )
      } else {
        System.err.println(
          Console.BOLD + "> " +
            Console.BLUE + "poncho" + Console.RESET +
            Console.GREEN + Console.BOLD + ": " + Console.RESET +
            text
        )
      }
    }
  }

  def log(message: String): Unit = logger.debug.msg(message)

  logger.info
    .item("channels", database.data.channels.size)
    .item(
      "errored-channels",
      database.data.channels.values.filter(_.localErrors.size > 0).size
    )
    .item(
      "clients-total-balance",
      database.data.channels.values
        .filter(_.lcss.isHost)
        .map(_.lcss.remoteBalanceMsat)
        .fold(MilliSatoshi(0))(_ + _)
    )
    .msg(s"starting poncho.")
  logger.info.msg(s"using config $config.")

  var currentBlock = BlockHeight(0L)
  def currentBlockDay: Long = currentBlock.toLong / 144
  def updateCurrentBlock() = {
    node
      .getCurrentBlock()
      .onComplete {
        case Success(block) => {
          if (block > this.currentBlock) {
            this.currentBlock = block
            logger.info.item(block).msg("updated current block")

            this.channels.values.foreach(_.onBlockUpdated(block))
            this.preimageCatcher.onBlockUpdated(block)
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
        case Success(chainHash) => {
          this.chainHash = chainHash
          isReady = true
        }
        case Failure(err) =>
          logger.err.item(err).msg("failed to get chainhash")
      }
  }

  def run(): Unit = {
    node.main(() => {
      // wait for this callback so we know the RPC is ready and we can call these things
      setChainHash()
      updateCurrentBlock()

      Timer.repeat(1.minutes) { () =>
        updateCurrentBlock()
      }

      // as the node starts c-lightning will reply the htlc_accepted HTLCs on us,
      // but we must do the same with the hosted-to-hosted HTLCs that are pending manually
      Timer.timeout(10.seconds) { () =>
        for {
          // ~ for all channels
          (sourcePeerId, sourceChannelData) <- database.data.channels
          // ~ get all that have incoming HTLCs in-flight
          in <- sourceChannelData.lcss.incomingHtlcs
          sourcePeer = this.getChannel(sourcePeerId)
          // ~ from these find all that are outgoing to other channels using data from our database
          out <- database.data.htlcForwards.get(
            HtlcIdentifier(sourcePeer.shortChannelId, in.id)
          )
          // ~ parse outgoing data from the onion
          (scid, amount, cltvExpiry, nextOnion) <- Utils.getOutgoingData(
            this.node.privateKey,
            in
          )
          // ~ use that to get the target channel parameters
          (targetPeerId, targetChannelData) <- database.data.channels.find(
            (p, _) =>
              HostedChannelHelpers.getShortChannelId(
                this.node.publicKey.value,
                p
              ) == scid
          )
          // ~ get/instantiate the target channel
          targetPeer = this.getChannel(targetPeerId)
          // ~ and send the HTLC to it
          _ = targetPeer
            .addHtlc(
              htlcIn = HtlcIdentifier(
                HostedChannelHelpers
                  .getShortChannelId(this.node.publicKey.value, sourcePeerId),
                in.id
              ),
              paymentHash = in.paymentHash,
              amountIn = in.amountMsat,
              amountOut = amount,
              cltvIn = in.cltvExpiry,
              cltvOut = cltvExpiry,
              nextOnion = nextOnion
            )
            .foreach { status =>
              sourcePeer.gotPaymentResult(in.id, status)
            }
        } yield ()
      }
    })
  }

  def cleanupPreimages(): Unit =
    database.update { data =>
      // remove any preimages we were keeping track of but are now committed
      // we don't care about these preimages anymore since we have the signature of the peer
      // in the updated state, which is much more powerful
      data
        .modify(_.preimages)
        .using(preimages => {
          // ~ get the hashes of all payments in-flight accross all hosted channels
          val inflightHashes = channels
            .map((_, chan) => chan.currentData.lcss)
            .filter(!_.isEmpty)
            .flatMap(lcss =>
              (lcss.incomingHtlcs ++ lcss.outgoingHtlcs).map(_.paymentHash)
            )
            .toSet

          // ~ get the hashes we have in our current preimages list that are not relevant
          //   i.e. are not in the in-flight list from above
          val irrelevantPreimages =
            preimages.filterNot((hash, _) => inflightHashes.contains(hash))
          val irrelevantHashes = irrelevantPreimages.map((hash, _) => hash)

          // ~ delete these as we don't care about them anymore
          preimages -- irrelevantHashes
        })
    }
}
