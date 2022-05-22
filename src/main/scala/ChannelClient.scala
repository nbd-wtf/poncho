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

class ChannelClient(peerId: String)(implicit
    ac: castor.Context
) extends castor.SimpleActor[HostedServerMessage] {
  sealed trait State
  case class Inactive() extends State

  var state: State =
    Database.data.channels.get(peerId) match {
      case _ => Inactive()
    }

  def stay = state

  def sendMessage: HostedClientMessage => Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, _)

  def run(msg: HostedServerMessage): Unit = {
    state = (state, msg) match {
      case _ => stay
    }
  }
}
