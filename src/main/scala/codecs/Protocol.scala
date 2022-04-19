package codecs

import java.io.{OutputStream, InputStream, ByteArrayInputStream, IOException}
import java.nio.{ByteBuffer, ByteOrder}
import scodec.bits.{ByteVector, ByteOrdering}

object Protocol {
  val PROTOCOL_VERSION = 70015

  def uint8(input: InputStream): Int = input.read()

  def writeUInt8(input: Int, out: OutputStream): Unit = out.write(input & 0xff)

  def uint16(
      input: InputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Int = {
    val bin = new Array[Byte](2)
    input.read(bin)
    uint16(bin, order)
  }

  def uint16(input: Array[Byte], order: ByteOrder): Int = {
    val buffer = ByteBuffer.wrap(input).order(order)
    buffer.getShort & 0xffff
  }

  def writeUInt16(
      input: Int,
      out: OutputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Unit = out.write(writeUInt16(input, order).toArray)

  def writeUInt16(input: Int, order: ByteOrder): ByteVector = {
    val bin = new Array[Byte](2)
    val buffer = ByteBuffer.wrap(bin).order(order)
    buffer.putShort(input.toShort)
    ByteVector.view(bin)
  }

  def uint32(
      input: InputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Long = {
    val bin = new Array[Byte](4)
    input.read(bin)
    uint32(bin, order)
  }

  def uint32(input: Array[Byte], order: ByteOrder): Long = {
    val buffer = ByteBuffer.wrap(input).order(order)
    buffer.getInt() & 0xffffffffL
  }

  def uint32(input: ByteVector, order: ByteOrder): Long = {
    input.toLong(signed = false, ByteOrdering.fromJava(order))
  }

  def writeUInt32(
      input: Long,
      out: OutputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Unit = out.write(writeUInt32(input, order).toArray)

  def writeUInt32(input: Long, order: ByteOrder): ByteVector = {
    val bin = new Array[Byte](4)
    val buffer = ByteBuffer.wrap(bin).order(order)
    buffer.putInt((input & 0xffffffff).toInt)
    ByteVector.view(bin)
  }

  def writeUInt32(input: Long): ByteVector =
    writeUInt32(input, ByteOrder.LITTLE_ENDIAN)

  def uint64(
      input: InputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Long = {
    val bin = new Array[Byte](8)
    input.read(bin)
    uint64(bin, order)
  }

  def uint64(input: Array[Byte], order: ByteOrder): Long = {
    val buffer = ByteBuffer.wrap(input).order(order)
    buffer.getLong()
  }

  def writeUInt64(
      input: Long,
      out: OutputStream,
      order: ByteOrder = ByteOrder.LITTLE_ENDIAN
  ): Unit = out.write(writeUInt64(input, order).toArray)

  def writeUInt64(input: Long, order: ByteOrder): ByteVector = {
    val bin = new Array[Byte](8)
    val buffer = ByteBuffer.wrap(bin).order(order)
    buffer.putLong(input)
    ByteVector.view(bin)
  }

  def varint(blob: Array[Byte]): Long = varint(new ByteArrayInputStream(blob))

  def varint(input: InputStream): Long = input.read() match {
    case value if value < 0xfd => value
    case 0xfd                  => uint16(input)
    case 0xfe                  => uint32(input)
    case 0xff                  => uint64(input)
  }

  def writeVarint(input: Int, out: OutputStream): Unit =
    writeVarint(input.toLong, out)

  def writeVarint(input: Long, out: OutputStream): Unit = {
    if (input < 0xfdL) writeUInt8(input.toInt, out)
    else if (input < 65535L) {
      writeUInt8(0xfd, out)
      writeUInt16(input.toInt, out)
    } else if (input < 1048576L) {
      writeUInt8(0xfe, out)
      writeUInt32(input.toInt, out)
    } else {
      writeUInt8(0xff, out)
      writeUInt64(input, out)
    }
  }

  def bytes(input: InputStream, size: Long): ByteVector =
    bytes(input, size.toInt)

  def bytes(input: InputStream, size: Int): ByteVector = {
    val blob = new Array[Byte](size)
    if (size > 0) {
      val count = input.read(blob)
      if (count < size) throw new IOException("not enough data to read from")
    }
    ByteVector.view(blob)
  }

  def writeBytes(input: Array[Byte], out: OutputStream): Unit = out.write(input)

  def writeBytes(input: ByteVector, out: OutputStream): Unit =
    out.write(input.toArray)

  def varstring(input: InputStream): String = {
    val length = varint(input)
    new String(bytes(input, length).toArray, "UTF-8")
  }

  def writeVarstring(input: String, out: OutputStream): Unit = {
    writeVarint(input.length, out)
    writeBytes(input.getBytes("UTF-8"), out)
  }

  def hash(input: InputStream): ByteVector32 = ByteVector32(
    bytes(input, 32)
  ) // a hash is always 256 bits
}
