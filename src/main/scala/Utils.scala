import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import scala.annotation.tailrec
import scodec.bits.ByteVector

import crypto.Crypto
import codecs._

class PonchoException(s: String) extends java.lang.Exception {
  override def toString(): String = s
}

object Utils {
  @tailrec
  final def isLessThan(a: ByteVector, b: ByteVector): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else (a.head & 0xff) < (b.head & 0xff)
  }

  def getShortChannelId(peer1: ByteVector, peer2: ByteVector): ShortChannelId =
    val num: Long = Protocol.uint64(
      new ByteArrayInputStream(
        pubkeysCombined(peer1, peer2).toArray
      ),
      ByteOrder.BIG_ENDIAN
    )
    ShortChannelId(
      List
        .fill(8)(num)
        .sum
    )

  def getChannelId(peer1: ByteVector, peer2: ByteVector): ByteVector32 =
    Crypto.sha256(pubkeysCombined(peer1, peer2))

  def pubkeysCombined(
      pubkey1: ByteVector,
      pubkey2: ByteVector
  ): ByteVector =
    if (Utils.isLessThan(pubkey1, pubkey2)) pubkey1 ++ pubkey2
    else pubkey2 ++ pubkey1
}
