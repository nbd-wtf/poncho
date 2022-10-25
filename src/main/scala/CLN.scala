import java.nio.file.{StandardOpenOption, Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}
import scala.util.chaining._
import scala.util.control.Breaks._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.unsigned._
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import scala.util.{Failure, Success}
import scodec.bits.{ByteVector, BitVector}
import scodec.codecs.uint16
import io.circe.{Printer => _, _}
import io.circe.parser.{parse, decode}
import io.circe.syntax._
import unixsocket.UnixSocket
import scoin._
import scoin.ln._
import scoin.hc._
import scoin.hc.HostedChannelCodecs._
import scoin.Crypto.{PublicKey, PrivateKey}

class CLN() extends NodeInterface {
  import Picklers.given

  private var initCallback = () => {}
  private var rpcAddr: String = ""
  private var hsmSecret: Path = Paths.get("")
  private var nextId = 0
  private var onStartup = true
  private val peersUsingBrokenEncoding =
    scala.collection.mutable.Set.empty[ByteVector]

  Timer.timeout(10.seconds) { () => onStartup = false }

  def rpc(
      method: String,
      params: JsonObject = JsonObject.empty
  ): Future[Json] = {
    if (rpcAddr == "") {
      return Future.failed(PonchoException("rpc address is not known yet"))
    }

    nextId += 1

    val payload = Json
      .obj(
        "jsonrpc" := "2.0",
        "id" := nextId,
        "method" := method,
        "params" := params
      )
      .noSpaces

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(parse(_))
      .flatMap {
        case Right(o: Json)
            if o.hcursor
              .downField("result")
              .focus
              .isDefined =>
          Future.successful(o.hcursor.downField("result").focus.get)
        case Right(o: Json) =>
          Future.failed(
            PonchoException(
              o.hcursor.downField("error").get[String]("message").toOption.get
            )
          )
        case Left(err) =>
          Future.failed(err.underlying)
      }
  }

  def answer(req: Json)(result: Json): Unit = {
    System.out.println(
      Json
        .obj(
          "jsonrpc" := "2.0",
          "id" := req.hcursor.downField("id").focus.get,
          "result" := result
        )
        .noSpaces
    )
  }

  def answer(req: Json)(errorMessage: String): Unit = {
    System.out.println(
      Json
        .obj(
          "jsonrpc" := "2.0",
          "id" := req.hcursor.downField("id").focus.get,
          "error" := Json.obj("message" := errorMessage)
        )
        .noSpaces
    )
  }

  lazy val privateKey: PrivateKey = {
    val salt = Array[UByte](0.toByte.toUByte)
    val info = "nodeid".getBytes().map(_.toUByte)
    val secret = Files.readAllBytes(hsmSecret).map(_.toUByte)

    val sk = hkdf256.hkdf(salt, secret, info, 32)
    PrivateKey(ByteVector32(ByteVector(sk.map(_.toByte))))
  }

  lazy val publicKey: PublicKey = privateKey.publicKey

  def getChainHash(): Future[ByteVector32] =
    rpc("getinfo")
      .map(_.hcursor.get[String]("network"))
      .map({
        case Right("bitcoin") => Block.LivenetGenesisBlock.hash.toHex
        case Right("testnet") => Block.TestnetGenesisBlock.hash.toHex
        case Right("signet")  => Block.SignetGenesisBlock.hash.toHex
        case Right("regtest") => Block.RegtestGenesisBlock.hash.toHex
        case chain =>
          throw IllegalArgumentException(s"bad chain '$chain'")
      })
      .map(ByteVector32.fromValidHex(_))

  def getAddress(): Future[String] =
    rpc("newaddr").map(_.hcursor.get[String]("bech32").toTry.get)

  def getCurrentBlock(): Future[BlockHeight] =
    rpc("getchaininfo").map(info =>
      BlockHeight(info.hcursor.get[Long]("headercount").toTry.get)
    )

  def getBlockByHeight(height: BlockHeight): Future[Block] =
    rpc("getrawblockbyheight", JsonObject("height" := height.toInt))
      .flatMap(
        _.hcursor
          .get[String]("block")
          .map(hex => Future(Block.read(hex)))
          .toTry
          .get
      )

  def inspectOutgoingPayment(
      identifier: HtlcIdentifier,
      paymentHash: ByteVector32
  ): Future[PaymentStatus] =
    rpc("listsendpays", JsonObject("payment_hash" := paymentHash.toHex))
      .map(
        _.hcursor
          .downField("payments")
          .focus
          .get
          .asArray
          .get
          .filter(_.hcursor.downField("label").focus.isDefined)
          .filter(
            // use a filter because there may be multiple sendpays with the
            //   same hash and label
            p =>
              decode[(String, Long)](p.hcursor.get[String]("label").toTry.get)
                .map(_ == (identifier.scid.toString, identifier.id.toLong))
                .getOrElse(false)
          )
          .pipe(toStatus(_))
      )

  private def toStatus(results: Vector[Json]): PaymentStatus =
    if (results.size == 0)
      // no outgoing payments found, this means the payment was never attempted
      Some(Left(None))
    else {
      // we have at least one match
      if results.exists(
          _.hcursor.get[String]("status").toOption == Some("complete")
        )
      then // if at least one result is complete then this is indeed fully complete
        Some(
          Right(
            ByteVector32.fromValidHex(
              results
                .find(
                  _.hcursor.get[String]("status").toOption == Some("complete")
                )
                .get
                .hcursor
                .get[String]("payment_preimage")
                .toTry
                .get
            )
          )
        )
      else if results.exists(
          _.hcursor.get[String]("status").toOption == Some("pending")
        )
      then // if at least one result is pending then this is still pending
        None
      else if (
        results.forall(
          _.hcursor.get[String]("status").toOption == Some("failed")
        )
      )
      then // but if all are failed then we consider it failed
        Some(
          Left(
            results.last // take the last and use its error
              .hcursor
              .pipe(o =>
                o.get[ByteVector]("onionreply")
                  .orElse(o.get[ByteVector]("erroronion"))
              )
              .map(FailureOnion(_))
              .map(Some(_))
              .getOrElse(None)
          )
        )
      else None // we don't know
    }

  def sendCustomMessage(
      peerId: ByteVector,
      message: LightningMessage
  ): Future[Json] = {
    val result = hostedMessageCodec.encode(message).toOption.get.toByteVector

    val payload = if peersUsingBrokenEncoding.contains(peerId) then {
      val tagHex = result.take(2).toHex
      val value = result.drop(2)
      val lengthHex = uint16
        .encode(value.size.toInt)
        .toOption
        .get
        .toByteVector
        .toHex
      tagHex ++ lengthHex ++ value.toHex
    } else result.toHex

    ChannelMaster.log(s"  ::> sending $message --> ${peerId.toHex}")
    rpc(
      "sendcustommsg",
      JsonObject(
        "node_id" := peerId.toHex,
        "msg" := payload
      )
    )
  }

  def sendOnion(
      chan: Channel,
      htlcId: Long,
      paymentHash: ByteVector32,
      firstHop: ShortChannelId,
      amount: MilliSatoshi,
      cltvExpiry: CltvExpiry,
      onion: ByteVector
  ): Unit = {
    var logger = ChannelMaster.logger.attach.item("scid", firstHop).logger()
    val noChannelPaymentResult = Some(
      Left(Some(NormalFailureMessage(UnknownNextPeer)))
    )

    (for {
      funds <- rpc("listfunds")
      blockheight <- this.getCurrentBlock()
    } yield (funds, blockheight))
      .onComplete {
        case Failure(err) =>
          logger.debug.item(err).msg("listfunds/getCurrentBlock call failed")
          chan.gotPaymentResult(htlcId, noChannelPaymentResult)

        case Success((funds, blockheight)) =>
          funds.hcursor
            .downField("channels")
            .as[List[Json]]
            .toTry
            .get
            .find(
              _.hcursor
                .get[String]("short_channel_id")
                .map(_ == firstHop.toString)
                .getOrElse(false)
            )
            .map(_.hcursor.get[String]("peer_id").toTry.get)
            .map(ByteVector.fromValidHex(_)) match {
            case None =>
              logger.debug.msg("we don't know about this channel")
              chan.gotPaymentResult(htlcId, noChannelPaymentResult)
            case Some(targetPeerId) =>
              logger = logger.attach.item("peer", targetPeerId.toHex).logger()

              // lightningd will take whatever we pass here and add to the current block,
              //   so since we already have the final cltv value and not a delta, we subtract
              //   the current block from it and then when lightningd adds we'll get back to the
              //   correct expected cltv
              // we also remove 1 because the world is crazy
              val delay = (cltvExpiry - blockheight).toInt - 1
              logger.debug
                .item("peer", targetPeerId.toHex)
                .item("hash", paymentHash.toHex)
                .item("target-ctlv", cltvExpiry.toLong)
                .item("blockheight", blockheight.toLong)
                .item("delay", delay)
                .msg("calling sendonion")

              rpc(
                "sendonion",
                JsonObject(
                  "first_hop" := Json.obj(
                    "id" := targetPeerId.toHex,
                    "amount_msat" := amount.toLong,
                    "delay" := delay
                  ),
                  "onion" := onion.toHex,
                  "payment_hash" := paymentHash.toHex,
                  "label" :=
                    // json encoded array with these two things
                    (
                      chan.shortChannelId.toString,
                      htlcId
                    ).asJson.noSpaces,
                  "groupid" :=
                    // we need a unique combination of groupid and partid
                    //   so lightningd is happy to accept multiple parts
                    (
                      // the groupid is the hosted channel from which this payment is coming.
                      // this contraption is just so we get an unsigned integer
                      //   that is still fairly unique for this channel and fits in a java Long
                      UInt64(chan.shortChannelId.toLong).toBigInt / 100
                    ).toLong,
                  "partid" :=
                    // here we just use the htlc id since it is already unique per channel
                    htlcId
                )
              )
                .onComplete {
                  case Failure(err) => {
                    logger.info.item(err).msg("sendonion failure")

                    val failure: Option[PaymentFailure] =
                      if err
                          .toString()
                          .contains("WIRE_TEMPORARY_NODE_FAILURE")
                      then Some(NormalFailureMessage(TemporaryNodeFailure))
                      else if err
                          .toString()
                          .contains("WIRE_TEMPORARY_CHANNEL_FAILURE")
                      then Some(NormalFailureMessage(PermanentChannelFailure))
                      else None

                    chan.gotPaymentResult(
                      htlcId,
                      Some(Left(failure))
                    )
                  }
                  case Success(_) => {}
                }
          }
      }
  }

  def handleLine(line: String): Unit = parse(line) match {
    case Left(err) =>
      ChannelMaster.logger.warn
        .item("line", line)
        .item("err", err)
        .msg("failed to read line from CLN")
    case Right(json) => handleRPC(json)
  }

  def handleRPC(req: Json): Unit = {
    val params = req.hcursor.downField("params").focus.get
    def reply(result: Json) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

    req.hcursor.get[String]("method").getOrElse("") match {
      case "getmanifest" =>
        reply(
          Json.obj(
            "dynamic" := false, // custom features can only be set on non-dynamic
            "options" := Json.arr(),
            "subscriptions" := Json.arr(
              "sendpay_success".asJson,
              "sendpay_failure".asJson,
              "connect".asJson,
              "disconnect".asJson
            ),
            "hooks" := Json.arr(
              Json.obj("name" := "custommsg"),
              Json.obj("name" := "htlc_accepted"),
              Json.obj("name" := "rpc_command")
            ),
            "rpcmethods" := Json.arr(
              Json.obj(
                "name" := "parse-lcss",
                "usage" := "peerid last_cross_signed_state_hex",
                "description" := "Parse a hex representation of a last_cross_signed_state as provided by a mobile client."
              ),
              Json.obj(
                "name" := "add-hc-secret",
                "usage" := "secret",
                "description" := ("Adds a {secret} (hex, 32 bytes) to the list of acceptable secrets for when a client invokes a hosted channel. " +
                  "This secret can only be used once. You can add the same secret multiple times so it can be used multiple times. " +
                  "You can also add permanent secrets on the config file.")
              ),
              Json.obj(
                "name" := "remove-hc-secret",
                "usage" := "secret",
                "description" := "Removes a {secret} (hex, 32 bytes) to the list of acceptable secrets for when a client invokes a hosted channel. See also `add-hc-secret`."
              ),
              Json.obj(
                "name" := "hc-list",
                "usage" := "",
                "description" := "Lists all your hosted channels."
              ),
              Json.obj(
                "name" := "hc-channel",
                "usage" := "peerid",
                "description" := "Shows your hosted channel with {peerid} with more details than hc-list."
              ),
              Json.obj(
                "name" := "hc-override",
                "usage" := "peerid msatoshi",
                "description" := "Proposes overriding the state of the channel with {peerid} with the next local balance being equal to {msatoshi}."
              ),
              Json.obj(
                "name" := "hc-resize",
                "usage" := "peerid msatoshi",
                "description" := "Prepares the channel with {peerid} to resize its max capacity up to {msatoshi} (after calling this on the host the client must issue a resize command on its side). Calling it with {msatoshi} set to zero cancels the resize."
              ),
              Json.obj(
                "name" := "hc-request-channel",
                "usage" := "peerid",
                "description" := "Requests a hosted channel from another hosted channel provider (do not use)."
              )
            ),
            "notifications" := Json.arr(),
            "featurebits" := Json.obj(
              "init" := Utils.generateFeatureBits(Set(32973, 257)),
              "node" := Utils.generateFeatureBits(Set(257))
              // "channel" := Utils.generateFeatureBits(Set(32975))
            )
          )
        )
      case "init" => {
        reply(Json.obj())

        val c = params.hcursor
        val lightningDir =
          c.downField("configuration")
            .downField("lightning-dir")
            .as[String]
            .toTry
            .get
        val rpcFile =
          c.downField("configuration")
            .downField("rpc-file")
            .as[String]
            .toTry
            .get
        rpcAddr = Paths.get(lightningDir).resolve(rpcFile).toString()
        hsmSecret = Paths.get(lightningDir).resolve("hsm_secret")

        initCallback()
      }
      case "custommsg" => {
        reply(Json.obj("result" := "continue"))

        val c = params.hcursor
        val peerId = ByteVector.fromValidHex(c.get[String]("peer_id").toTry.get)
        val body = c.get[String]("payload").toTry.get
        val payload: ByteVector = ByteVector.fromValidHex(body)

        val potentialLength =
          uint16.decode(payload.drop(2).take(2).toBitVector).require.value
        val isUsingLegacyLength = payload.drop(4).size == potentialLength

        val msg =
          if isUsingLegacyLength then {
            System.err.println(s"LEGACY ${peerId.toHex}")
            peersUsingBrokenEncoding.add(peerId)
            payload.take(2) ++
              payload.drop(2 /* tag */ + 2 /* length */ )
          } else {
            System.err.println(s"NEW ${peerId.toHex}")
            peersUsingBrokenEncoding.remove(peerId)
            payload
          }

        hostedMessageCodec
          .decode(msg.toBitVector)
          .toTry match {
          case Success(msg) =>
            ChannelMaster.getChannel(peerId).gotPeerMessage(msg.value)
          case Failure(err) =>
            ChannelMaster.logger.debug
              .item(err)
              .item("peer", peerId)
              .item("msg", body)
              .msg("failed to parse client messages")
        }
      }
      case "rpc_command" => {
        // intercept the "pay" command if it is paying to a hosted channel peer
        // send a payment manually in that case
        val c = params.hcursor.downField("rpc_command")

        if (c.get[String]("method").toOption == Some("pay")) {
          val inv = c
            .downField("params")
            .get[String]("bolt11")
            .orElse(c.downField("params").downN(0).as[String])
            .toTry

          System.err.println(c.focus.get)

          // there will always be an invoice
          inv.flatMap(Bolt11Invoice.fromString(_)) match {
            case Failure(err) =>
              ChannelMaster.logger.err
                .item("inv", inv)
                .item("err", err)
                .msg("failed to decode invoice on 'pay'")
              reply(Json.obj("result" := "continue"))
            case Success(bolt11) if bolt11.amountOpt == None =>
              reply(Json.obj("result" := "continue"))
            case Success(bolt11) =>
              val targetPeer = bolt11.routingInfo
                .map { hops =>
                  ChannelMaster.database.data.channels.find((peerId, _) =>
                    hops.size == 1 &&
                      hostedShortChannelId(
                        publicKey.value,
                        peerId
                      ) == hops(0).shortChannelId
                  )
                }
                .flatten
                .headOption

              targetPeer match {
                case Some((peerId, _)) =>
                  // try to send this through the hosted channel
                  ChannelMaster
                    .getChannel(peerId)
                    .sendDirectPayment(bolt11)
                    .onComplete {
                      case Success(preimage) =>
                        reply(
                          Json.obj(
                            "return" := Json.obj(
                              "result" := Json.obj(
                                "destination" := peerId.toHex,
                                "payment_hash" := bolt11.hash.toHex,
                                "parts" := 1,
                                "amount_msat" := bolt11.amountOpt.get.toLong,
                                "msatoshi_sent" := bolt11.amountOpt.get.toLong,
                                "payment_preimage" := preimage.toHex,
                                "status" := "complete"
                              )
                            )
                          )
                        )
                      case Failure(err) =>
                        ChannelMaster.logger.info
                          .item("peer", peerId)
                          .item("bolt11", bolt11)
                          .item("err", err)
                          .msg(
                            "couldn't pay hosted peer, letting CLN do its thing"
                          )
                        reply(Json.obj("result" := "continue"))
                    }
                case None =>
                  // this is not targeting one of our hosted channels, so let CLN handle it
                  reply(Json.obj("result" := "continue"))
              }
          }
        } else
          // otherwise let CLN execute the command
          reply(Json.obj("result" := "continue"))
      }
      case "htlc_accepted" => {
        // we wait here because on startup c-lightning will replay all pending htlcs
        // and at that point we won't have the hosted channels active with our clients yet
        Timer.timeout(
          if (onStartup) 3.seconds
          else 0.seconds
        )(() => {
          val c = params.hcursor
          val htlc = c.downField("htlc")
          val onion = c.downField("onion")

          // if we're the final hop of an htlc this property won't exist
          if (onion.downField("short_channel_id").focus.isEmpty) {
            // just continue so our node will accept this payment
            reply(Json.obj("result" := "continue"))
          } else {
            val hash = htlc.get[ByteVector32]("payment_hash").toTry.get
            val sourceChannel =
              ShortChannelId(htlc.get[String]("short_channel_id").toTry.get)
            val sourceAmount = MilliSatoshi(
              htlc
                .get[String]("amount_msat")
                .orElse(htlc.get[String]("amount"))
                .map(_.takeWhile(_.isDigit).toLong)
                .orElse(
                  htlc
                    .get[Long]("amount_msat")
                )
                .toTry
                .get
            )
            val sourceId = htlc.get[Long]("id").toTry.get
            val cltvIn = htlc.get[CltvExpiry]("cltv_expiry").toTry.get

            val targetChannel =
              onion.get[ShortChannelId]("short_channel_id").toTry.get
            val targetAmount = MilliSatoshi(
              htlc
                .get[String]("forward_amount")
                .orElse(htlc.get[String]("forward_msat"))
                .map(_.takeWhile(_.isDigit).toLong)
                .orElse(
                  htlc
                    .get[Long]("forward_msat")
                )
                .toTry
                .get
            )
            val cltvOut = onion.get[CltvExpiry]("outgoing_cltv_value").toTry.get
            val nextOnion = onion.get[ByteVector]("next_onion").toTry.get
            val sharedSecret =
              onion.get[ByteVector32]("shared_secret").toTry.get

            ChannelMaster.database.data.channels.find((peerId, chandata) =>
              hostedShortChannelId(
                publicKey.value,
                peerId
              ) == targetChannel
            ) match {
              case Some((peerId, _)) => {
                ChannelMaster
                  .getChannel(peerId)
                  .addHtlc(
                    htlcIn = HtlcIdentifier(sourceChannel, sourceId),
                    paymentHash = hash,
                    amountIn = sourceAmount,
                    amountOut = targetAmount,
                    cltvIn = cltvIn,
                    cltvOut = cltvOut,
                    nextOnion = nextOnion
                  )
                  .foreach { status =>
                    val response = status match {
                      case Some(Right(preimage)) =>
                        Json.obj(
                          "result" := "resolve",
                          "payment_key" := preimage.toHex
                        )
                      case Some(Left(Some(FailureOnion(onion)))) =>
                        // must unwrap the onion here because the hosted channel
                        // won't unwrap whatever packet they got from the hosted peer
                        Json.obj(
                          "result" := "fail",
                          "failure_onion" := Sphinx.FailurePacket
                            .wrap(onion, sharedSecret)
                            .toHex
                        )
                      case Some(Left(Some(NormalFailureMessage(message)))) =>
                        Json.obj(
                          "result" := "fail",
                          "failure_message" := message.toHex
                        )
                      case Some(Left(None)) =>
                        Json.obj(
                          "result" := "fail",
                          "failure_message" := "1007"
                        )
                      case None =>
                        Json.obj("result" := "continue")
                    }
                    reply(response)
                  }
              }
              case None => {
                reply(Json.obj("result" := "continue"))
              }
            }
          }
        })
      }
      case "sendpay_success" => {
        val successdata = params.hcursor.downField("sendpay_success")
        if (successdata.downField("label").focus.isDefined)
          for {
            label <- successdata.get[String]("label").toOption
            (scidStr, htlcId) <- decode[(String, Long)](label).toOption
            scid = ShortChannelId(scidStr)
            (peerId, _) <- ChannelMaster.database.data.channels.find((p, _) =>
              hostedShortChannelId(publicKey.value, p) == scid
            )
          } yield ChannelMaster
            .getChannel(peerId)
            .gotPaymentResult(
              htlcId,
              toStatus(Vector(successdata.focus.get))
            )
      }
      case "sendpay_failure" => {
        val failuredata =
          params.hcursor.downField("sendpay_failure").downField("data")
        if (failuredata.downField("label").focus.isDefined)
          for {
            label <- failuredata.get[String]("label").toOption
            (scidStr, htlcId) <- decode[(String, Long)](label).toOption
            scid = ShortChannelId(scidStr)
            (peerId, _) <- ChannelMaster.database.data.channels.find((p, _) =>
              hostedShortChannelId(publicKey.value, p) == scid
            )
            channel = ChannelMaster.getChannel(peerId)
          } yield {
            failuredata.get[String]("status").toOption match {
              case Some("pending") =>
                Timer.timeout(1.second) { () =>
                  inspectOutgoingPayment(
                    HtlcIdentifier(scid, htlcId),
                    failuredata.get[ByteVector32]("payment_hash").toTry.get
                  ).foreach { result =>
                    channel.gotPaymentResult(htlcId, result)
                  }
                }
              case Some("failed") =>
                channel.gotPaymentResult(
                  htlcId,
                  toStatus(Vector(failuredata.focus.get))
                )

              case status =>
                ChannelMaster.logger.warn
                  .item("status", status)
                  .msg("unexpected status on sendpay_failure")
            }
          }
      }
      case "connect" => {
        // val id = params("id").str
        // val address = params("address")("address").str
        // ChannelMaster.log(s"$id connected: $address")
        // TODO: send InvokeHostedChannel to all hosted peers from which we are clients
        //       and related flows -- for example sending LastCrossSignedState etc
      }
      case "disconnect" => {
        // val id = params("id").str
        // ChannelMaster.log(s"$id disconnected")
      }

      // custom rpc methods
      case "parse-lcss" => {
        val c = params.hcursor

        (for {
          peerId <- c
            .get[ByteVector]("peerid")
            .orElse(c.downN(0).as[ByteVector])
            .toOption
          peer = PublicKey(peerId)
          lcssHex <- c
            .get[String]("last_cross_signed_state")
            .orElse(c.downN(1).as[String])
            .toOption
          lcssBits <- BitVector.fromHex(lcssHex)
          decoded <- lastCrossSignedStateCodec.decode(lcssBits).toOption
          lcss = decoded.value
        } yield (peer, lcss)) match {
          case Some((peer, lcss)) if lcss.verifyRemoteSig(peer) =>
            if (
              Crypto.verifySignature(
                lcss.reverse.hostedSigHash,
                lcss.localSigOfRemote,
                publicKey
              )
            )
              reply(lcss.asJson)
            else replyError("provided lcss wasn't signed by us")
          case None =>
            replyError("failed to decode last_cross_signed_state or peerid")
          case _ =>
            replyError("provided lcss signature does not match the peerid")
        }
      }

      case "add-hc-secret" =>
        if (!ChannelMaster.config.requireSecret) {
          replyError(
            "`requireSecret` must be set to true on config.json for this to do anything."
          )
        } else {
          val c = params.hcursor
          c.get[String]("secret").orElse(c.downN(0).as[String]) match {
            case Right(secret) => {
              ChannelMaster.temporarySecrets =
                ChannelMaster.temporarySecrets :+ secret
              reply(Json.obj("added" := true))
            }
            case Left(fail) => replyError(s"secret not given? $fail")
          }
        }

      case "remove-hc-secret" =>
        if (!ChannelMaster.config.requireSecret) {
          replyError(
            "`requireSecret` must be set to true on config.json for this to do anything."
          )
        } else {
          val c = params.hcursor
          c.get[String]("secret").orElse(c.downN(0).as[String]) match {
            case Right(secret) => {
              ChannelMaster.temporarySecrets =
                ChannelMaster.temporarySecrets.filterNot(_ == secret)
              reply(Json.obj("removed" := true))
            }
            case Left(fail) => replyError(s"secret not given? $fail")
          }
        }

      case "hc-list" =>
        reply(
          ChannelMaster.database.data.channels.toList
            .map(Printer.hcSimple(_))
            .asJson
        )

      case "hc-channel" =>
        val c = params.hcursor
        val peerId =
          c.get[ByteVector]("peerid").orElse(c.downN(0).as[ByteVector]).toOption
        val data = peerId.flatMap[ChannelData](
          ChannelMaster.database.data.channels.get(_)
        )

        // normally this will create a channel from whatever id we gave it,
        //   so here we first ensure this channel exists on the database
        val chan = (peerId, data) match {
          case (Some(id), Some(_)) => Some(ChannelMaster.getChannel(id))
          case _                   => None
        }

        (peerId, data, chan) match {
          case (Some(peerId), Some(data), Some(chan)) =>
            Printer.hcDetail(peerId, data, chan).onComplete {
              case Success(json) => reply(json)
              case Failure(err)  => replyError(err.toString())
            }
          case _ =>
            replyError("couldn't find the channel")
        }

      case "hc-override" =>
        val c = params.hcursor
        (
          c.get[ByteVector]("peerid").orElse(c.downN(0).as[ByteVector]),
          c.get[MilliSatoshi]("msatoshi")
            .orElse(c.downN(1).as[MilliSatoshi])
            .orElse(
              c.get[String]("msatoshi")
                .orElse(c.downN(1).as[String])
                .map(msat => MilliSatoshi(msat.toLong))
            )
        ) match {
          case (Right(peerId), Right(msatoshi)) => {
            ChannelMaster
              .getChannel(peerId)
              .proposeOverride(msatoshi)
              .onComplete {
                case Success(msg) => reply(msg.asJson)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }

      case "hc-resize" =>
        val c = params.hcursor
        (
          c.get[ByteVector]("peerid").orElse(c.downN(0).as[ByteVector]),
          c.get[MilliSatoshi]("msatoshi")
            .orElse(c.downN(1).as[MilliSatoshi])
            .orElse(
              c.get[String]("msatoshi")
                .orElse(c.downN(1).as[String])
                .map(msat => MilliSatoshi(msat.toLong))
            )
        ) match {
          case (Right(peerId), Right(amount)) => {
            val upTo = amount.toLong match {
              case 0 => None
              case s => Some(amount.toSatoshi)
            }

            ChannelMaster
              .getChannel(peerId)
              .acceptResize(upTo)
              .onComplete {
                case Success(msg) => reply(msg.asJson)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }

      case "hc-request-channel" =>
        val c = params.hcursor
        c.get[ByteVector]("peerid").orElse(c.downN(0).as[ByteVector]) match {
          case Right(peerId) => {
            ChannelMaster
              .getChannel(peerId)
              .requestHostedChannel()
              .onComplete {
                case Success(msg) => reply(msg.asJson)
                case Failure(err) => replyError(err.toString)
              }
          }
          case _ => {
            replyError("invalid parameters")
          }
        }
    }
  }

  def main(onInit: () => Unit): Unit = {
    initCallback = onInit

    Poll(0).startRead { v =>
      var current = Array.empty[Byte]

      breakable {
        while (true) {
          // read stdin char-by-char
          Try(scala.Console.in.read()) match {
            case Success(char) if char == -1 =>
              // this will happen when stdin is closed, i.e. lightningd
              //   is not alive anymore so we should shutdown too
              scala.sys.exit(72)
            case Success(char) if char == 10 =>
              // newline, we've got a full line, so handle it
              val line = new String(current, StandardCharsets.UTF_8).trim()
              if (line.size > 0) handleLine(line)
              current = Array.empty
            case Success(char) =>
              // normal char, add it to the current
              current = current :+ char.toByte
            case Failure(err) =>
              // EOF, stop reading and wait for the next libuv callback
              break()
          }
        }
      }
    }
  }
}
