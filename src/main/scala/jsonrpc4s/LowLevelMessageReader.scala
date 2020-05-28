package jsonrpc4s

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import monix.execution.Ack
import monix.execution.Scheduler
import monix.reactive.Observable.Operator
import monix.reactive.observers.Subscriber
import scribe.LoggerSupport

final class LowLevelMessageReader(logger: LoggerSupport) {
  // TODO: Benchmark and consider `Queue[ByteBuffer]` over `ArrayBuffer[Byte]`

  private[this] val EmptyPair = "" -> ""
  private[this] var contentLength = -1
  def currentContentLength: Int = contentLength

  private[this] var header = Map.empty[String, String]
  private[this] def atDelimiter(idx: Int, data: ArrayBuffer[Byte]): Boolean = {
    data.size >= idx + 4 &&
    data(idx) == '\r' &&
    data(idx + 1) == '\n' &&
    data(idx + 2) == '\r' &&
    data(idx + 3) == '\n'
  }

  import LowLevelMessageReader.ReadResult
  def readHeaders(data: ArrayBuffer[Byte]): ReadResult = {
    if (data.size < 4) ReadResult(None, Ack.Continue)
    else {
      var i = 0
      while (i + 4 < data.size && !atDelimiter(i, data)) {
        i += 1
      }
      if (!atDelimiter(i, data)) ReadResult(None, Ack.Continue)
      else {
        val bytes = new Array[Byte](i)
        data.copyToArray(bytes)
        data.remove(0, i + 4)
        val headers = new String(bytes, StandardCharsets.US_ASCII)

        // Parse other headers in JSON-RPC messages even if we only use `Content-Length` below
        val pairs: Map[String, String] = headers
          .split("\r\n")
          .iterator
          .filterNot(_.trim.isEmpty)
          .map { line =>
            line.split(":") match {
              case Array(key, value) => key.trim -> value.trim
              case _ =>
                logger.error(s"Malformed input: $line")
                EmptyPair
            }
          }
          .toMap

        pairs.get("Content-Length") match {
          case Some(n) =>
            try {
              contentLength = n.toInt
              header = pairs
              readContent(data)
            } catch {
              case _: NumberFormatException =>
                logger.error(
                  s"Expected Content-Length to be a number, obtained $n"
                )
                ReadResult(None, Ack.Continue)
            }
          case _ =>
            logger.error(s"Missing Content-Length key in headers $pairs")
            ReadResult(None, Ack.Continue)
        }
      }
    }
  }

  def readContent(data: ArrayBuffer[Byte]): ReadResult = {
    if (contentLength > data.size) ReadResult(None, Ack.Continue)
    else {
      val contentBytes = new Array[Byte](contentLength)
      data.copyToArray(contentBytes)
      data.remove(0, contentLength)
      contentLength = -1
      val msg = new LowLevelMessage(header, contentBytes)
      ReadResult(Some(msg), Ack.Stop)
    }
  }

  //def readWholeMessage()
}

object LowLevelMessageReader {
  def read(buf: ByteBuffer, logger: LoggerSupport): Option[LowLevelMessage] = {
    val data = ArrayBuffer.empty[Byte]
    val array = new Array[Byte](buf.remaining())
    buf.get(array)
    data ++= array

    val reader = new LowLevelMessageReader(logger)
    val result = reader.readHeaders(data)

    // Guarantee that this reader invariant holds
    assert(result.msg.isDefined && result.ack != Ack.Continue)
    result.msg
  }

  def streamReader(logger: LoggerSupport): Operator[ByteBuffer, LowLevelMessage] = {
    new Operator[ByteBuffer, LowLevelMessage] {
      def apply(
          out: Subscriber[LowLevelMessage]
      ): Subscriber[ByteBuffer] = {
        new Subscriber[ByteBuffer] {
          private[this] val data = ArrayBuffer.empty[Byte]
          private[this] val reader = new LowLevelMessageReader(logger)
          override implicit val scheduler: Scheduler = out.scheduler
          override def onError(ex: Throwable): Unit = out.onError(ex)
          override def onComplete(): Unit = {
            out.onComplete()
          }

          override def onNext(elem: ByteBuffer): Future[Ack] = {
            val array = new Array[Byte](elem.remaining())
            elem.get(array)
            data ++= array
            def loopUntilBufferExhaustion(result: ReadResult): Future[Ack] = {
              result.msg match {
                case None => result.ack
                case Some(msg) =>
                  out.onNext(msg).flatMap {
                    case Ack.Continue => loopUntilBufferExhaustion(reader.readHeaders(data))
                    case Ack.Stop => Ack.Stop
                  }
              }
            }

            loopUntilBufferExhaustion(
              if (reader.currentContentLength < 0) reader.readHeaders(data)
              else reader.readContent(data)
            )
          }
        }
      }
    }
  }

  private[LowLevelMessageReader] case class ReadResult(
      msg: Option[LowLevelMessage],
      ack: Future[Ack]
  )

}
