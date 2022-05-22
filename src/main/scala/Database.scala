import java.nio.file.{Files, Path, Paths}
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
    isActive: Boolean,
    lcss: LastCrossSignedState,
    error: Option[Error] = None,
    proposedOverride: Option[LastCrossSignedState] = None
)

object Database {
  import Picklers.given

  val path: Path = Paths.get("poncho.db").toAbsolutePath()
  if (!Files.exists(path)) {
    Files.createFile(path)
    Files.write(path, write(Data()).getBytes)
  }
  var data: Data = read[Data](path)

  def update(change: Data => Data) = {
    val newData = change(data)
    writeToOutputStream(newData, Files.newOutputStream(path))
    data = newData
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
  given ReadWriter[Error] = macroRW

  type UpdateAddHtlcTlvStream = TlvStream[UpdateAddHtlcTlv]
  given ReadWriter[UpdateAddHtlcTlvStream] =
    readwriter[List[Int]]
      .bimap[TlvStream[UpdateAddHtlcTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )
  type ErrorTlvStream = TlvStream[ErrorTlv] // hack
  given ReadWriter[ErrorTlvStream] =
    readwriter[List[Int]]
      .bimap[TlvStream[ErrorTlv]](
        _ => List.empty[Int],
        _ => TlvStream.empty
      )

  implicit val rw: ReadWriter[Data] = macroRW
  given ReadWriter[ChannelData] = macroRW
}

object OptionPickler extends upickle.AttributeTagged {
  override implicit def OptionWriter[T: Writer]: Writer[Option[T]] =
    implicitly[Writer[T]].comap[Option[T]] {
      case None    => null.asInstanceOf[T]
      case Some(x) => x
    }

  override implicit def OptionReader[T: Reader]: Reader[Option[T]] = {
    new Reader.Delegate[Any, Option[T]](implicitly[Reader[T]].map(Some(_))) {
      override def visitNull(index: Int) = None
    }
  }
}
