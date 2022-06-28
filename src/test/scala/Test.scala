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
  Database.path = Paths.get("/tmp/ponchotest.db")
  Files.deleteIfExists(Database.path)
  Database.loadData()

  val mockNode = new MockingNode(1)
  Main.node = mockNode
  Main.main(Array("oneshot"))

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

  // 1111111...
  val peer1sk = ByteVector32(ByteVector(Array.fill(32)(1.toByte)))
  val peer1pk = Crypto.getPublicKey(peer1sk)
  val channel1 = ChannelMaster.getChannel(peer1pk)

  val tests = Tests {
    test("opening a channel") {
      waitUntil(Main.isReady).map { _ =>
        openChannel()
      }
    }

    test("peer receives a payment") {
      waitUntil(Main.isReady).map { _ =>
        openChannel()
        forwardToPeer()
      }
    }

    test("peer sends a payment") {
      waitUntil(Main.isReady).map { _ =>
        openChannel()
        forwardToPeer()
        forwardFromPeer()
      }
    }

  }

  def openChannel(): Unit = {
    channel1
      .gotPeerMessage(
        InvokeHostedChannel(
          chainHash = ByteVector32(
            ByteVector.fromValidHex(
              "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
            )
          ),
          refundScriptPubKey = refundScriptPubKey,
          secret = ByteVector.empty
        )
      )

    mockNode.mockCustomMessageSent.size ==> 1
    assert(
      mockNode.mockCustomMessageSent.head.isInstanceOf[InitHostedChannel]
    )

    channel1.gotPeerMessage(
      makePeerStateUpdate(peer1sk, 0, 0, MilliSatoshi(0L))
    )

    mockNode.mockCustomMessageSent.size ==> 3
    assert(mockNode.mockCustomMessageSent.head.isInstanceOf[ChannelUpdate])
    assert(
      mockNode.mockCustomMessageSent.drop(1).head.isInstanceOf[StateUpdate]
    )
    assert(channel1.state.openingRefundScriptPubKey == None)
    assert(channel1.state.data.lcss.isDefined)
    assert(channel1.state.lcss.refundScriptPubKey == refundScriptPubKey)
  }

  def forwardToPeer(): Unit = {
    val preimage =
      Crypto.sha256(ByteVector32(ByteVector(Array.fill(32)(8.toByte))))

    // we receive a payment from the outer universe destined to our peer
    channel1.addHTLC(
      incoming = HtlcIdentifier(ShortChannelId(1, 2, 3), 4),
      incomingAmount = MilliSatoshi(88100000L),
      outgoingAmount = MilliSatoshi(88000000L),
      paymentHash = Crypto.sha256(preimage),
      cltvExpiry = CltvExpiry(88),
      nextOnion =
    )

    // we shouldn't forward the payment yet, but wait for the StateUpdate
    mockNode.mockOnionSent.size ==> 0
    mockNode.mockCustomMessageSent.size ==> 3

    channel1
      .gotPeerMessage(
        makePeerStateUpdate(
          peer1sk,
          1,
          2,
          MilliSatoshi(0),
          htlcsToHost = List(htlc)
        )
      )

    // now we should have forwarded and also replied with a StateUpdate
    mockNode.mockOnionSent.size ==> 1
    mockNode.mockCustomMessageSent.size ==> 4
  }

  def forwardFromPeer(): Unit = {
    val preimage =
      Crypto.sha256(ByteVector32(ByteVector(Array.fill(32)(8.toByte))))

    // peer will send a request to forward an HTLC
    val htlc = UpdateAddHtlc(
      channelId = Utils.getChannelId(Main.node.ourPubKey, peer1pk),
      id = 1L.toULong,
      amountMsat = MilliSatoshi(88000000L),
      paymentHash = preimage,
      cltvExpiry = CltvExpiry(88),
      onionRoutingPacket = ByteVector(Array.empty[Byte])
    )

    channel1.gotPeerMessage(htlc)

    // we shouldn't forward the payment yet, but wait for the StateUpdate
    mockNode.mockOnionSent.size ==> 0
    mockNode.mockCustomMessageSent.size ==> 3

    channel1
      .gotPeerMessage(
        makePeerStateUpdate(
          peer1sk,
          1,
          2,
          MilliSatoshi(0),
          htlcsToHost = List(htlc)
        )
      )

    // now we should have forwarded and also replied with a StateUpdate
    mockNode.mockOnionSent.size ==> 1
    mockNode.mockCustomMessageSent.size ==> 4
  }

  def makePeerStateUpdate(
      peersk: ByteVector32,
      localUpdates: Long,
      remoteUpdates: Long,
      localBalance: MilliSatoshi,
      htlcsToPeer: List[UpdateAddHtlc] = List.empty,
      htlcsToHost: List[UpdateAddHtlc] = List.empty
  ): StateUpdate =
    StateUpdate(
      blockDay = Main.currentBlockDay,
      localUpdates = localUpdates,
      remoteUpdates = remoteUpdates,
      localSigOfRemoteLCSS = LastCrossSignedState(
        isHost = false,
        refundScriptPubKey = refundScriptPubKey,
        initHostedChannel = Main.ourInit,
        blockDay = Main.currentBlockDay,
        localBalanceMsat = localBalance,
        remoteBalanceMsat = Main.ourInit.channelCapacityMsat - localBalance,
        localUpdates = localUpdates,
        remoteUpdates = remoteUpdates,
        incomingHtlcs = htlcsToPeer,
        outgoingHtlcs = htlcsToHost,
        localSigOfRemote = ByteVector64.Zeroes,
        remoteSigOfLocal = ByteVector64.Zeroes
      ).withLocalSigOfRemote(peersk).localSigOfRemote
    )
}
