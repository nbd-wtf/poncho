import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import java.nio.file.{Files, Path, Paths}
import scala.annotation.tailrec
import scala.collection.immutable.Map
import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import upickle.default._

import crypto.Crypto
import codecs._

case class Data(
    htlcAcceptedIds: Map[String, String] = Map.empty,
    channels: Map[String, ChannelData] = Map.empty
)

case class ChannelData(
    peerId: ByteVector,
    isActive: Boolean,
    lcss: LastCrossSignedState
) {
  def shortChannelId(ourPubkey: ByteVector): ShortChannelId = ShortChannelId(
    List
      .fill(8)(
        Protocol.uint64(
          new ByteArrayInputStream(pubkeysCombined(ourPubkey, peerId).toArray),
          ByteOrder.BIG_ENDIAN
        )
      )
      .sum
  )

  def channelId(ourPubkey: ByteVector): ByteVector32 =
    Crypto.sha256(pubkeysCombined(ourPubkey, peerId))

  def pubkeysCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector =
    if (isLessThan(pubkey1, pubkey2)) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1

  @tailrec
  def isLessThan(a: ByteVector, b: ByteVector): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else (a.head & 0xff) < (b.head & 0xff)
  }
}

object Database {
  import Picklers.given

  val path: Path = Paths.get("poncho.db").toAbsolutePath()
  if (!Files.exists(path)) {
    Files.createFile(path)
    Files.write(path, write(Data()).getBytes)
  }
  var data: Data = read[Data](path)

  def save(): Unit = {
    writeToOutputStream(data, Files.newOutputStream(path))
  }
}

object Picklers {
  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))
  given ReadWriter[ByteVector32] =
    readwriter[String]
      .bimap[ByteVector32](_.toHex, ByteVector32.fromValidHex(_))
  given ReadWriter[ByteVector64] =
    readwriter[String]
      .bimap[ByteVector64](_.toHex, ByteVector64.fromValidHex(_))
  given ReadWriter[MilliSatoshi] =
    readwriter[Long].bimap[MilliSatoshi](_.toLong, MilliSatoshi(_))
  given ReadWriter[CltvExpiry] =
    readwriter[Long].bimap[CltvExpiry](_.toLong, CltvExpiry(_))
  given ReadWriter[ULong] =
    readwriter[Long].bimap[ULong](_.toLong, _.toULong)

  given ReadWriter[LastCrossSignedState] = macroRW
  given ReadWriter[InitHostedChannel] = macroRW
  given ReadWriter[UpdateAddHtlc] = macroRW
  given ReadWriter[TlvStream[UpdateAddHtlcTlv]] =
    readwriter[List[Int]]
      .bimap[TlvStream[UpdateAddHtlcTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )

  implicit val rw: ReadWriter[Data] = macroRW
  given ReadWriter[ChannelData] = macroRW
}
