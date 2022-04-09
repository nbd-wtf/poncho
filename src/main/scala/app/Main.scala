package app

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import scala.scalanative.loop.Timer
import scala.scalanative.loop.Poll
import castor.Context

object Main {
  def main(args: Array[String]): Unit = {
    println("start")

    val l1 = new StringLogger("foo")(castor.Context.Simple.global)
    val l2 = new StringLogger("xiq")(castor.Context.Simple.global)
    val i1 = new IntLogger("bar")(castor.Context.Simple.global)
    l1.send("hello")
    l2.send("hello")
    i1.send(2)
    i1.send(3)
    l1.send("hello2")
    l2.send("hello2")

    println("before timer")
    Timer.timeout(FiniteDuration(5, "seconds")) { () =>
      println("will read stdin")

      Poll(0).startRead { _ =>
        val line = scala.io.StdIn.readLine().trim
        println(s"line: $line")
        if (line.size > 0) {
          l1.send(line)
        }
      }
    }
  }
}

class StringLogger(name: String)(implicit ac: castor.Context)
    extends castor.SimpleActor[String] {
  def run(s: String): Unit = {
    println(s"$name immediately: ${s}")
    Timer.timeout(FiniteDuration(2, "seconds")) { () =>
      println(s"$name after 2 seconds: ${s}")
    }
  }
}

class IntLogger(name: String)(implicit ac: castor.Context)
    extends castor.SimpleActor[Int] {
  def run(s: Int): Unit = {
    println(s"$name: ${s}")
  }
}
