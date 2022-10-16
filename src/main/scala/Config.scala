import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.util.Try
import scodec.bits.ByteVector
import io.circe._
import io.circe.parser.decode
import scoin._
import scoin.ln.Color
import scoin.hc.{InitHostedChannel, HostedChannelBranding}

import Utils.readString

object Config {
  import Picklers.given

  def fromFile(basePath: Path): Try[Config] =
    Try(readString(basePath.resolve("config.json")))
      .flatMap(decode[Config](_).toTry)
      .map(_.copy(basePath = Some(basePath)))

  def defaults: Config = Config()
}

case class Config(
    // path
    basePath: Option[Path] = None,

    // settings
    isDev: Boolean = true,

    // channels settings
    cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(137),
    feeBase: MilliSatoshi = MilliSatoshi(1000L),
    feeProportionalMillionths: Long = 1000L,
    maxHtlcValueInFlightMsat: MilliSatoshi = MilliSatoshi(100000000L),
    htlcMinimumMsat: MilliSatoshi = MilliSatoshi(1000L),
    maxAcceptedHtlcs: Int = 12,
    channelCapacityMsat: MilliSatoshi = MilliSatoshi(100000000L),
    initialClientBalanceMsat: MilliSatoshi = MilliSatoshi(0),

    // branding
    contactURL: String = "",
    logoFile: String = "",
    hexColor: String = "#ffffff",

    // extra
    requireSecret: Boolean = false,
    permanentSecrets: List[String] = List.empty,
    disablePreimageChecking: Boolean = false
) {
  def init: InitHostedChannel = InitHostedChannel(
    maxHtlcValueInFlight = maxHtlcValueInFlightMsat,
    htlcMinimum = htlcMinimumMsat,
    maxAcceptedHtlcs = maxAcceptedHtlcs,
    channelCapacity = channelCapacityMsat,
    initialClientBalance = initialClientBalanceMsat
  )

  def branding(logger: nlog.Logger): Option[HostedChannelBranding] =
    if (contactURL == "") None
    else {
      val optionalPng =
        Try {
          val png = ByteVector.view(
            Files.readAllBytes(basePath.get.resolve(logoFile))
          )

          if (png.size > 65535) {
            logger.warn.msg(
              s"logoFile must be a PNG with at most 65535 bytes, but $logoFile has ${png.size}."
            )
            throw new java.lang.IllegalArgumentException("")
          }

          png
        }.toOption

      val color: Color = Try {
        val rgb = ByteVector.fromValidHex(hexColor.drop(1))
        Color(rgb(0), rgb(1), rgb(2))
      }.getOrElse(Color(255.toByte, 255.toByte, 255.toByte))

      Some(
        HostedChannelBranding(
          color,
          optionalPng,
          contactURL
        )
      )
    }

  override def toString(): String = {
    val chan =
      s"capacity=$channelCapacityMsat initial-client-balance=$initialClientBalanceMsat"
    val policy = {
      val proportional =
        f"${(feeProportionalMillionths.toDouble * 100 / 1000000)}%.2f"
      s"fees=$feeBase/$proportional% min-delay=${cltvExpiryDelta.toInt}"
    }
    val htlc =
      s"max-htlcs=$maxAcceptedHtlcs max-htlc-sum=${maxHtlcValueInFlightMsat}msat min-htlc=$htlcMinimumMsat"
    val branding =
      if (contactURL != "")
        s"contact=$contactURL color=$hexColor logo=$logoFile"
      else "~"

    s"channel($chan) policy($policy) branding($branding) htlc($htlc)"
  }
}
