import codecs.{
  CltvExpiryDelta,
  BlockHeight,
  ByteVector32,
  InitHostedChannel,
  MilliSatoshi
}
import codecs.ShortChannelId

case class Config(
    cltvExpiryDelta: CltvExpiryDelta,
    feeBase: MilliSatoshi,
    feeProportionalMillionths: Long
)

object Main {
  val cm = new ChannelMaster()
  def main(args: Array[String]): Unit = {
    cm.run()
  }
}
