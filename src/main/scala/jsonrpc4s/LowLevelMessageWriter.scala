package jsonrpc4s

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import monix.execution.Ack
import monix.reactive.Observer
import scribe.LoggerSupport

/**
 * A class to write JSON-RPC messages to an output stream.
 * It produces the following format:
 *
 * <Header> '\r\n' <Content>
 *
 * Header := FieldName ':' FieldValue '\r\n'
 *
 * Currently there are two defined header fields:
 * - 'Content-Length' in bytes (required)
 * - 'Content-Type' (string), defaults to 'application/vscode-jsonrpc; charset=utf8'
 *
 * @note The header part is defined to be ASCII encoded, while the content part is UTF8.
 */
class LowLevelMessageWriter(out: Observer[ByteBuffer], logger: LoggerSupport) {

  /** Lock protecting the output stream, so multiple writes don't mix message chunks. */
  private val lock = new Object

  private val baos = new ByteArrayOutputStream()
  private val headerOut = LowLevelMessageWriter.headerWriter(baos)

  /**
   * Write a message to the output stream. This method can be called from multiple threads,
   * but it may block waiting for other threads to finish writing.
   */
  def write(msg: Message): Future[Ack] = lock.synchronized {
    baos.reset()
    val protocol = LowLevelMessage.fromMsg(msg)
    logger.trace {
      val json = new String(protocol.content, StandardCharsets.UTF_8)
      s" --> $json"
    }
    val byteBuffer = LowLevelMessageWriter.write(protocol, baos, headerOut)
    out.onNext(byteBuffer)
  }
}

object LowLevelMessageWriter {
  def headerWriter(out: OutputStream): PrintWriter = {
    new PrintWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII))
  }

  def write(message: LowLevelMessage): ByteBuffer = {
    val out = new ByteArrayOutputStream()
    val header = headerWriter(out)
    write(message, out, header)
  }

  def write(
      message: LowLevelMessage,
      out: ByteArrayOutputStream,
      headerOut: PrintWriter
  ): ByteBuffer = {
    message.header.foreach {
      case (key, value) =>
        headerOut.write(key)
        headerOut.write(": ")
        headerOut.write(value)
        headerOut.write("\r\n")
    }
    headerOut.write("\r\n")
    headerOut.flush()
    out.write(message.content)
    out.flush()
    val buffer = ByteBuffer.wrap(out.toByteArray, 0, out.size())
    buffer
  }
}
