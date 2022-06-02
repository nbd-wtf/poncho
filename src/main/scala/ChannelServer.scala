import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.unsigned._
import com.softwaremill.quicklens._
import castor.Context
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._

import codecs._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import crypto.Crypto

import ChanTools.OnionParseResult

// -- questions:
// should we fail all pending incoming htlcs on our actual normal node side whenever we fail the channel (i.e. send an Error message -- or receive an error message?)
//   answer: no, as they can still be resolve manually.
//   instead we should fail them whenever the timeout expires on the hosted channel side

case class FailureOnion(onion: ByteVector)
case class FailureCode(code: String)
type PaymentFailure = FailureOnion | FailureCode
type PaymentPreimage = ByteVector32
type HTLCResult =
  Option[Either[PaymentFailure, PaymentPreimage]]

case class FromLocal(upd: ChannelModifier)
case class FromRemote(upd: ChannelModifier)

class ChannelServer(peerId: String)(implicit
    ac: castor.Context
) extends castor.SimpleActor[HostedClientMessage] {
  sealed trait State
  case class Inactive() extends State
  case class Opening(refundScriptPubKey: ByteVector) extends State
  case class Active(
      htlcResults: Map[ULong, Promise[HTLCResult]] = Map.empty,
      uncommittedUpdates: List[FromLocal | FromRemote] = List.empty
  ) extends State {
    // return a copy of this state with the update_add_htlc/update_fail_htlc/update_fulfill_htlc
    // appended to the list of uncommitted updates that will be used to generate lcssNext below
    // and that will be processed and dispatched to our upstream node once they are actually committed
    def addUncommittedUpdate(upd: FromLocal | FromRemote): Active =
      this.copy(uncommittedUpdates = this.uncommittedUpdates :+ upd)

    // this tells our upstream node to resolve or fail the htlc it is holding
    def provideHtlcResult(id: ULong, result: HTLCResult): Unit =
      this.htlcResults
        .get(id)
        .foreach(_.success(result))

    // calculates what will be our next state once we commit these uncommitted updates
    lazy val lcssNext: LastCrossSignedState = {
      val base = Database.data.channels
        .get(peerId)
        .get
        .lcss
        .copy(
          blockDay = Main.currentBlockDay,
          remoteSigOfLocal = ByteVector64.Zeroes,
          localSigOfRemote = ByteVector64.Zeroes
        )

      uncommittedUpdates
        .foldLeft(base)((lcss, upd) =>
          upd match {
            case FromRemote(add: UpdateAddHtlc) => {
              lcss.copy(
                remoteBalanceMsat = lcss.remoteBalanceMsat - add.amountMsat,
                remoteUpdates = lcss.remoteUpdates + 1,
                incomingHtlcs = lcss.incomingHtlcs :+ add
              )
            }
            case FromRemote(
                  fail: (UpdateFailHtlc | UpdateFailMalformedHtlc)
                ) => {
              val htlcId = fail match {
                case x: UpdateFailHtlc          => x.id;
                case x: UpdateFailMalformedHtlc => x.id
              }

              lcss.outgoingHtlcs.find(_.id == htlcId) match {
                case Some(htlc) => {
                  lcss.copy(
                    localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                    remoteUpdates = lcss.remoteUpdates + 1,
                    outgoingHtlcs =
                      lcss.outgoingHtlcs.filterNot(_.id == htlc.id)
                  )
                }
                case None => lcss
              }
            }
            case FromRemote(fulfill: UpdateFulfillHtlc) => {
              lcss.outgoingHtlcs.find(_.id == fulfill.id) match {
                case Some(htlc) => {
                  lcss.copy(
                    remoteBalanceMsat =
                      lcss.remoteBalanceMsat + htlc.amountMsat,
                    remoteUpdates = lcss.remoteUpdates + 1,
                    outgoingHtlcs =
                      lcss.outgoingHtlcs.filterNot(_.id == htlc.id)
                  )
                }
                case None => lcss
              }
            }
            case FromLocal(add: UpdateAddHtlc) => {
              lcss.copy(
                localBalanceMsat = lcss.localBalanceMsat - add.amountMsat,
                localUpdates = lcss.localUpdates + 1,
                outgoingHtlcs = lcss.outgoingHtlcs :+ add
              )
            }
            case FromLocal(
                  fail: (UpdateFailHtlc | UpdateFailMalformedHtlc)
                ) => {
              val htlcId = fail match {
                case x: UpdateFailHtlc          => x.id;
                case x: UpdateFailMalformedHtlc => x.id
              }

              lcss.outgoingHtlcs.find(_.id == htlcId) match {
                case Some(htlc) => {
                  lcss.copy(
                    remoteBalanceMsat =
                      lcss.remoteBalanceMsat + htlc.amountMsat,
                    localUpdates = lcss.localUpdates + 1,
                    outgoingHtlcs =
                      lcss.outgoingHtlcs.filterNot(_.id == htlc.id)
                  )
                }
                case None => lcss
              }
            }
            case FromLocal(fulfill: UpdateFulfillHtlc) => {
              lcss.outgoingHtlcs.find(_.id == fulfill.id) match {
                case Some(htlc) => {
                  lcss.copy(
                    localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                    localUpdates = lcss.localUpdates + 1,
                    outgoingHtlcs =
                      lcss.outgoingHtlcs.filterNot(_.id == htlc.id)
                  )
                }
                case None => lcss
              }
            }
          }
        )
        .withLocalSigOfRemote(Main.node.getPrivateKey())
    }

    override def toString: String =
      s"Active(${lcssNext}, $htlcResults, $uncommittedUpdates)"
  }
  case class Errored(lcssNext: Option[LastCrossSignedState]) extends State
  case class Overriding(target: LastCrossSignedState) extends State

  var state: State =
    Database.data.channels.get(peerId) match {
      case Some(chandata) if chandata.isActive => Active()
      case _                                   => Inactive()
    }

  def stay = state

  def sendMessage: HostedServerMessage => Future[ujson.Value] =
    Main.node.sendCustomMessage(peerId, _)

  // TODO: here on startup check if the node has notice of any outgoing
  //       payments we may have sent and resolve or fail these accordingly

  def run(msg: HostedClientMessage): Unit = {
    Main.log(s"[$this] at $state <-- $msg")
    state = (state, msg) match {
      // someone wants a new hosted channel from us
      case (Inactive(), msg: InvokeHostedChannel) => {
        // check chain hash
        if (msg.chainHash != Main.chainHash) {
          Main.log(
            s"[${peerId}] sent InvokeHostedChannel for wrong chain: ${msg.chainHash} (current: ${Main.chainHash})"
          )
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              s"invalid chainHash (local=${Main.chainHash} remote=${msg.chainHash})"
            )
          )
          stay
        } else {
          // chain hash is ok, proceed
          Database.data.channels.get(peerId) match {
            case Some(chandata) => {
              // channel already exists, so send last cross-signed-state
              sendMessage(chandata.lcss)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
            case None => {
              // reply saying we accept the invoke
              sendMessage(Main.ourInit)
              Opening(refundScriptPubKey = msg.refundScriptPubKey)
            }
          }
        }
      }

      // final step of channel open process
      case (Opening(refundScriptPubKey), msg: StateUpdate) => {
        // build last cross-signed state
        val lcss = LastCrossSignedState(
          isHost = true,
          refundScriptPubKey = refundScriptPubKey,
          initHostedChannel = Main.ourInit,
          blockDay = msg.blockDay,
          localBalanceMsat =
            Main.ourInit.channelCapacityMsat - Main.ourInit.initialClientBalanceMsat,
          remoteBalanceMsat = Main.ourInit.initialClientBalanceMsat,
          localUpdates = 0L,
          remoteUpdates = 0L,
          incomingHtlcs = Nil,
          outgoingHtlcs = Nil,
          localSigOfRemote = ByteVector64.Zeroes,
          remoteSigOfLocal = msg.localSigOfRemoteLCSS
        )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        // check if everything is ok
        if ((msg.blockDay - Main.currentBlockDay).abs > 1) {
          Main.log(
            s"[${peerId}] sent StateUpdate with wrong blockday: ${msg.blockDay} (current: ${Main.currentBlockDay})"
          )
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_BLOCKDAY
            )
          )
          Inactive()
        } else if (!lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
          Main.log(s"[${peerId}] sent StateUpdate with wrong signature.")
          sendMessage(
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          )
          Inactive()
        } else {
          // all good, save this channel to the database and consider it opened
          Database.update { data =>
            {
              data
                .modify(_.channels)
                .using(
                  _ +
                    (
                      peerId -> ChannelData(
                        isActive = true,
                        error = None,
                        lcss = lcss,
                        proposedOverride = None
                      )
                    )
                )
            }
          }

          // send our signed state update
          sendMessage(lcss.stateUpdate)

          // send a channel update
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

          Active()
        }
      }

      // a client was just turned on and is sending this to sync states
      case (_: Active, msg: LastCrossSignedState) => {
        val isLocalSigOk = msg.verifyRemoteSig(Main.node.ourPubKey)
        val isRemoteSigOk =
          msg.reverse.verifyRemoteSig(ByteVector.fromValidHex(peerId))

        if (!isLocalSigOk || !isRemoteSigOk) {
          val err = if (!isLocalSigOk) {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with a signature that isn't ours"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_LOCAL_SIG
            )
          } else {
            Main.log(
              s"[${peerId}] sent LastCrossSignedState with an invalid signature"
            )
            Error(
              ChanTools.getChannelId(peerId),
              Error.ERR_HOSTED_WRONG_REMOTE_SIG
            )
          }
          sendMessage(err)
          Database.update { data =>
            {
              data
                .modify(_.channels.at(peerId).isActive)
                .setTo(false)
            }
          }
          Inactive()
        } else {
          // channel is active, which means we must have a database entry necessarily
          val chandata = Database.data.channels.get(peerId).get

          val lcssMostRecent =
            if (
              (chandata.lcss.localUpdates + chandata.lcss.remoteUpdates) >=
                (msg.remoteUpdates + msg.localUpdates)
            ) {
              // we are even or ahead
              chandata.lcss
            } else {
              // we are behind
              Main.log(
                s"[${peerId}] sent LastCrossSignedState showing that we are behind: " +
                  s"local=${chandata.lcss.localUpdates}/${chandata.lcss.remoteUpdates} " +
                  s"remote=${msg.remoteUpdates}/${msg.localUpdates}"
              )

              // save their lcss here
              Database.update { data =>
                {
                  data
                    .modify(_.channels.at(peerId).lcss)
                    .setTo(msg.reverse)
                }
              }

              msg
            }

          // all good, send the most recent lcss again and then the channel update
          sendMessage(lcssMostRecent)
          sendMessage(ChanTools.makeChannelUpdate(peerId, lcssMostRecent))
          stay
        }
      }

      // a client has lost its channel state but we have it
      case (_: Active, msg: InvokeHostedChannel) => {
        // channel already exists, so send last cross-signed-state
        val chandata = Database.data.channels.get(peerId).get
        sendMessage(chandata.lcss)
        stay
      }

      // client is fulfilling an HTLC we've sent
      case (
            active: Active,
            msg: UpdateFulfillHtlc
          ) => {
        Main.log(s"got fulfill!")

        // find the htlc
        active.lcssNext.outgoingHtlcs.find(_.id == msg.id) match {
          case Some(htlc)
              if Crypto.sha256(msg.paymentPreimage) == htlc.paymentHash => {
            Main.log(s"resolving htlc ${htlc.paymentHash}")

            // check if this new potential lcss is not broken
            val updated = active.addUncommittedUpdate(FromRemote(msg))
            if (ChanTools.lcssIsBroken(updated.lcssNext)) {
              // TODO we should get a proper error here and fail the channel
              Main.log(s"lcssNext is broken: ${updated.lcssNext}")
              stay
            } else {
              // call our htlc callback so our node is notified
              // we do this to guarantee our money as soon as possible
              active.provideHtlcResult(
                htlc.id,
                Some(Right(msg.paymentPreimage))
              )

              // keep updated state
              updated
            }
          }
          case _ => {
            Main.log(
              s"client has fulfilled an HTLC we don't know about (or used a wrong preimage): ${msg}"
            )
            stay
          }
        }
      }

      // client is failing an HTLC we've sent
      case (
            active: Active,
            msg: (UpdateFailHtlc | UpdateFailMalformedHtlc)
          ) => {
        msg match {
          case f: UpdateFailHtlc if (f.reason.isEmpty) => {
            // TODO fail the channel
            stay
          }
          case _ => {
            val updated = active.addUncommittedUpdate(FromRemote(msg))
            if (ChanTools.lcssIsBroken(updated.lcssNext)) {
              // TODO fail the channel
              stay
            } else {
              // keep the updated state
              updated
            }
          }
        }
      }

      // client is sending an htlc through us
      case (
            active: Active,
            msg: UpdateAddHtlc
          ) => {
        // TODO check if fee and cltv delta etc are correct, otherwise fail the channel
        ChanTools.parseClientOnion(msg) match {
          case Right(OnionParseResult(packet, _, _)) => {}
          case _                                     => {}
        }

        val updated = active.addUncommittedUpdate(FromRemote(msg))
        if (ChanTools.lcssIsBroken(updated.lcssNext)) {
          // TODO fail the channel
          stay
        } else {
          // keep the updated state
          updated
        }
      }

      // after an HTLC has been sent or received or failed or fulfilled and we've updated our local state,
      // this should be the confirmation that the other side has also updated it correctly
      // TODO this should account for situations in which peer is behind us (ignore?) and for when we're behind (keep track of the forward state?)
      case (active: Active, msg: StateUpdate)
          if (!active.uncommittedUpdates.isEmpty) => {
        // this won't be triggered if we don't have uncommitted updates
        Main.log(s"updating our local state after a transition")
        if (
          msg.remoteUpdates == active.lcssNext.localUpdates &&
          msg.localUpdates == active.lcssNext.remoteUpdates &&
          msg.blockDay == active.lcssNext.blockDay
        ) {
          Main.log("we and the client are now even")

          // verify signature
          val lcssNext = active.lcssNext.copy(
            remoteSigOfLocal = msg.localSigOfRemoteLCSS
          )
          if (lcssNext.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Main.log(s"saving on db: $lcssNext")
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcssNext, proposedOverride = None, error = None)
                )
            }

            // relay pending messages to node
            active.uncommittedUpdates.foreach {
              // i.e. and fail htlcs if any
              case FromRemote(fail: UpdateFailHtlc) =>
                // TODO wrap the failure and return it as an onion tp upstream node
                active.provideHtlcResult(
                  fail.id,
                  Some(Left(FailureCode("2002")))
                )
              case FromRemote(fail: UpdateFailMalformedHtlc) =>
                // TODO wrap the failure and return it as an onion tp upstream node
                active.provideHtlcResult(
                  fail.id,
                  Some(Left(FailureCode("2002")))
                )
              case FromRemote(add: UpdateAddHtlc) => {
                // send a payment through the upstream node
                ChanTools.parseClientOnion(add) match {
                  case Left(fail) => {
                    // TODO reply with failure
                    Main.log(s"failure: $fail")
                  }
                  case Right(
                        OnionParseResult(
                          payload: PaymentOnion.FinalTlvPayload,
                          _,
                          _
                        )
                      ) => {
                    // TODO we're receiving the payment? this is weird but possible.
                    // figure out how to handle this later, it probably involves checking our parent node invoices.
                    // could also be a trampoline, so when we want to support that we'll have to look again at the
                    // eclair code to see how they're doing it.
                    Main.log(
                      s"we're receiving the payment from the client? $payload"
                    )
                  }
                  case Right(
                        OnionParseResult(
                          payload: PaymentOnion.ChannelRelayPayload,
                          nextOnion: ByteVector,
                          sharedSecret: ByteVector32
                        )
                      ) => {
                    System.err.println(s"got an onion: $payload")
                    System.err.println(
                      s"channel: ${ShortChannelId(payload.outgoingChannelId)}"
                    )

                    Main.node
                      .getPeerFromChannel(
                        ShortChannelId(payload.outgoingChannelId)
                      )
                      .onComplete {
                        case Success(Some(nodeid)) =>
                          Main.node
                            .sendOnion(
                              hostedPeerId = peerId,
                              htlcId = add.id,
                              paymentHash = add.paymentHash,
                              firstHop = nodeid,
                              amount = payload.amountToForward,
                              cltvExpiryDelta =
                                add.cltvExpiry - payload.outgoingCltv,
                              onion = nextOnion
                            )
                            .onComplete {
                              case Failure(e) =>
                                // TODO return a temporary_channel_failure here
                                Main.log(s"sendonion failure: $e")
                              case _ => {}
                            }
                        case Failure(err) => {
                          // TODO return unknown_next_peer
                          System.err.println(
                            s"failed to get peer for channel: $err"
                          )
                        }
                        case Success(None) => {
                          // TODO return unknown_next_peer
                          System.err.println("didn't find peer for channel")
                        }
                      }
                  }
                }
              }
              case FromRemote(_: UpdateFulfillHtlc) => {
                // we've already relayed this to the upstream node eagerly, so do nothing
              }
              case FromLocal(_) => {
                // we do not take any action reactively with updates we originated
                // since we have sent them already before sending our signed state updates for them
              }
            }

            // send our state update
            sendMessage(lcssNext.stateUpdate)

            // update this channel state to the new lcss
            // plus clean up htlcResult promises that were already fulfilled
            active.copy(
              htlcResults =
                active.htlcResults.filterNot((_, p) => p.future.isCompleted),
              uncommittedUpdates = List.empty
            )
          } else {
            // TODO wrong signature, fail the channel
            stay
          }
        } else {
          // this state update is outdated, do nothing and wait for the next
          Main.log(
            s"the state they've sent (${msg.blockDay}) is different from our last (${active.lcssNext.blockDay}):"
          )
          Main.log(s"theirs: ${msg.remoteUpdates}/${msg.localUpdates}")
          Main.log(
            s"ours: ${active.lcssNext.localUpdates}/${active.lcssNext.remoteUpdates}"
          )
          stay
        }
      }

      // client is (hopefully) accepting our override proposal
      case (Overriding(lcssOverrideProposal), msg: StateUpdate) => {
        if (
          msg.remoteUpdates == lcssOverrideProposal.localUpdates &&
          msg.localUpdates == lcssOverrideProposal.remoteUpdates &&
          msg.blockDay == lcssOverrideProposal.blockDay
        ) {
          // it seems that the peer has agreed to our override proposal
          val lcss = lcssOverrideProposal.copy(remoteSigOfLocal =
            msg.localSigOfRemoteLCSS
          )
          if (lcss.verifyRemoteSig(ByteVector.fromValidHex(peerId))) {
            // update state on the database
            Database.update { data =>
              data
                .modify(_.channels.at(peerId))
                .using(
                  _.copy(lcss = lcss, proposedOverride = None, error = None)
                )
            }

            // send our channel policies again just in case
            sendMessage(ChanTools.makeChannelUpdate(peerId, lcss))

            // channel is active again
            Active()
          } else stay
        } else stay
      }

      // client is sending an error
      case (active: Active, msg: Error) => {
        Database.update { data =>
          data.modify(_.channels.at(peerId).error).setTo(Some(msg))
        }
        Errored(lcssNext = Some(active.lcssNext))
      }

      case _ => stay
    }
  }

  def addHTLC(prototype: UpdateAddHtlc): Future[HTLCResult] = {
    var promise = Promise[HTLCResult]()

    Main.log(s"forwarding payment $state <-- $prototype")
    state match {
      case active: Active
          if (active.lcssNext.incomingHtlcs
            .exists(_.paymentHash == prototype.paymentHash)) => {
        // reject htlc as outgoing if it's already incoming, sanity check
        Main.log(
          s"${prototype.paymentHash} is already incoming, can't add it as outgoing"
        )
        promise.success(None)
      }
      case active: Active
          if (active.lcssNext.outgoingHtlcs
            .exists(_.paymentHash == prototype.paymentHash)) => {
        // do not add htlc to state if it's already there (otherwise the state will be invalid)
        // this is likely to be hit on restarts as the node will replay pending htlcs on us
        Main.log("won't forward the htlc as it's already there")

        // but we still want to update the callbacks we're keeping track of (because we've restarted!)
        val add = active.lcssNext.outgoingHtlcs
          .find(htlc =>
            // TODO update this to check for the id once CLN allows
            htlc.paymentHash == prototype.paymentHash && htlc.amountMsat == prototype.amountMsat
          )
          .get

        state =
          active.copy(htlcResults = active.htlcResults + (add.id -> promise))
      }
      case active: Active => {
        // the default case in which we add a new htlc
        // create update_add_htlc based on the prototype we've received
        val msg = prototype
          .copy(id = active.lcssNext.localUpdates.toULong + 1L.toULong)

        // prepare modification to new lcss to be our next
        val updated = active.addUncommittedUpdate(FromLocal(msg))

        // TODO check if we're going over the max in-flight limits, return a temporary_channel_failure if yes
        // TODO check if fees are sufficient, return a temporary_channel_failure if yes
        // check if this new potential lcss is not broken
        if (ChanTools.lcssIsBroken(updated.lcssNext)) {
          // TODO return a temporary_channel_failure here
          promise.success(Some(Left(FailureCode("2002"))))
        } else {
          // will send update_add_htlc to hosted client
          //
          // but first we update the state to include this uncommitted htlc
          // and add to the callbacks we're keeping track of for the upstream node
          state =
            updated.copy(htlcResults = active.htlcResults + (msg.id -> promise))

          sendMessage(msg)
            .onComplete {
              case Success(_) =>
                // success here means the client did get our update_add_htlc, so send signed state_update
                sendMessage(updated.lcssNext.stateUpdate)
              case Failure(err) => {
                // client is offline and can't take our update_add_htlc, so we fail it on upstream
                // and remote it from the list of uncommitted updates
                Main.log(s"failed to send update_add_htlc to $peerId: $err")
                promise.success(None)
                state match {
                  case active: Active => {
                    state = active.copy(uncommittedUpdates =
                      active.uncommittedUpdates.filterNot({
                        case FromLocal(add: UpdateAddHtlc) => add.id == msg.id
                        case _                             => false
                      })
                    )
                  }
                  case _ => {}
                }
              }
            }
        }
      }
      case _ => {
        Main.log("can't add an HTLC in a channel that isn't Active()")
        promise.success(None)
      }
    }

    promise.future
  }

  def upstreamPaymentFailure(htlcId: ULong): Unit = {
    scala.concurrent.ExecutionContext.global.execute(() => {
      state match {
        case active: Active => {
          active.lcssNext.outgoingHtlcs.find(_.id == htlcId) match {
            case Some(htlc) => {
              ChanTools.parseClientOnion(htlc) match {
                case Right(OnionParseResult(packet, _, sharedSecret)) => {
                  val fail = UpdateFailHtlc(
                    ChanTools.getChannelId(peerId),
                    htlcId,
                    Sphinx.FailurePacket // TODO accept failure onions from upstream and just wrap them here
                      .create(sharedSecret, TemporaryNodeFailure)
                  )

                  sendMessage(fail)
                    .onComplete {
                      case Success(_) => {
                        state match {
                          case active: Active => {
                            val updated =
                              active.addUncommittedUpdate(FromLocal(fail))
                            state = updated
                            sendMessage(updated.lcssNext.stateUpdate)
                          }
                          case _ => {}
                        }
                      }
                      case _ => {}
                    }
                }
                case _ => {} // should never happen
              }
            }
            case None => {} // should never happen
          }
        }
        case _ => {
          // TODO what to do when a payment fails upstream and the channel is not active
        }
      }
    })
  }

  def upstreamPaymentSuccess(
      htlcId: ULong,
      preimage: ByteVector32
  ): Unit = {
    scala.concurrent.ExecutionContext.global.execute(() => {
      state match {
        case active: Active => {
          val success = UpdateFulfillHtlc(
            ChanTools.getChannelId(peerId),
            htlcId,
            preimage
          )
          sendMessage(success)
            .onComplete {
              case Success(_) => {
                state match {
                  case active: Active => {
                    val updated =
                      active.addUncommittedUpdate(FromLocal(success))
                    state = updated
                    sendMessage(updated.lcssNext.stateUpdate)
                  }
                  case _ => {}
                }
              }
              case _ => {}
            }
        }
        case _ => {
          // TODO what to do when a payment succeeds upstream and the channel is not active
        }
      }
    })
  }

  def proposeOverride(newLocalBalance: MilliSatoshi): Future[String] = {
    state match {
      case Errored(maybeLcssNext) => {
        val lcssNextBase =
          maybeLcssNext
            .orElse(Database.data.channels.get(peerId).map(_.lcss))
            .get

        val lcssOverride = lcssNextBase
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              lcssNextBase.initHostedChannel.channelCapacityMsat - newLocalBalance,
            incomingHtlcs = Nil,
            outgoingHtlcs = Nil,
            localUpdates = lcssNextBase.localUpdates + 1,
            remoteUpdates = lcssNextBase.remoteUpdates + 1,
            blockDay = Main.currentBlockDay,
            remoteSigOfLocal = ByteVector64.Zeroes
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        state = Overriding(lcssOverride)
        sendMessage(lcssOverride.stateOverride)
          .map((v: ujson.Value) => v("status").str)
      }
      case Overriding(target) => {
        val lcssOverride = target
          .copy(
            localBalanceMsat = newLocalBalance,
            remoteBalanceMsat =
              target.initHostedChannel.channelCapacityMsat - newLocalBalance,
            blockDay = Main.currentBlockDay
          )
          .withLocalSigOfRemote(Main.node.getPrivateKey())

        state = Overriding(lcssOverride)
        sendMessage(lcssOverride.stateOverride)
          .map((v: ujson.Value) => v("status").str)
      }
      case _ => {
        Future {
          s"can't send to this channel since it is not errored or in overriding state."
        }
      }
    }
  }
}
