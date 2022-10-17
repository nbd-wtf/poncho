import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import util.chaining.scalaUtilChainingOps
import scala.collection.immutable.Map
import scala.scalanative.unsigned._
import scala.scalanative.loop.Timer
import scodec.bits.ByteVector
import io.circe.{Error => _, _}
import io.circe.parser.decode
import io.circe.syntax._
import scoin._
import scoin.ln._
import scoin.hc._

import Utils.readString

case class HtlcIdentifier(scid: ShortChannelId, id: Long) {
  override def toString(): String = s"HtlcIdentifier($id@$scid)"
}

case class Data(
    channels: Map[ByteVector, ChannelData],

    // this is a mapping between the channel id and the htlc id of the the stuff we've received to the
    // channel id and htlc id we've sent for every payment that is in flight -- it allows us to know which
    // one to fulfill/fail when the other has been fulfilled/failed, and also prevents us from adding the same
    // htlc more than once when we restart and get the pending htlcs replayed on each channel
    htlcForwards: Map[HtlcIdentifier, HtlcIdentifier],

    // this is a mapping between hash and preimage containing the
    // the preimages we have received but that our hosted peer hasn't acknowledged yet
    preimages: Map[ByteVector32, ByteVector32]
)

object Data {
  def empty = Data(
    channels = Map.empty,
    htlcForwards = Map.empty,
    preimages = Map.empty
  )
}

case class ChannelData(
    lcss: LastCrossSignedState,
    localErrors: Set[DetailedError],
    remoteErrors: Set[Error],
    suspended: Boolean,
    proposedOverride: Option[LastCrossSignedState],
    acceptingResize: Option[Satoshi]
)

object ChannelData {
  def empty = ChannelData(
    lcss = LastCrossSignedState.empty,
    localErrors = Set.empty,
    remoteErrors = Set.empty,
    suspended = false,
    proposedOverride = None,
    acceptingResize = None
  )
}

case class DetailedError(
    error: Error,
    htlc: Option[UpdateAddHtlc],
    reason: String
) {
  override def toString: String = s"${error.toAscii} | $reason | $htlc"
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
    Files.write(
      htlcForwardsFile,
      Data.empty.htlcForwards.toList.asJson.noSpaces.getBytes
    )
  }
  if (!Files.exists(preimagesFile)) {
    Files.createFile(preimagesFile)
    Files.write(
      preimagesFile,
      Data.empty.preimages.toList.asJson.noSpaces.getBytes
    )
  }

  var data = {
    val channels = channelsDir
      .toFile()
      .list()
      .filter(_.matches("[a-f0-9]{66}.json"))
      .tap(_.foreach { filename =>
        // (temporary -- remove this later)
        // replace the $type attribute in the JSON before reading
        // this is necessary now only because we've changed the types to be at scoin.hc now
        val path = channelsDir.resolve(filename)

        val replaced =
          new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            .replace(
              "codecs.LastCrossSignedState",
              "scoin.hc.LastCrossSignedState"
            )
            .replace("codecs.InitHostedChannel", "scoin.hc.InitHostedChannel")

        Files.write(path, replaced.getBytes)
      })
      .map(filename =>
        (
          ByteVector.fromValidHex(filename.take(66)),
          decode[ChannelData](
            readString(channelsDir.resolve(filename))
          ).toTry.get
        )
      )
      .toMap
    val htlcForwards = decode[List[(HtlcIdentifier, HtlcIdentifier)]](
      readString(htlcForwardsFile)
    ).toTry.get.toMap
    val preimages = decode[List[(ByteVector32, ByteVector32)]](
      readString(preimagesFile)
    ).toTry.get.toMap
    Data(channels, htlcForwards, preimages)
  }

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
        Files.write(file, chandata.asJson.noSpaces.getBytes)
      }

    if (newData.htlcForwards != data.htlcForwards) {
      Files.write(
        htlcForwardsFile,
        newData.htlcForwards.toList.asJson.noSpaces.getBytes
      )
    }
    if (newData.preimages != data.preimages) {
      Files.write(preimagesFile, newData.preimages.asJson.noSpaces.getBytes)
    }

    data = newData
  }
}
