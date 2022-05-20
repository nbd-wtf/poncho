package unixsocket

import scala.Byte.byte2int
import scala.collection.mutable.HashSet
import scala.concurrent.Promise
import scala.scalanative.unsafe._
import scala.scalanative.libc.{stdlib, string}
import scalanative.unsigned.UnsignedRichLong
import scala.scalanative.loop.EventLoop.loop

import scala.scalanative.loop.LibUV.{uv_read_start as _, _}
import scala.scalanative.loop.LibUVConstants._
import LibUVMissing._

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

  def uv_read_start(
      handle: PipeHandle,
      alloc_cb: PipeAllocCB,
      read_cb: PipeReadCB
  ): CInt = extern
}

private case class UnixDomainSocketException(s: String)
    extends java.lang.Exception(s) {
  override def toString(): String = s
}

private class Call(_path: String, _payload: String) {
  final val UV_CONNECT_REQUEST = 2

  val path = _path
  val payload = _payload

  val pipe: PipeHandle =
    stdlib.malloc(uv_handle_size(UV_PIPE_T)).asInstanceOf[PipeHandle]
  val connect: ConnectReq =
    stdlib.malloc(uv_req_size(UV_CONNECT_REQUEST)).asInstanceOf[ConnectReq]
  val write = stdlib.malloc(uv_req_size(UV_WRITE_REQ_T)).asInstanceOf[WriteReq]

  var readResponse = ""
  val result = Promise[String]()
}

object UnixSocket {
  final val UV_EOF = -4095

  val calls = HashSet.empty[Call]

  def call(path: String, payload: String): Promise[String] = {
    val call = new Call(path, payload)
    calls.addOne(call)

    // libuv magic
    uv_pipe_init(loop, call.pipe, 0)
    var pathC: CString = c""
    Zone { implicit z =>
      pathC = toCString(path)
    }

    // ask libuv: "hey we want to open a connection to this thing, please"
    uv_pipe_connect(
      call.connect,
      call.pipe,
      pathC,
      onConnect
    )

    call.result
  }

  val onConnect: ConnectCB = (connect: ConnectReq, status: CInt) => {
    calls.find(c => c.connect == connect) match {
      case Some(call) =>
        status match {
          case 0 => {
            // we have connected successfully
            Zone { implicit z =>
              val buffer = alloc[Byte](sizeof[Buffer]).asInstanceOf[Ptr[Buffer]]
              val temp_payload = toCString(call.payload)
              val payload_len = string.strlen(temp_payload) + 1L.toULong
              buffer._1 = alloc[Byte](payload_len)
              buffer._2 = payload_len
              string.strncpy(buffer._1, temp_payload, payload_len)

              // ask libuv: "can you please let us write this payload into the pipe?"
              val r = uv_write(call.write, call.pipe, buffer, 1, onWrite)
              if (r != 0) {
                // result.failure(
                UnixDomainSocketException(
                  s"couldn't even try to write ($r): ${fromCString(uv_strerror(r))}"
                )
              }
            }
          }
          case _ =>
            // fail the promise
            call.result.failure(
              UnixDomainSocketException(
                s"failed to connect [${call.path}] ($status): ${fromCString(uv_strerror(status))}"
              )
            )
        }
      case None => {}
    }
    ()
  }

  val onWrite: WriteCB = (write: WriteReq, status: CInt) => {
    calls.find(c => c.write == write) match {
      case Some(call) =>
        status match {
          case 0 =>
            // written successfully, now ask libuv: "now we want to read the response"
            uv_read_start(call.pipe, onAlloc, onRead)
          case _ =>
            // fail the promise
            call.result.failure(
              UnixDomainSocketException(
                s"failed to write ($status): ${fromCString(uv_strerror(status))}"
              )
            )
        }
      case None => {}
    }
    ()
  }

  val onAlloc: PipeAllocCB =
    (_: PipeHandle, suggested_size: CSize, buf: Ptr[Buffer]) => {
      // this is called in a loop with an empty buffer, we must allocate some bytes for it
      buf._1 = stdlib.malloc(64L.toULong)
      buf._2 = 64L.toULong
    }

  val onRead: PipeReadCB =
    (pipe: PipeHandle, nread: CSSize, buf: Ptr[Buffer]) => {
      calls.find(c => c.pipe == pipe) match {
        case Some(call) => {
          nread match {
            case UV_EOF => {
              // done reading
              uv_read_stop(pipe)
              uv_close(pipe, onClose)
            }
            case n if n > 0 => {
              Zone { implicit z =>
                {
                  // success reading
                  val bytesRead: Ptr[Byte] =
                    alloc[Byte](nread.toULong + 1L.toULong)
                  string.strncpy(bytesRead, buf._1, nread.toULong)
                  !(bytesRead + nread) = 0 // set a null byte at the end
                  val part = fromCString(bytesRead)

                  // append this part to the full payload we're storing globally like animals
                  call.readResponse += part

                  if (!(buf._1 + buf._2 - 1L) == 0) {
                    // there is a null byte at the end, we're done reading
                    uv_read_stop(pipe)
                    uv_close(pipe, onClose)
                  } else if (nread.toULong != buf._2) {
                    // less chars than the actual buffer size, we're done reading
                    uv_read_stop(pipe)
                    uv_close(pipe, onClose)
                  } else {
                    // otherwise there is more stuff to be read, we'll be called again
                  }
                }
              }
            }
            case 0 => {
              // this means the read is still happening, we'll be called again, do nothing
            }
            case n if n < 0 && n != UV_EOF => {
              // error reading
              call.result.failure(
                UnixDomainSocketException(s"failed to read ($nread)}")
              )
              uv_read_stop(pipe)
              uv_close(pipe, onClose)
            }
          }

          // free buffer if it was allocated
          if (buf._2 > 0L.toULong) {
            stdlib.free(buf._1)
          }
        }
        case None => {}
      }
    }

  val onClose: CloseCB = (pipe: PipeHandle) => {
    calls.find(c => c.pipe == pipe) match {
      case Some(call) => {
        // after closing the pipe we free all the memory
        stdlib.free(call.pipe.asInstanceOf[Ptr[Byte]])
        stdlib.free(call.connect.asInstanceOf[Ptr[Byte]])
        stdlib.free(call.write.asInstanceOf[Ptr[Byte]])

        if (!call.result.isCompleted) {
          call.result.success(call.readResponse)
        }

        calls.remove(call)
      }
      case None => {}
    }
    ()
  }
}
