import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}

import codecs._
import crypto.Crypto
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import scodec.bits.ByteVector
import scodec.codecs._

class ChannelClient(peerId: ByteVector)(implicit
    ac: castor.Context
) extends Channel[HostedServerMessage, HostedClientMessage](peerId) {
  case class Inactive() extends State

  var state: State =
    Database.data.channels.get(peerId) match {
      case _ => Inactive()
    }

  def run(msg: HostedServerMessage): Unit = {
    state = (state, msg) match {
      case _ => stay
    }
  }

  def addHTLC(
      incoming: HtlcIdentifier,
      incomingAmount: MilliSatoshi,
      outgoingAmount: MilliSatoshi,
      paymentHash: ByteVector32,
      cltvExpiry: CltvExpiry,
      nextOnion: ByteVector
  ): Future[PaymentStatus] = Future { None }

  def gotPaymentResult(
      htlcId: ULong,
      status: PaymentStatus
  ): Unit = {}

  def onBlockUpdated(block: BlockHeight): Unit = {}
}
