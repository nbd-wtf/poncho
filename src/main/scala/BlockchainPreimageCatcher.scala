import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scodec.bits.ByteVector
import scoin._

class BlockchainPreimageCatcher(master: ChannelMaster) {
  def onBlockUpdated(height: BlockHeight): Unit = {
    var localLogger = master.logger.attach
      .item("block", height.toInt)
      .logger()

    master.node
      .getBlockByHeight(height)
      .onComplete {
        case Success(block) => {
          localLogger.debug.msg("inspecting raw block for preimages")

          block.tx
            .foreach { tx =>
              val preimages = extractPreimages(tx)
              preimages.foreach { preimage =>
                // we've found a preimage that someone has published.
                val preimageLogger = localLogger.attach
                  .item("preimage", preimage)
                  .item("txid", tx.txid.toHex)
                  .logger()
                preimageLogger.debug.msg("found a preimage from an OP_RETURN")

                // is it for a pending outgoing HTLC in one of our HCs?
                // (we must use filter because there could be more than one in case of MPP)
                master.database.data.channels
                  .flatMap { case (peer, chandata) =>
                    chandata.lcss.outgoingHtlcs.map(htlc => (peer, htlc))
                  }
                  .filter { case (peer, htlc) =>
                    htlc.paymentHash == scoin.Crypto.sha256(preimage)
                  }
                  .foreach { case (peer, htlc) =>
                    // we've found an outgoing htlc that matches this preimage
                    // so now we act as if we had received an update_fulfill_htlc
                    // and resolve it upwards
                    preimageLogger.warn
                      .item("htlc", htlc)
                      .item("peer", peer)
                      .msg("a payment was resolved with an OP_RETURN preimage")

                    // this will protect our money, but the hosted channel will
                    // still error when this outgoing HTLC expire -- which is ok because resorting
                    // to OP_RETURN is an indication that something is wrong.
                    master
                      .getChannel(peer)
                      .provideHtlcResult(htlc.id, Some(Right(preimage)))

                  }
              }
            }
        }
        case Failure(err) =>
          localLogger.err
            .item("err", err)
            .item("height", height.toInt)
            .msg("failed to get raw block")
      }
  }

  def extractPreimages(tx: Transaction): Seq[ByteVector32] =
    tx.txOut
      .map(out => Try(Script.parse(out.publicKeyScript)))
      .flatMap {
        case Success(
              OP_RETURN :: OP_PUSHDATA(p1, 32) :: OP_PUSHDATA(p2, 32) :: Nil
            ) =>
          List(p1, p2)
        case Success(OP_RETURN :: OP_PUSHDATA(p1, 32) :: Nil) =>
          List(p1)
        case _ => List.empty[ByteVector]
      }
      .map(ByteVector32(_))
}
