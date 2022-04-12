package UnixSocket

import scala.scalanative.unsafe._
import scala.scalanative.libc.stdlib
import scala.scalanative.libc.string
import scalanative.unsigned.UnsignedRichLong
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.LibUV._
import scala.scalanative.loop.LibUVConstants._

// stuff that is missing from the libuv interface exposed by scala-native-loop
@link("uv")
@extern
object LibUVMissing {
  type ConnectReq = Ptr[Byte]
  type ConnectCB = CFuncPtr2[ConnectReq, CInt, Unit]
  type PipeAllocCB = CFuncPtr3[PipeHandle, CSize, Ptr[Buffer], Unit]
  type PipeReadCB = CFuncPtr3[PipeHandle, CSSize, Ptr[Buffer], Unit]

  def uv_pipe_connect(
      uv_connect_t: ConnectReq,
      uv_pipe_t: PipeHandle,
      name: CString,
      cb: ConnectCB
  ): Unit = extern

  def uv_buf_init(base: CString, len: Int): Buffer = extern
  def uv_strerror(err: CInt): CString = extern
  def uv_read_start(
      handle: PipeHandle,
      alloc_cb: PipeAllocCB,
      read_cb: PipeReadCB
  ): CInt = extern
}

object UnixSocket {
  import LibUVMissing._

  val UV_CONNECT_REQUEST = 2
  val pipe: PipeHandle =
    stdlib.malloc(uv_handle_size(UV_PIPE_T)).asInstanceOf[PipeHandle]
  val connect: ConnectReq =
    stdlib.malloc(uv_req_size(UV_CONNECT_REQUEST)).asInstanceOf[ConnectReq]
  val write = stdlib.malloc(uv_req_size(UV_WRITE_REQ_T)).asInstanceOf[WriteReq]
  var p = ""
  var result = ""

  def connect(path: String, payload: String): Unit = {
    p = payload
    System.err.println("starting libuv magic")
    val r = uv_pipe_init(loop, pipe, 0)
    System.err.println(s"pipe init: $r")
    System.err.println(path)

    Zone { implicit z =>
      uv_pipe_connect(
        connect,
        pipe,
        toCString(path),
        onConnect
      )
    }
  }

  val onConnect: ConnectCB = (_: ConnectReq, status: CInt) =>
    status match {
      case 0 => {
        System.err.println("connected")
        val buffer = stdlib.malloc(sizeof[Buffer]).asInstanceOf[Ptr[Buffer]]
        Zone { implicit z =>
          val temp_payload = toCString(p)
          val payload_len = string.strlen(temp_payload) + 1L.toULong
          buffer._1 = stdlib.malloc(payload_len)
          buffer._2 = payload_len
          string.strncpy(buffer._1, temp_payload, payload_len)
        }

        System.err.println(s"buf '${p}', size! ${p.length}")
        System.err.println(uv_write(write, pipe, buffer, 1, onWrite))
      }
      case _ =>
        System.err.println(
          s"failed to connect ($status): ${fromCString(uv_strerror(status))}"
        )
    }

  val onWrite: WriteCB = (_: WriteReq, status: CInt) =>
    status match {
      case 0 =>
        System.err.println("written")
        uv_read_start(pipe, onAlloc, onRead)
        ()
      case _ =>
        System.err.println(
          s"failed to write ($status): ${fromCString(uv_strerror(status))}"
        )
    }

  val onAlloc: PipeAllocCB =
    (_: PipeHandle, suggested_size: CSize, buf: Ptr[Buffer]) => {
      buf._1 = stdlib.malloc(suggested_size)
      buf._2 = suggested_size
    }

  val onRead: PipeReadCB = (_: PipeHandle, nread: CSSize, buf: Ptr[Buffer]) =>
    nread match {
      case 0 => {
        System.err.println("done reading")
        uv_close(pipe, onClose)
      }
      case n if n > 0 => {
        System.err.println("reading")
        System.err.println(fromCString(buf._1))
        stdlib.free(buf.asInstanceOf[Ptr[Byte]])
      }
      case n if n < 0 =>
        System.err.println(s"failed to read ($n)}")
        freeAll()
    }

  val onClose: CloseCB = (_: UVHandle) => {
    freeAll()
  }

  def freeAll(): Unit = {
    stdlib.free(pipe.asInstanceOf[Ptr[Byte]])
    stdlib.free(connect.asInstanceOf[Ptr[Byte]])
    stdlib.free(write.asInstanceOf[Ptr[Byte]])
  }
}
