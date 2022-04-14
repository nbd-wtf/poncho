import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Map
import upickle.default._

case class Data(channels: Map[String, ChannelData])
object Data {
  implicit val rw: ReadWriter[Data] = macroRW
  val initial: Data = Data(channels = Map.empty)
}
object Database {
  val path: Path = Paths.get("pokemon.db").toAbsolutePath()
  if (!Files.exists(path)) {
    Files.createFile(path)
    Files.write(path, write(Data.initial).getBytes)
  }
  var data: Data = read[Data](path)

  def save(): Unit = {
    writeToOutputStream(data, Files.newOutputStream(path))
  }
}
