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
import java.{util => ju}

final class LowLevelMessageReader(logger: LoggerSupport) {
  import LowLevelMessageReader.{ReadResult, SuccessfulRead, FailedRead}

  private[this] var readHeaders = false
  private[this] var readHeaderDelimiter = false
  private[this] var headers = new ju.LinkedHashMap[String, String]()

  def readFromBuf(buf: ByteBuffer, prev: SuccessfulRead): ReadResult = {
    if (!buf.hasRemaining()) {
      logger.warn("Can't start reading low-level JSON-RPC message from empty buffer!")
    }

    import scala.jdk.CollectionConverters.MapHasAsScala
    val currentPos = buf.position()
    val length = prev.carryover.length + buf.remaining()

    def updateCarryover(): ArrayBuffer[Byte] = {
      val dst = new Array[Byte](buf.remaining())
      buf.get(dst)
      prev.carryover.addAll(dst)
    }

    def isEndOfHeaderSection(idx: Int): Boolean =
      isEndOfHeader(idx)
    def isEndOfHeader(idx: Int): Boolean =
      (buf.get(idx) == '\r' && buf.get(idx + 1) == '\n')
    def isSplitHeader(idx: Int): Boolean =
      prev.carryover.length > 0 && prev.carryover.last == '\r' && buf.get(idx) == '\n'

    if (length < 2) {
      SuccessfulRead(None, updateCarryover())
    } else {
      var i = 0
      var headerStart = i
      var headerEnd = false

      while (!readHeaders && !headerEnd) {
        val isDelimiterSplitAtPosition =
          i == 0 && prev.carryover.length > 0 && prev.carryover.last == '\r' && buf.get(i) == '\n'

        if (!isDelimiterSplitAtPosition) {
          while ((i + 2 <= length && !isEndOfHeader(currentPos + i))) {
            i += 1
          }
        }

        if (!isDelimiterSplitAtPosition && (i + 2 > length || !isEndOfHeader(currentPos + i))) {
          return SuccessfulRead(None, updateCarryover())
        } else {
          // Remove last '\r' if it's in the carryover
          if (isDelimiterSplitAtPosition)
            prev.carryover.remove(prev.carryover.length - 1)

          val bufLen = i - headerStart
          val len = bufLen + prev.carryover.length

          if (len > 0) {
            val dst = new Array[Byte](len)
            val endIdx = prev.carryover.copyToArray(dst)
            buf.get(dst, endIdx, bufLen)
            prev.carryover.clear()

            val headerLine = new String(dst, StandardCharsets.UTF_8)
            headerLine.split(":") match {
              case Array(key, value) => headers.put(key.trim, value.trim)
              case _ => logger.error(s"Malformed header: $headerLine")
            }

            // Increment by 2 because i marks beginning of `\r\n`,
            // or by 1 because it marks `\n` and `r` was removed above
            if (isDelimiterSplitAtPosition) i += 1 else i += 2

            buf.position(currentPos + i)
            headerStart = i
          } else {
            // If we encounter header with no content, we found the header
            // delimiter. Let's stop the loop and let the parser parse it
            println("HEADER END")
            headerEnd = true
            if (isDelimiterSplitAtPosition) i += 1 else i += 2
            buf.position(currentPos + i)
            readHeaderDelimiter = true
          }
        }
      }

      readHeaders = true

      if (!readHeaderDelimiter && (i + 2 > length)) {
        // We haven't gotten to the \r\n dividing headers and body
        SuccessfulRead(None, updateCarryover())
      } else if (!readHeaderDelimiter && !isSplitHeader(currentPos + 1) && !isEndOfHeaderSection(
                   currentPos + i
                 )) {
        FailedRead(
          new RuntimeException(s"Expected \\r\\n division between headers and body in msg!")
        )
      } else {
        if (!readHeaderDelimiter) {
          if (isSplitHeader(currentPos + 1)) {
            i += 1
            prev.carryover.remove(prev.carryover.length - 1)
          } else {
            i += 2
          }

          buf.position(currentPos + i)
          readHeaderDelimiter = true
        }

        Option(headers.get("Content-Length")) match {
          case Some(n) =>
            val contentLength = n.toInt
            val currentBodyLength = length - i + prev.carryover.length
            if (currentBodyLength < contentLength) {
              SuccessfulRead(None, updateCarryover())
            } else {
              val contentBytes = new Array[Byte](contentLength)
              val endIdx = prev.carryover.copyToArray(contentBytes)
              buf.get(contentBytes, endIdx, contentLength - prev.carryover.length)
              val msg = new LowLevelMessage(headers.asScala.toMap, contentBytes)

              // Reinitialize all of the state
              readHeaders = false
              readHeaderDelimiter = false
              headers.clear()

              prev.carryover.clear()
              SuccessfulRead(Some(msg), prev.carryover)
            }

          case None =>
            val headerMap = headers.asScala.toMap
            FailedRead(
              new RuntimeException(s"Missing Content-Length key in msg header! Found: $headerMap")
            )
        }
      }
    }
  }

  private[this] val EmptyPair = "" -> ""
  private[this] var contentLength = -1
  def currentContentLength: Int = contentLength

  private[this] var header = Map.empty[String, String]
  private[this] def atDelimiter(idx: Int, data: ArrayBuffer[Byte]): Boolean = {
    data.size >= idx + 4 && { println("condition 1"); true } &&
    data(idx) == '\r' && { println("condition 2"); true } &&
    data(idx + 1) == '\n' && { println("condition 3"); true } &&
    data(idx + 2) == '\r' && { println("condition 4"); true } &&
    data(idx + 3) == '\n'
  }

  import LowLevelMessageReader.ReadResult2
  def readHeaders2(data: ArrayBuffer[Byte]): ReadResult2 = {
    logger.info("read headers")
    if (data.size < 4) ReadResult2(None, Ack.Continue)
    else {
      logger.info("read headers II")
      var i = 0
      while (data.size >= i + 4 && !atDelimiter(i, data)) {
        i += 1
      }
      data.length
      logger.info(s"idx $i")
      logger.info(s"size ${data.size}")
      logger.info(s"data " + data.mkString)
      if (!atDelimiter(i, data)) ReadResult2(None, Ack.Continue)
      else {
        logger.info("read headers III")
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
                ReadResult2(None, Ack.Continue)
            }
          case _ =>
            logger.error(s"Missing Content-Length key in headers $pairs")
            ReadResult2(None, Ack.Continue)
        }
      }
    }
  }

  def readContent(data: ArrayBuffer[Byte]): ReadResult2 = {
    logger.info("read content")
    if (contentLength > data.size) {
      ReadResult2(None, Ack.Continue)
    } else {
      val contentBytes = new Array[Byte](contentLength)
      data.copyToArray(contentBytes)
      data.remove(0, contentLength)
      contentLength = -1
      val msg = new LowLevelMessage(header, contentBytes)
      ReadResult2(Some(msg), Ack.Stop)
    }
  }

  //def readWholeMessage()
}

object LowLevelMessageReader {
  def read(buf: ByteBuffer, logger: LoggerSupport): Option[LowLevelMessage] = {
    val reader = new LowLevelMessageReader(logger)
    reader.readFromBuf(buf, SuccessfulRead(None)) match {
      case FailedRead(err) =>
        throw err
      case SuccessfulRead(msg, carryover) =>
        msg
    }
  }

  def streamReader(logger: LoggerSupport): Operator[ByteBuffer, LowLevelMessage] = {
    new Operator[ByteBuffer, LowLevelMessage] {
      def apply(
          out: Subscriber[LowLevelMessage]
      ): Subscriber[ByteBuffer] = {
        new Subscriber[ByteBuffer] {
          //private[this] val data = ArrayBuffer.empty[Byte]

          private[this] val reader = new LowLevelMessageReader(logger)
          override implicit val scheduler: Scheduler = out.scheduler
          override def onError(ex: Throwable): Unit = out.onError(ex)
          override def onComplete(): Unit = {
            out.onComplete()
          }

          private[this] var carryover = new ArrayBuffer[Byte](0)
          override def onNext(buf: ByteBuffer): Future[Ack] = {
            def loopUntilBufferExhaustion(prev: SuccessfulRead): Future[Ack] = {
              println("loop")
              println(buf.position())
              println(buf.remaining())
              println(buf.limit())
              reader.readFromBuf(buf, prev) match {
                case FailedRead(err) =>
                  // Propagate error and continue receiving data upstream
                  out.onError(err)
                  Ack.Continue
                case SuccessfulRead(None, carryover0) =>
                  carryover = carryover0
                  Ack.Continue
                case SuccessfulRead(Some(msg), carryover0) =>
                  out.onNext(msg).flatMap {
                    case Ack.Stop => Ack.Stop
                    case Ack.Continue =>
                      carryover = carryover0
                      if (!buf.hasRemaining()) Ack.Continue
                      else loopUntilBufferExhaustion(SuccessfulRead(None, carryover))
                  }
              }
            }

            if (buf.hasRemaining()) loopUntilBufferExhaustion(SuccessfulRead(None, carryover))
            else {
              logger.warn("Expected non-empty byte buffer to read message")
              Ack.Continue
            }
          }

          /*
          override def onNext(elem: ByteBuffer): Future[Ack] = {
            val array = new Array[Byte](elem.remaining())
            elem.get(array)
            data ++= array
            def loopUntilBufferExhaustion(result: ReadResult2): Future[Ack] = {
              result.msg match {
                case None => result.ack
                case Some(msg) =>
                  out.onNext(msg).flatMap {
                    case Ack.Continue => loopUntilBufferExhaustion(reader.readHeaders2(data))
                    case Ack.Stop => Ack.Stop
                  }
              }
            }

            loopUntilBufferExhaustion(
              if (reader.currentContentLength < 0) reader.readHeaders2(data)
              else reader.readContent(data)
            )
          }
         */
        }
      }
    }
  }

  private[LowLevelMessageReader] case class ReadResult2(
      msg: Option[LowLevelMessage],
      ack: Future[Ack]
  )

  private[LowLevelMessageReader] sealed trait ReadResult {
    def ack: Future[Ack]
  }

  private[LowLevelMessageReader] final case class FailedRead(err: Throwable) extends ReadResult {
    def ack: Future[Ack] = Ack.Stop
  }

  private[LowLevelMessageReader] final case class SuccessfulRead(
      msg: Option[LowLevelMessage],
      carryover: ArrayBuffer[Byte] = new ArrayBuffer[Byte]()
  ) extends ReadResult {
    def ack: Future[Ack] = Ack.Continue
  }
}
