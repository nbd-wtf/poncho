package nlog

sealed trait Level
case object Debug extends Level
case object Info extends Level
case object Warn extends Level
case object Err extends Level

object Levels {
  val severity = Array(Debug, Info, Warn, Err)
  def shouldShow(setting: Level, logLevel: Level): Boolean =
    severity.indexOf(logLevel) >= severity.indexOf(setting)
}

type Items = List[(String, Any) | Any]

class Attacher(parent: Logger) {
  var items: Items = parent.getItems
  def item(label: String, value: Any) = {
    items = items :+ (label, value)
    this
  }
  def item(value: Any) = {
    items = items :+ value
    this
  }

  def logger = new Logger(items, parent.getPrinter)
}

class Logger(
    val items: Items = List.empty,
    printer: String => Unit = System.err.println,
    level: Level = Debug
) {
  def debug = new Log(this, Debug)
  def info = new Log(this, Info)
  def warn = new Log(this, Warn)
  def err = new Log(this, Err)
  def attach = new Attacher(this)

  def getItems = items
  def getPrinter = printer
  def print(logLevel: Level, str: String): Unit =
    if (Levels.shouldShow(level, logLevel)) printer(str)
}

class Log(
    parent: Logger,
    level: Level = Debug
) {
  var items: Items = parent.getItems
  def item(label: String, value: Any) = {
    items = items :+ (label, value)
    this
  }
  def item(value: Any) = {
    items = items :+ value
    this
  }

  def msg(text: String): Unit = {
    val lvl = level.getClass.getSimpleName.toUpperCase()
    val its =
      items
        .map {
          case (l: String, it: Any) => s"[$l]=$it"
          case it                   => s"{$it}"
        }
        .mkString(" ")
    val sep = if items.size > 0 then " -- " else ""
    parent.print(level, s"$lvl ${text}${sep}${its}")
  }
}
