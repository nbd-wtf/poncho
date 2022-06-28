import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}
import scala.util.chaining._
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import com.softwaremill.quicklens._
import upickle.default.{ReadWriter, macroRW}
import scodec.bits.ByteVector
import scodec.codecs._

import codecs._
import codecs.HostedChannelCodecs._
import codecs.LightningMessageCodecs._
import crypto.Crypto

import Utils.OnionParseResult
import scala.concurrent.duration.FiniteDuration

trait ChannelStatus
case object Opening extends ChannelStatus
case object Invoking extends ChannelStatus
case object Active extends ChannelStatus
case object Overriding extends ChannelStatus
case object NotOpened extends ChannelStatus
case object Errored extends ChannelStatus
case object Suspended extends ChannelStatus

case class ChannelState(
    peerId: ByteVector,
    htlcResults: Map[ULong, Promise[PaymentStatus]] = Map.empty,
    uncommittedUpdates: List[FromLocal | FromRemote] = List.empty,
    openingRefundScriptPubKey: Option[ByteVector] = None,
    invoking: Option[ByteVector | LastCrossSignedState] = None
) {
  def data = Database.data.channels.get(peerId).getOrElse(ChannelData())
  def lcss = data.lcss.get
  def status =
    if openingRefundScriptPubKey.isDefined then Opening
    else if invoking.isDefined then Invoking
    else if data.lcss.isEmpty then NotOpened
    else if data.proposedOverride.isDefined then Overriding
    else if !data.localErrors.isEmpty then Errored
    else if data.suspended then Suspended
    else Active

  // return a copy of this state with the update_add_htlc/update_fail_htlc/update_fulfill_htlc
  // appended to the list of uncommitted updates that will be used to generate lcssNext below
  // and that will be processed and dispatched to our upstream node once they are actually committed
  // TODO: should we also not add if this is an htlc that is already committed? probably
  def addUncommittedUpdate(upd: FromLocal | FromRemote): ChannelState = {
    if (this.uncommittedUpdates.exists(_ == upd)) then this
    else this.copy(uncommittedUpdates = this.uncommittedUpdates :+ upd)
  }
  def removeUncommitedUpdate(upd: FromLocal | FromRemote): ChannelState =
    this.copy(uncommittedUpdates = this.uncommittedUpdates.filterNot(_ == upd))

  // this tells our upstream node to resolve or fail the htlc it is holding
  def provideHtlcResult(id: ULong, result: PaymentStatus): Unit =
    this.htlcResults
      .get(id)
      .foreach(_.success(result))

  // calculates what will be our next state once we commit these uncommitted updates
  lazy val lcssNext: LastCrossSignedState = {
    val base = lcss
      .copy(
        blockDay = Main.currentBlockDay,
        remoteSigOfLocal = ByteVector64.Zeroes,
        localSigOfRemote = ByteVector64.Zeroes
      )

    uncommittedUpdates
      .foldLeft(base)((lcss, upd) =>
        upd match {
          case FromRemote(add: UpdateAddHtlc) =>
            lcss.copy(
              remoteBalanceMsat = lcss.remoteBalanceMsat - add.amountMsat,
              remoteUpdates = lcss.remoteUpdates + 1,
              incomingHtlcs = lcss.incomingHtlcs :+ add
            )
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
                  outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
          case FromRemote(fulfill: UpdateFulfillHtlc) => {
            lcss.outgoingHtlcs.find(_.id == fulfill.id) match {
              case Some(htlc) => {
                lcss.copy(
                  remoteBalanceMsat = lcss.remoteBalanceMsat + htlc.amountMsat,
                  remoteUpdates = lcss.remoteUpdates + 1,
                  outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
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

            lcss.incomingHtlcs.find(_.id == htlcId) match {
              case Some(htlc) => {
                lcss.copy(
                  remoteBalanceMsat = lcss.remoteBalanceMsat + htlc.amountMsat,
                  localUpdates = lcss.localUpdates + 1,
                  incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
          case FromLocal(fulfill: UpdateFulfillHtlc) => {
            lcss.incomingHtlcs.find(_.id == fulfill.id) match {
              case Some(htlc) => {
                lcss.copy(
                  localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                  localUpdates = lcss.localUpdates + 1,
                  incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
        }
      )
      .withLocalSigOfRemote(Main.node.getPrivateKey())
  }

  override def toString: String = {
    val printable = status match {
      case Opening    => s"(${openingRefundScriptPubKey.get.toHex})"
      case Active     => s"($lcss, $htlcResults, $uncommittedUpdates)"
      case Overriding => s"(${data.proposedOverride.get})"
      case Errored    => s"(${data.localErrors})"
      case _          => ""
    }

    s"Channel[${peerId.toHex}]${status.getClass.getSimpleName}$printable"
  }
}
