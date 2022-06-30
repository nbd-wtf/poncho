import java.nio.file.{Files, Paths}
import scala.scalanative.unsigned._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import utest._
import scodec.bits._
import scala.scalanative.loop.Timer

import codecs._
import crypto.Crypto

object ChannelTests extends TestSuite {
  def start(id: Integer): ChannelMaster = {
    val dbpath = Paths.get(s"/tmp/ponchotest/$id.db")
    Files.deleteIfExists(dbpath)
    val cm = new ChannelMaster() {
      override val database = new Database(dbpath)
      override val node = new MockingNode(id)
    }
    cm.run(isTest = true)
    cm
  }

  def waitUntil(
      cond: => Boolean,
      promise: Promise[Unit] = Promise[Unit]
  ): Future[Unit] = {
    if (cond)
      promise.success(())
    else
      Timer.timeout(FiniteDuration(10, "millisecond")) { () =>
        waitUntil(cond, promise)
      }
    promise.future
  }

  val refundScriptPubKey = ByteVector(Array[Byte](0, 1, 2, 3))

  val tests = Tests {
    test("opening a channel") {
      val client = start(1)
      val host = start(2)
      val fromClient = client.getChannel(host.node.publicKey)
      val fromHost = host.getChannel(client.node.publicKey)

      (for {
        _ <- waitUntil(client.isReady)
        _ <- waitUntil(host.isReady)
        _ <- fromClient.requestHostedChannel()
        _ <- waitUntil(fromClient.status == Active)
      } yield ()) map { _ =>
        val clientNode = client.node.asInstanceOf[MockingNode]
        clientNode.mockCustomMessageSent.size ==> 2

        assert(fromHost.state.openingRefundScriptPubKey == None)
        assert(fromHost.currentData.lcss.isDefined)
        assert(fromHost.lcssStored.refundScriptPubKey == refundScriptPubKey)
        val hostNode = host.node.asInstanceOf[MockingNode]
        hostNode.mockCustomMessageSent.size ==> 3
        assert(hostNode.mockCustomMessageSent.head.isInstanceOf[ChannelUpdate])
        assert(
          hostNode.mockCustomMessageSent.drop(1).head.isInstanceOf[StateUpdate]
        )

        ()
      }
    }

    test("hc receives a payment") {
      val preimage =
        Crypto.sha256(ByteVector32(ByteVector(Array.fill(32)(8.toByte))))

      val client = start(1)
      val host = start(2)
      val fromClient = client.getChannel(host.node.publicKey)
      val fromHost = host.getChannel(client.node.publicKey)

      (for {
        _ <- waitUntil(client.isReady)
        _ <- waitUntil(host.isReady)
        _ <- fromClient.requestHostedChannel()
        _ <- waitUntil(fromClient.status == Active)

        // a payment comes from the outer universe destined to the hc
        _ <- fromHost.addHTLC(
          incoming = HtlcIdentifier(ShortChannelId("1x1x1"), 4.toULong),
          incomingAmount = MilliSatoshi(88100000L),
          outgoingAmount = MilliSatoshi(88000000L),
          paymentHash = Crypto.sha256(preimage),
          cltvExpiry = CltvExpiry(88),
          nextOnion = ByteVector.view(Array.empty[Byte])
        )
      } yield ()) map { _ =>
        println(fromClient.lcssStored)
        val clientNode = client.node.asInstanceOf[MockingNode]
        println(clientNode.mockCustomMessageSent)

        println(fromHost.lcssStored)
        val hostNode = host.node.asInstanceOf[MockingNode]
        println(hostNode.mockCustomMessageSent.size)
        hostNode.mockCustomMessageSent.size ==> 6
        assert(hostNode.mockCustomMessageSent.head.isInstanceOf[StateUpdate])
        hostNode.mockCustomMessageSent.size ==> 7

        ()
      }
    }
  }
}
