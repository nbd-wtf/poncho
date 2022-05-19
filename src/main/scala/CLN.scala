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
import scodec.bits.ByteVector
import scodec.codecs.uint16

import unixsocket.UnixSocket
import crypto.{PublicKey, PrivateKey}
import codecs.HostedChannelCodecs._
import codecs._
import secp256k1.Secp256k1

class CLN {
  private var initCallback = () => {}
  private var rpcAddr: String = ""
  private var hsmSecret: Path = Paths.get("")
  private var nextId = 0

  def rpc(method: String, params: ujson.Obj): Future[ujson.Value] = {
    if (rpcAddr == "") {
      return Future.failed(Exception("rpc address is not known yet"))
    }

    nextId += 1

    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> nextId,
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

  def answer(req: ujson.Value)(errorMessage: String): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "error" -> ujson.Obj(
            "message" -> errorMessage
          )
        )
      )
    )
  }

  def getPrivateKey(): PrivateKey = {
    val salt = Array[UByte](0.toByte.toUByte)
    val info = "nodeid".getBytes().map(_.toUByte)
    val secret = Files.readAllBytes(hsmSecret).map(_.toUByte)

    val sk = Hkdf.hkdf(salt, secret, info, 32)
    ByteVector32(ByteVector(sk.map(_.toByte)))
  }

  lazy val ourPubKey: PublicKey = ByteVector(
    Keys
      .loadPrivateKey(getPrivateKey().bytes.toArray.map(_.toUByte))
      .toOption
      .get
      .publicKey()
      ._1
      .map(_.toByte)
  )

  def getChainHash(): Future[ByteVector32] =
    rpc("getinfo", ujson.Obj())
      .map(_("network").str)
      .map({
        case "bitcoin" =>
          "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000"
        case "testnet" =>
          "43497fd7f826957108f4a30fd9cec3aeba79972084e90ead01ea330900000000"
        case "signet" =>
          "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"
        case "regtest" =>
          "b291211d4bb2b7e1b7a4758225e69e50104091a637213d033295c010f55ffb18"
        case chain =>
          throw IllegalArgumentException(s"unknown chain name '$chain'")
      })
      .map(ByteVector32.fromValidHex(_))

  def getCurrentBlockDay(): Future[Long] =
    rpc("getchaininfo", ujson.Obj())
      .map(_("headercount").num.toLong / 144)

  def sendCustomMessage(
      peerId: String,
      message: HostedServerMessage | HostedClientMessage,
      failureCallback: () => Unit = () => {}
  ): Unit = {
    val (tag, encoded) = message match {
      case m: HostedServerMessage => encodeServerMessage(m)
      case m: HostedClientMessage => encodeClientMessage(m)
    }
    val tagHex = uint16.encode(tag).toOption.get.toByteVector.toHex
    val lengthHex = uint16
      .encode(encoded.size.toInt)
      .toOption
      .get
      .toByteVector
      .toHex
    val payload = tagHex + lengthHex + encoded.toHex

    Main.log(s"  ::> sending $message --> $peerId")

    rpc(
      "sendcustommsg",
      ujson.Obj(
        "node_id" -> peerId,
        "msg" -> payload
      )
    )
      .onComplete {
        case Failure(err) => {
          Main.log(s"failed to send custom message: $err")
          failureCallback()
        }
        case _ => {}
      }
  }

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val data = req("params")
    def reply(result: ujson.Obj) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

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
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "hc-override",
                "usage" -> "peerid msatoshi",
                "description" -> "Propose overriding the state of the channel with {peerid} with the next local balance being equal to {msatoshi}."
              )
            ),
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

        initCallback()
      }
      case "custommsg" => {
        reply(ujson.Obj("result" -> "continue"))

        val peerId = data("peer_id").str
        val body = data("payload").str
        val tag = ByteVector
          .fromValidHex(body.take(4))
          .toInt(signed = false)
        val payload =
          ByteVector.fromValidHex(
            body
              .drop(4 /* tag */ )
              .drop(4 /* length */ )
          )

        decodeClientMessage(tag, payload).toEither match {
          case Left(err) => Main.log(s"$err")
          case Right(msg) => {
            val peer = ChannelMaster.getChannelActor(peerId)
            peer.send(msg)
          }
        }
      }
      case "htlc_accepted" => {
        val htlc = data("htlc")
        val onion = data("onion")
        val scid = ShortChannelId(onion("short_channel_id").str)
        val hash = ByteVector32.fromValidHex(htlc("payment_hash").str)
        val amount = onion("forward_amount").str.dropRight(4).toInt
        val cltv = CltvExpiry(
          BlockHeight(onion("outgoing_cltv_value").num.toLong)
        )
        val nextOnion = ByteVector.fromValidHex(onion("next_onion").str)

        Database.data.channels
          .find((peerId: String, chandata: ChannelData) =>
            ChannelMaster.getShortChannelId(peerId) == scid
          ) match {
          case Some((peerId, chandata)) if chandata.isActive => {
            val peer = ChannelMaster.getChannelActor(peerId)
            peer
              .addHTLC(
                UpdateAddHtlc(
                  channelId = ChannelMaster.getChannelId(peerId),
                  id = 0L.toULong,
                  amountMsat = MilliSatoshi(amount),
                  paymentHash = hash,
                  cltvExpiry = cltv,
                  onionRoutingPacket = nextOnion
                )
              )
              .future
              .foreach {
                case Some(Right(preimage)) => {
                  Main.log(s"[htlc] channel $scid succeed in handling $hash")
                  reply(
                    ujson
                      .Obj(
                        "result" -> "resolve",
                        "payment_key" -> preimage.toString
                      )
                  )
                }
                case Some(Left(FailureOnion(onion))) => {
                  Main.log(s"[htlc] channel $scid failed $hash")
                  reply(
                    ujson.Obj(
                      "result" -> "fail",
                      "failure_onion" -> onion.toString
                    )
                  )
                }
                case Some(Left(FailureCode(code))) => {
                  Main.log(s"[htlc] we've failed $hash for channel $scid")
                  reply(
                    ujson.Obj(
                      "result" -> "fail",
                      "failure_message" -> code
                    )
                  )
                }
                case None => {
                  Main.log(
                    s"[htlc] channel $scid decided to not handle $hash"
                  )
                  reply(ujson.Obj("result" -> "continue"))
                }
              }
          }
          case Some((_, chandata)) => {
            Main.log(
              s"[htlc] can't assign $hash to $scid as that channel is inactive"
            )
            reply(ujson.Obj("result" -> "continue"))
          }
          case None => {
            Main.log(
              s"[htlc] can't assign $hash to $scid as that channel doesn't exist"
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

      // custom rpc methods
      case "hc-override" => {
        val params = data match {
          case _: ujson.Obj =>
            Some((data("peerid").strOpt, data("msatoshi").numOpt))
          case _: ujson.Arr =>
            Some((data(0).strOpt, data(0).numOpt))
          case _ => None
        } match {
          case Some(Some(peerId), Some(msatoshi)) => {
            ChannelMaster
              .getChannelActor(peerId)
              .stateOverride(MilliSatoshi(msatoshi.toLong))
          }
          case _ => {
            replyError("invalid parameters")
          }
        }
      }
    }
  }

  def main(onInit: () => Unit): Unit = {
    initCallback = onInit

    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        System.err.println(Console.BOLD + s"line: ${line}" + Console.RESET)
        handleRPC(line)
      }
    }
  }
}
