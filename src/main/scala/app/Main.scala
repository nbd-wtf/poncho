package app

import castor.Context

import app.codecs.CommonCodecs

object Main {
  def main(args: Array[String]): Unit = {
    val l1 = new StringLogger("foo")(castor.Context.Simple.global)
    val l2 = new StringLogger("xiq")(castor.Context.Simple.global)
    val i1 = new IntLogger("bar")(castor.Context.Simple.global)
    l1.send("hello")
    l2.send("hello")
    i1.send(2)
    i1.send(3)
    l1.send("hello2")
    l2.send("hello2")

    println(CommonCodecs.bool8)
  }
}

class StringLogger(name: String)(implicit ac: castor.Context)
    extends castor.SimpleActor[String] {
  def run(s: String): Unit = {
    println(s"$name: ${s}")
  }
}

class IntLogger(name: String)(implicit ac: castor.Context)
    extends castor.SimpleActor[Int] {
  def run(s: Int): Unit = {
    println(s"$name: ${s}")
  }
}
