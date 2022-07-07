import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Map
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import scodec.bits.ByteVector
import upickle.default._

import crypto.Crypto
import codecs._

case class HtlcIdentifier(scid: ShortChannelId, id: ULong) {
  override def toString(): String = s"HtlcIdentifier($id@$scid)"
}

case class Data(
    channels: Map[ByteVector, ChannelData] = Map.empty,

    // this is a mapping between the channel id and the htlc id of the the stuff we've received to the
    // channel id and htlc id we've sent for every payment that is in flight -- it allows us to know which
    // one to fulfill/fail when the other has been fulfilled/failed, and also prevents us from adding the same
    // htlc more than once when we restart and get the pending htlcs replayed on each channel
    htlcForwards: Map[HtlcIdentifier, HtlcIdentifier] = Map.empty,

    // this is a mapping between hash and preimage containing the
    // the preimages we have received but that our hosted peer hasn't acknowledged yet
    preimages: Map[ByteVector32, ByteVector32] = Map.empty
)

case class ChannelData(
    lcss: Option[LastCrossSignedState] = None,
    localErrors: Set[DetailedError] = Set.empty,
    remoteErrors: Set[Error] = Set.empty,
    proposedOverride: Option[LastCrossSignedState] = None,
    suspended: Boolean = false
)

case class DetailedError(
    error: Error,
    htlc: Option[UpdateAddHtlc],
    reason: String
) {
  override def toString: String = s"${error.description} | $reason: $htlc"
}

class Database(val path: Path = Paths.get("poncho").toAbsolutePath()) {
  import Picklers.given

  val channelsDir = path.resolve("channels")
  val htlcForwardsFile = path.resolve("htlc-forwards.json")
  val preimagesFile = path.resolve("preimages.json")

  if (!Files.exists(channelsDir)) {
    channelsDir.toFile().mkdirs()
  }
  if (!Files.exists(htlcForwardsFile)) {
    Files.createFile(htlcForwardsFile)
    Files.write(htlcForwardsFile, write(Data().htlcForwards.toList).getBytes)
  }
  if (!Files.exists(preimagesFile)) {
    Files.createFile(preimagesFile)
    Files.write(preimagesFile, write(Data().preimages).getBytes)
  }

  var data = Data(
    channels = channelsDir
      .toFile()
      .list()
      .filter(_.matches("[a-f0-9]{66}.json"))
      .map(filename =>
        (
          ByteVector.fromValidHex(filename.take(66)),
          read[ChannelData](channelsDir.resolve(filename))
        )
      )
      .toMap,
    htlcForwards =
      read[List[(HtlcIdentifier, HtlcIdentifier)]](htlcForwardsFile).toMap,
    preimages = read[Map[ByteVector32, ByteVector32]](preimagesFile)
  )

  // update will overwrite only the files that changed during the `change` operation
  def update(change: Data => Data) = {
    val newData = change(data)

    newData.channels
      .filter((key, chandata) =>
        !data.channels.contains(key) || data.channels(key) != chandata
      )
      .foreach { (key, chandata) =>
        val data = newData.channels(key)
        val file = channelsDir.resolve(key.toHex ++ ".json")
        Files.write(file, write(chandata).getBytes)
      }

    if (newData.htlcForwards != data.htlcForwards) {
      Files.write(htlcForwardsFile, write(newData.htlcForwards.toList).getBytes)
    }
    if (newData.preimages != data.preimages) {
      Files.write(preimagesFile, write(newData.preimages).getBytes)
    }

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
  given ReadWriter[ShortChannelId] =
    readwriter[String].bimap[ShortChannelId](_.toString, ShortChannelId(_))
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

  given ReadWriter[Data] = macroRW
  given ReadWriter[HtlcIdentifier] = macroRW
  given ReadWriter[ChannelData] = macroRW
  given ReadWriter[DetailedError] = macroRW
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
