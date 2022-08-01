import scala.scalanative.loop.{Poll, Timer}
import scala.scalanative.unsigned.given
import scala.concurrent.duration.FiniteDuration
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

import codecs._
import crypto.Crypto

class ChannelMaster { self =>
  import Picklers.given

  val node: NodeInterface = new CLN(self)
  val database = new Database()
  var isReady: Boolean = false

  val config = Config
    .fromFile(database.path)
    .getOrElse {
      logger.warn.msg("failed to read config.json, will use the defaults")
      Config.defaults
    }

  val channels = mutable.Map.empty[ByteVector, Channel]
  def getChannel(peerId: ByteVector): Channel =
    channels.getOrElseUpdate(peerId, { new Channel(self, peerId) })

  val logger: nlog.Logger = {
    def printer(message: String): Unit =
      if (node.isInstanceOf[CLN] && !self.config.isDev) {
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

    new nlog.Logger(
      printer = printer,
      level = if self.config.isDev then nlog.Debug else nlog.Info
    )
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
          if (block > self.currentBlock) {
            self.currentBlock = block
            logger.info.item(block).msg("updated current block")

            self.channels.values.foreach(_.onBlockUpdated(block))
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
          self.chainHash = chainHash
          isReady = true
        }
        case Failure(err) => logger.err.item(err).msg("failed to get chainhash")
      }
  }

  def run(isTest: Boolean = false): Unit = {
    node.main(() => {
      // wait for this callback so we know the RPC is ready and we can call these things
      setChainHash()
      updateCurrentBlock()

      if (!isTest) {
        Timer.repeat(FiniteDuration(1, "minutes")) { () =>
          updateCurrentBlock()
        }
      }

      // as the node starts c-lightning will reply the htlc_accepted HTLCs on us,
      // but we must do the same with the hosted-to-hosted HTLCs that are pending manually
      Timer.timeout(FiniteDuration(10, "seconds")) { () =>
        for {
          // ~ for all channels
          (sourcePeerId, sourceChannelData) <- database.data.channels
          // ~ get all that have incoming HTLCs in-flight
          in <- sourceChannelData.lcss.incomingHtlcs
          sourcePeer = self.getChannel(sourcePeerId)
          // ~ from these find all that are outgoing to other channels using data from our database
          out <- database.data.htlcForwards.get(
            HtlcIdentifier(sourcePeer.shortChannelId, in.id)
          )
          // ~ parse outgoing data from the onion
          (scid, amount, cltvExpiry, nextOnion) <- Utils.getOutgoingData(
            self.node.privateKey,
            in
          )
          // ~ use that to get the target channel parameters
          (targetPeerId, targetChannelData) <- database.data.channels.find(
            (p, _) => Utils.getShortChannelId(self.node.publicKey, p) == scid
          )
          // ~ get/instantiate the target channel
          targetPeer = self.getChannel(targetPeerId)
          // ~ and send the HTLC to it
          _ = targetPeer
            .addHtlc(
              incoming = HtlcIdentifier(
                Utils.getShortChannelId(self.node.publicKey, sourcePeerId),
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

  def channelJSON(chan: (ByteVector, Channel)): ujson.Obj = {
    val mapHtlc = (htlc: UpdateAddHtlc) => {
      ujson.Obj(
        "id" -> htlc.id.toLong.toInt,
        "amount" -> htlc.amountMsat.toLong.toInt,
        "hash" -> htlc.paymentHash.toHex,
        "cltv" -> htlc.cltvExpiry.toLong.toInt,
        "released_uncommitted_preimage" -> database.data.preimages
          .get(htlc.paymentHash)
          .map(_.toHex)
      )
    }

    val (peerId, channel) = chan

    ujson.Obj(
      "peer_id" -> peerId.toHex,
      "channel_id" -> Utils.getChannelId(self.node.publicKey, peerId).toHex,
      "short_channel_id" -> Utils
        .getShortChannelId(self.node.publicKey, peerId)
        .toString,
      "status" -> channel.status.getClass.getSimpleName.toLowerCase,
      "data" -> channel.currentData.lcss.pipe(lcss =>
        ujson.Obj(
          "is_host" -> lcss.isHost,
          "blockday" -> lcss.blockDay.toInt,
          "local_errors" -> channel.currentData.localErrors
            .map(dtlerr => ujson.Str(dtlerr.toString))
            .pipe(v => if v.isEmpty then v else null),
          "remote_errors" -> channel.currentData.remoteErrors
            .map(err => ujson.Str(err.toString))
            .pipe(v => if v.isEmpty then v else null),
          "local_updates" -> lcss.localUpdates,
          "remote_updates" -> lcss.remoteUpdates,
          "balance" -> ujson.Obj(
            "total" -> lcss.initHostedChannel.channelCapacityMsat.toLong,
            "local" -> lcss.localBalanceMsat.toLong,
            "remote" -> lcss.remoteBalanceMsat.toLong
          ),
          "incoming_htlcs" -> ujson.Arr.from(
            lcss.incomingHtlcs.map(mapHtlc)
          ),
          "outgoing_htlcs" -> ujson.Arr.from(
            lcss.outgoingHtlcs.map(mapHtlc)
          ),
          "uncommitted_updates" -> channel.state.uncommittedUpdates.size
        )
      )
    )
  }
}
