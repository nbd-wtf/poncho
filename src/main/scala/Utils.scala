import scala.annotation.tailrec
import scodec.bits.ByteVector

object Utils {
  @tailrec
  final def isLessThan(a: ByteVector, b: ByteVector): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else (a.head & 0xff) < (b.head & 0xff)
  }
}
