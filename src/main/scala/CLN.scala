import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.Poll
import scala.concurrent.Future
import scala.util.{Failure, Success}
import secp256k1.Keys
import sha256.Hkdf
import ujson._

import UnixSocket.UnixSocket
import scodec.bits.ByteVector
import scodec.codecs.uint16
import codecs.HostedChannelCodecs._
import codecs._
import secp256k1.Secp256k1

class CLN {
  var rpcAddr: String = ""
  var hsmSecret: Path = Paths.get("")

  def rpc(method: String, params: ujson.Obj): Future[ujson.Value] = {
    if (rpcAddr == "") {
      return Future.failed(Exception("rpc address is not known yet"))
    }

    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> 0,
          "method" -> method,
          "params" -> params
        )
      )

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(ujson.read(_))
      .flatMap(read =>
        if (read.obj.contains("error")) {
          Future.failed(Exception(read("error")("message").str))
        } else {
          Future.successful(read("result"))
        }
      )
  }

  def answer(req: ujson.Value)(result: ujson.Value): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "result" -> result
        )
      )
    )
  }

  def getPrivateKey(): ByteVector32 = {
    val salt = Array[UByte](0.toByte.toUByte)
    val info = "nodeid".getBytes().map(_.toUByte)
    val secret = Files.readAllBytes(hsmSecret).map(_.toUByte)

    val sk = Hkdf.hkdf(salt, secret, info, 32)
    ByteVector32(ByteVector(sk.map(_.toByte)))
  }

  lazy val ourPubKey: ByteVector = ByteVector(
    Keys
      .loadPrivateKey(getPrivateKey().bytes.toArray.map(_.toUByte))
      .toOption
      .get
      .publicKey()
      ._1
      .map(_.toByte)
  )

  def getChainHash(): Future[ByteVector32] =
    rpc("getchaininfo", ujson.Obj())
      .map(info => {
        println(s"getchaininfo: ${info.toString}")
        info
      })
      .map(_("chain").str)
      .map({
        case "main" =>
          "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
        case "test" =>
          "43497fd7f826957108f4a30fd9cec3aeba79972084e90ead01ea330900000000"
        case "signet" =>
          "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
        case "regtest" =>
          "b291211d4bb2b7e1b7a4758225e69e50104091a637213d033295c010f55ffb18"
        case chain =>
          throw IllegalArgumentException(s"unknown chain name '$chain'")
      })
      .map(ByteVector32.fromValidHex(_))

  def getCurrentBlockDay(): Future[Long] = {
    rpc("getchaininfo", ujson.Obj())
      .map(info => {
        println(s"getchaininfo: ${info.toString}")
        info
      })
      .map(_("headercount").num.toLong / 144)
  }

  def sendCustomMessage(
      peerId: String,
      tag: Int,
      message: ByteVector
  ): Unit = {
    val tagHex = uint16.encode(tag).toOption.get.toByteVector.toHex
    val lengthHex = uint16
      .encode(message.size.toInt)
      .toOption
      .get
      .toByteVector
      .toHex
    val msg = tagHex + lengthHex + message.toHex

    Main.log(s"sending [$tag] $msg to $peerId")

    rpc(
      "sendcustommsg",
      ujson.Obj(
        "node_id" -> peerId,
        "msg" -> msg
      )
    )
      .onComplete {
        case Failure(err) => Main.log(s"failed to send custom message: $err")
        case _            => {}
      }
  }

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val data = req("params")
    val reply = answer(req)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> false,
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "custommsg"),
              ujson.Obj("name" -> "htlc_accepted")
            ),
            "rpcmethods" -> ujson.Arr(),
            "notifications" -> ujson.Arr(),
            "featurebits" -> ujson.Obj(
              // "init" -> 32972 /* hosted_channels */ .toHexString,
              // "node" -> 32972 /* hosted_channels */ .toHexString
            )
          )
        )
      case "init" => {
        reply(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> req("id").num,
            "result" -> ujson.Obj()
          )
        )

        val lightningDir = data("configuration")("lightning-dir").str
        rpcAddr = lightningDir + "/" + data("configuration")("rpc-file").str
        hsmSecret = Paths.get(lightningDir + "/hsm_secret")
      }
      case "custommsg" => {
        reply(ujson.Obj("result" -> "continue"))

        val peerId = data("peer_id").str
        val tag = ByteVector
          .fromValidHex(data("payload").str.take(4))
          .toInt(signed = false)
        val payload = ByteVector.fromValidHex(data("payload").str.drop(4))

        Main.log(s"got custommsg [$tag] from $peerId")

        decodeClientMessage(tag, payload).toEither match {
          case Left(err) => Main.log(s"$err")
          case Right(msg) => {
            val peer = ChannelMaster.getChannelActor(peerId)
            peer.send(Recv(msg))
          }
        }
      }
      case "htlc_accepted" => {
        val htlc = data("htlc")
        val onion = data("onion")
        val paymentHash = ByteVector32.fromValidHex(htlc("payment_hash").str)
        val amount = htlc("amount").str.dropRight(4).toInt
        val onionRoutingPacket =
          ByteVector.fromValidHex(onion("next_onion").str)

        (for {
          chandata <- Database.data.channels.values
            .find(_.shortChannelId.toString == onion("short_channel_id").str)
          peer = ChannelMaster.getChannelActor(chandata.peerId.toString)
          msg = UpdateAddHtlc(
            channelId = chandata.channelId,
            id = 0L.toULong,
            amountMsat = MilliSatoshi(amount),
            paymentHash = paymentHash,
            cltvExpiry =
              CltvExpiry(BlockHeight(htlc("cltv_expiry").num.toLong)),
            onionRoutingPacket = onionRoutingPacket
          )
        } yield (peer, msg)) match {
          case Some((peer, msg)) => peer.send(Send(msg))
          case _ => {
            val hash = htlc("payment_hash").str
            val scid = onion("short_channel_id").str
            Main.log(
              s"not handling this htlc: $hash=>scid"
            )
            reply(ujson.Obj("result" -> "continue"))
          }
        }
      }
      case "sendpay_success" => {
        Main.log(s"sendpay_success: $data")
      }
      case "sendpay_failure" => {
        Main.log(s"sendpay_failure: $data")
      }
      case "connect" => {
        val id = data("id").str
        val address = data("address")("address").str
        Main.log(s"$id connected: $address")
      }
      case "disconnect" => {
        val id = data("id").str
        Main.log(s"$id disconnected")
      }
    }
  }

  def main(): Unit = {
    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        System.err.println(Console.BOLD + s"line: ${line}" + Console.RESET)
        handleRPC(line)
      }
    }
  }
}
