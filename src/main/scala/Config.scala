import java.io.File
import java.net.URL
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import codecs.{
  CltvExpiryDelta,
  BlockHeight,
  ByteVector32,
  MilliSatoshi,
  InitHostedChannel,
  HostedChannelBranding
}
import codecs.CommonCodecs.Color
import upickle.default._

object Config {
  import Picklers.given
  given ReadWriter[Config] = macroRW

  def fromFile(basePath: Path): Try[Config] =
    Try(read[Config](basePath.resolve("config.json")))
      .map(_.copy(basePath = Some(basePath)))

  def defaults: Config = Config()
}

case class Config(
    // path
    basePath: Option[Path] = None,

    // channels settings
    cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(143),
    feeBase: MilliSatoshi = MilliSatoshi(1000L),
    feeProportionalMillionths: Long = 1000L,
    maxHtlcValueInFlightMsat: ULong = 100000000L.toULong,
    htlcMinimumMsat: MilliSatoshi = MilliSatoshi(1000L),
    maxAcceptedHtlcs: Int = 12,
    channelCapacityMsat: MilliSatoshi = MilliSatoshi(100000000L),
    initialClientBalanceMsat: MilliSatoshi = MilliSatoshi(0),

    // branding
    contactURL: Option[String] = None,
    logoFile: Option[String] = None,
    hexColor: String = "#ffffff"
) {
  // this will throw if not URL, which is desired
  contactURL.foreach { new URL(_) }

  def init: InitHostedChannel = InitHostedChannel(
    maxHtlcValueInFlightMsat = 100000000L.toULong,
    htlcMinimumMsat = MilliSatoshi(1000L),
    maxAcceptedHtlcs = 12,
    channelCapacityMsat = MilliSatoshi(100000000L),
    initialClientBalanceMsat = MilliSatoshi(0)
  )

  lazy val branding: Option[HostedChannelBranding] = {
    contactURL.map { url =>
      val optionalPng =
        Try(
          ByteVector.view(
            Files.readAllBytes(basePath.get.resolve(logoFile.get))
          )
        ).toOption

      val color: Color = Try {
        val rgb = ByteVector.fromValidHex(hexColor.drop(1))
        Color(rgb(0), rgb(1), rgb(2))
      }.getOrElse(Color(255.toByte, 255.toByte, 255.toByte))

      HostedChannelBranding(
        color,
        optionalPng,
        url
      )
    }
  }
}
