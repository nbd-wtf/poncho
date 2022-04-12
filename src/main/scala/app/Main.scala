package app

import scala.scalanative.loop.Poll
import castor.Context

object Main {
  def main(args: Array[String]): Unit = {
    val lightningd = new LightningdListener()(castor.Context.Simple.global)

    Poll(0).startRead { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        lightningd.send(line)
      }
    }
  }
}
