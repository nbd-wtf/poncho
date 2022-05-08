import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalanative.loop.Poll
import upickle.default._

object Main {
  import Picklers.given
  val isDev = true

  CLN.log(s"database is at: ${Database.path}")
  CLN.log(s"starting with data: ${write(Database.data)}")

  def main(args: Array[String]): Unit = {
    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        System.err.println(Console.BOLD + s"line: ${line}" + Console.RESET)
        CLN.handleRPC(line)
      }
    }
  }
}
