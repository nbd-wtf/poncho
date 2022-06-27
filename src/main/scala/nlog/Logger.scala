package nlog

sealed trait Level
case object Debug extends Level
case object Info extends Level
case object Warn extends Level
case object Err extends Level
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
    items: Items = List.empty,
    printer: String => Unit = System.err.println
) {
  def debug = new Log(this, Debug)
  def info = new Log(this, Info)
  def warn = new Log(this, Warn)
  def err = new Log(this, Err)
  def attach = new Attacher(this)

  def getItems = items
  def getPrinter = printer
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
    val lvl = level.getClass.getSimpleName.toLowerCase
    val its =
      items
        .map {
          case (l: String, it: Any) => s"$l=$it"
          case it                   => s"$it"
        }
        .mkString(" ")
    val sep = if items.size > 0 then " -- " else ""
    parent.getPrinter(s"[$lvl] ${text}${sep}${its}")
  }
}
