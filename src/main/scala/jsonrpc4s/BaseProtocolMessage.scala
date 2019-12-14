package jsonrpc4s

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util

import monix.reactive.Observable
import scribe.LoggerSupport

final class BaseProtocolMessage(
    val header: Map[String, String],
    val content: Array[Byte]
) {

  override def equals(obj: scala.Any): Boolean =
    this.eq(obj.asInstanceOf[Object]) || {
      obj match {
        case m: BaseProtocolMessage =>
          header.equals(m.header) &&
            util.Arrays.equals(content, m.content)
      }
    }

  override def toString: String = {
    val bytes = MessageWriter.write(this)
    StandardCharsets.UTF_8.decode(bytes).toString
  }
}

object BaseProtocolMessage {
  import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

  def fromMsg(msg: Message): BaseProtocolMessage = fromBytes(writeToArray(msg))
  def fromBytes(bytes: Array[Byte]): BaseProtocolMessage = {
    new BaseProtocolMessage(Map("Content-Length" -> bytes.length.toString), bytes)
  }

  def fromInputStream(
      in: InputStream,
      logger: LoggerSupport
  ): Observable[BaseProtocolMessage] = {
    // FIXME: Use bracket to handle this resource correctly if something fails
    fromBytes(Observable.fromInputStreamUnsafe(in), logger)
  }

  def fromBytes(
      in: Observable[Array[Byte]],
      logger: LoggerSupport
  ): Observable[BaseProtocolMessage] =
    fromByteBuffers(in.map(ByteBuffer.wrap), logger)

  def fromByteBuffers(
      in: Observable[ByteBuffer],
      logger: LoggerSupport
  ): Observable[BaseProtocolMessage] =
    in.executeAsync.liftByOperator(new BaseProtocolMessageParser(logger))
}
