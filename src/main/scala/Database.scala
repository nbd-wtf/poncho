import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Map
import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import upickle.default._

import codecs._

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

case class Data(
    htlcAcceptedIds: Map[String, String] = Map.empty,
    channels: Map[String, ChannelData] = Map.empty
)

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

case class ChannelData(
    channelId: ByteVector32,
    shortChannelId: String,
    isActive: Boolean,
    localNodeId: ByteVector,
    remoteNodeId: ByteVector,
    lastCrossSignedState: LastCrossSignedState
)
