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
import java.nio.channels.WritableByteChannel

/**
 * A trait that writes JSON-RPC messages to an output stream.
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
sealed trait LowLevelMessageWriter {
  protected val baos = new ByteArrayOutputStream(2048)
  protected val headerOut = LowLevelMessageWriter.headerWriter(baos)
}

/**
 * @inheritdoc
 *
 * @param out is an output stream where the writer writes directly.
 * @param logger is a logger where we trace messages if level allows.
 */
final class LowLevelChannelMessageWriter(
    channel: WritableByteChannel,
    logger: LoggerSupport
) extends LowLevelMessageWriter {
  def write(msg: Message): Future[Ack] = {
    val protocolMsg = LowLevelMessage.fromMsg(msg)
    logger.trace(s" --> ${new String(protocolMsg.content, StandardCharsets.UTF_8)}")

    val buf = baos.synchronized {
      baos.reset()
      LowLevelMessageWriter.writeToByteBuffer(protocolMsg, baos, headerOut)
    }

    channel.synchronized { channel.write(buf) }
    Ack.Continue
  }
}

/**
 * @inheritdoc
 *
 * @param out is an byte buffer observer where the writer writes the messages.
 * @param logger is a logger where we trace messages if level allows.
 */
final class LowLevelByteBufferMessageWriter(
    out: Observer[ByteBuffer],
    logger: LoggerSupport
) extends LowLevelMessageWriter {
  def write(msg: Message): Future[Ack] = {
    val protocolMsg = LowLevelMessage.fromMsg(msg)
    logger.trace(s" --> ${new String(protocolMsg.content, StandardCharsets.UTF_8)}")

    val buf = baos.synchronized {
      baos.reset()
      LowLevelMessageWriter.writeToByteBuffer(protocolMsg, baos, headerOut)
    }

    // No need to lock here, downstream observer *must* process `onNext` well in isolation
    out.onNext(buf)
  }
}

object LowLevelMessageWriter {
  def headerWriter(out: OutputStream): PrintWriter = {
    new PrintWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII))
  }

  def write(message: LowLevelMessage): ByteBuffer = {
    val out = new ByteArrayOutputStream()
    val header = headerWriter(out)
    writeToByteBuffer(message, out, header)
  }

  def writeDirectlyToOut(
      message: LowLevelMessage,
      out: OutputStream
  ): Unit = {
    val headerOut = LowLevelMessageWriter.headerWriter(out)
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
  }

  def writeToByteBuffer(
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
