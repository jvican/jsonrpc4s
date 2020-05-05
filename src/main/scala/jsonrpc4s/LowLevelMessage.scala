package jsonrpc4s

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util

import monix.reactive.Observable
import scribe.LoggerSupport

final class LowLevelMessage(
    val header: Map[String, String],
    val content: Array[Byte]
) {

  override def equals(obj: scala.Any): Boolean =
    this.eq(obj.asInstanceOf[Object]) || {
      obj match {
        case m: LowLevelMessage =>
          header.equals(m.header) &&
            util.Arrays.equals(content, m.content)
      }
    }

  override def toString: String = {
    val bytes = LowLevelMessageWriter.write(this)
    StandardCharsets.UTF_8.decode(bytes).toString
  }
}

object LowLevelMessage {
  import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

  def fromMsg(msg: Message): LowLevelMessage = fromBytes(writeToArray(msg))
  def fromBytes(bytes: Array[Byte]): LowLevelMessage = {
    new LowLevelMessage(Map("Content-Length" -> bytes.length.toString), bytes)
  }

  def fromInputStream(
      in: InputStream,
      logger: LoggerSupport
  ): Observable[LowLevelMessage] = {
    // FIXME: Use bracket to handle this resource correctly if something fails
    fromBytes(Observable.fromInputStreamUnsafe(in), logger)
  }

  def fromBytes(
      in: Observable[Array[Byte]],
      logger: LoggerSupport
  ): Observable[LowLevelMessage] =
    fromByteBuffers(in.map(ByteBuffer.wrap), logger)

  def fromByteBuffers(
      in: Observable[ByteBuffer],
      logger: LoggerSupport
  ): Observable[LowLevelMessage] = {
    in.executeAsync.liftByOperator(new LowLevelMessageParser(logger))
  }
}
