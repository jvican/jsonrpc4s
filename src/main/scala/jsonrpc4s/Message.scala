package jsonrpc4s

import scribe.LoggerSupport

import monix.eval.Task
import monix.execution.Ack
import monix.reactive.Observer

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader

sealed trait Message {
  def jsonrpc: String
}

object Message {
  implicit val messageCodec: JsonValueCodec[Message] = new JsonValueCodec[Message] {
    //                   id method result error params jsonrpc
    // Request           1  2      0      0     0/16   32
    // Response.None     0  0      0      0     0      32
    // Response.Error    1  0      0      8     0      32
    // Response.Success  1  0      4      0     0      32
    // Notification      0  2      0      0     0/16   32
    def decodeValue(in: JsonReader, default: Message): Message = {
      val msg: Message =
        if (in.isNextToken('{')) {
          var p = 63
          var id: RequestId = RequestId.requestIdCodec.nullValue
          var method: String = null
          var result: RawJson = RawJson.codec.nullValue
          var error: ErrorObject = ErrorObject.errorObjectCodec.nullValue
          var params: Option[RawJson] = None
          var jsonrpc: String = null
          if (!in.isNextToken('}')) {
            in.rollbackToken()
            do {
              val l = in.readKeyAsCharBuf();
              if (in.isCharBufEqualsTo(l, "id")) {
                p = validateAndSwitchFieldMask(in, l, p, 1)
                id = RequestId.requestIdCodec.decodeValue(in, id)
              } else if (in.isCharBufEqualsTo(l, "method")) {
                p = validateAndSwitchFieldMask(in, l, p, 2)
                method = in.readString(method)
              } else if (in.isCharBufEqualsTo(l, "result")) {
                p = validateAndSwitchFieldMask(in, l, p, 4)
                result = RawJson(in.readRawValAsBytes())
              } else if (in.isCharBufEqualsTo(l, "error")) {
                p = validateAndSwitchFieldMask(in, l, p, 8)
                error = ErrorObject.errorObjectCodec.decodeValue(in, error)
              } else if (in.isCharBufEqualsTo(l, "params")) {
                p = validateAndSwitchFieldMask(in, l, p, 16)
                params = Some(RawJson(in.readRawValAsBytes()))
              } else if (in.isCharBufEqualsTo(l, "jsonrpc")) {
                p = validateAndSwitchFieldMask(in, l, p, 32)
                jsonrpc = in.readString(jsonrpc)
                if (jsonrpc != "2.0") {
                  in.decodeError(
                    s"Expected JSON-RPC version 2.0 message, obtained version $jsonrpc"
                  )
                }
              } else {
                in.skip() // or raise an error here in case of no other fields are not allowed
              }
            } while (in.isNextToken(','))
            if (!in.isCurrentToken('}')) {
              in.objectEndOrCommaError()
            }
          }
          p match {
            case 12 | 28 => Request(method, params, id, jsonrpc)
            case 31 => Response.None
            case 22 => Response.Error(error, id, jsonrpc)
            case 26 => Response.Success(result, id, jsonrpc)
            case 13 | 29 => Notification(method, params, jsonrpc)
            case _ => default
          }
        } else in.readNullOrTokenError(default, '{')
      if (msg == default) {
        in.decodeError("Invalid JSON-RPC message, expected request, notification or response type")
      }
      msg
    }

    def encodeValue(msg: Message, out: JsonWriter): Unit = {
      msg match {
        case r: Request => Request.requestCodec.encodeValue(r, out)
        case r: Notification => Notification.notificationCodec.encodeValue(r, out)
        case r: Response => Response.responseCodec.encodeValue(r, out)
      }
    }

    def nullValue: Message = null

    private def validateAndSwitchFieldMask(in: JsonReader, l: Int, p: Int, mask: Int): Int = {
      if ((p & mask) != 0) {
        p ^ mask
      } else {
        in.duplicatedKeyError(l)
      }
    }
  }

  /**
   * An observer implementation that writes JSON-RPC message to the
   * underlying output. The output is internally transformed into
   * a [[java.nio.channels.WritableByteChannel]] for efficiency.
   *
   * @param out is either an output stream or a channel.
   * @param logger is the logger used to trace written messages and exceptions.
   */
  def messagesToOutput(
      out: Either[OutputStream, WritableByteChannel],
      logger: LoggerSupport
  ): Observer.Sync[Message] = {
    new Observer.Sync[Message] {
      private[this] val lock = new Object()
      private[this] var isClosed: Boolean = false
      private[this] val (channel, underlying) = out match {
        case Left(out) => Channels.newChannel(out) -> Some(out)
        case Right(channel) => channel -> None
      }

      private[this] val writer = new LowLevelChannelMessageWriter(channel, logger)
      override def onNext(elem: Message): Ack = lock.synchronized {
        if (isClosed) Ack.Stop
        else {
          try {
            writer.write(elem) match {
              case Ack.Continue => Ack.Continue
              case Ack.Stop => Ack.Stop
              case ack => Ack.Continue
            }
          } catch {
            case err: java.io.IOException =>
              logger.trace(s"Found error when writing ${elem}, closing channel!", err)
              isClosed = true
              Ack.Stop
          }
        }
      }

      override def onError(err: Throwable): Unit = {
        logger.trace("Caught error, stopped writing JSON-RPC messages to output stream!", err)
        onComplete()
      }

      override def onComplete(): Unit = {
        lock.synchronized {
          channel.close()
          underlying.foreach(_.close())
          isClosed = true
        }
      }
    }
  }

  def messagesToByteBuffer(
      out: Observer.Sync[ByteBuffer],
      logger: LoggerSupport
  ): Observer.Sync[Message] = {
    new Observer.Sync[Message] {
      private[this] var isClosed = false
      private[this] val writer = new LowLevelByteBufferMessageWriter(out, logger)
      override def onNext(elem: Message): Ack = writer.synchronized {
        if (isClosed) Ack.Stop
        else {
          try {
            writer.write(elem)
            Ack.Continue
          } catch {
            case err: java.io.IOException =>
              logger.trace(s"Found error when writing ${elem}, closing channel!", err)
              isClosed = true
              Ack.Stop
          }
        }
      }

      override def onError(err: Throwable): Unit = {
        logger.trace("Caught error, stopped writing JSON-RPC messages to byte buffer!", err)
        onComplete()
      }

      override def onComplete(): Unit = {
        out.synchronized {
          if (isClosed) {
            out.onComplete()
            isClosed = true
          }
        }
      }
    }
  }
}

final case class Request(
    method: String,
    params: Option[RawJson],
    id: RequestId,
    jsonrpc: String = "2.0"
) extends Message {
  def toError(code: ErrorCode, message: String): Response =
    Response.error(ErrorObject(code, message, None), id)
}

object Request {
  implicit val requestCodec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
}

final case class Notification(
    method: String,
    params: Option[RawJson],
    jsonrpc: String = "2.0"
) extends Message

object Notification {
  implicit val notificationCodec: JsonValueCodec[Notification] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
}

sealed trait Response extends Message {
  def isSuccess: Boolean = this.isInstanceOf[Response.Success]
}

object Response {
  // A case that doesn't exist in JSON-RPC but that exists to signal no response action
  final case object None extends Response { val jsonrpc: String = "2.0" }

  final case class Success(
      result: RawJson,
      id: RequestId,
      jsonrpc: String = "2.0"
  ) extends Response

  final case class Error(
      error: ErrorObject,
      id: RequestId,
      jsonrpc: String = "2.0"
  ) extends Response

  implicit val errorCodec: JsonValueCodec[Error] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
  implicit val successCodec: JsonValueCodec[Success] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))

  implicit val responseCodec: JsonValueCodec[Response] = new JsonValueCodec[Response] {
    def nullValue: Response = null
    def encodeValue(x: Response, out: JsonWriter): Unit = {
      x match {
        case r: Response.Success => successCodec.encodeValue(r, out)
        case r: Response.Error => errorCodec.encodeValue(r, out)
        case Response.None => ()
      }
    }

    def decodeValue(in: JsonReader, default: Response): Response = {
      val json = RawJson.codec.decodeValue(in, RawJson.codec.nullValue)
      RawJson.parseJsonTo[Success](json) match {
        case Right(msg) => msg
        case Left(err) =>
          RawJson.parseJsonTo[Error](json) match {
            case Right(msg) => msg
            case Left(err) =>
              in.decodeError("Failed to decode JSON-RPC message, missing 'result' or 'error'")
          }
      }
    }
  }

  def ok(result: RawJson, id: RequestId): Response = success(result, id)
  def okAsync[T](value: T): Task[Either[Response.Error, T]] = Task(Right(value))
  def success(result: RawJson, id: RequestId): Response = Success(result, id)
  def error(error: ErrorObject, id: RequestId): Response.Error = Error(error, id)

  def internalError(message: String): Response.Error =
    internalError(message, RequestId.Null)
  def internalError(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.InternalError, message, scala.None), id)
  def invalidParams(message: String): Response.Error =
    invalidParams(message, RequestId.Null)
  def invalidParams(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.InvalidParams, message, scala.None), id)

  def invalidRequest(message: String): Response.Error = {
    Error(
      ErrorObject(ErrorCode.InvalidRequest, message, scala.None),
      RequestId.Null
    )
  }

  def cancelled(id: RequestId): Response.Error = {
    Error(ErrorObject(ErrorCode.RequestCancelled, "", scala.None), id)
  }

  def parseError(message: String): Response.Error =
    Error(ErrorObject(ErrorCode.ParseError, message, scala.None), RequestId.Null)
  def methodNotFound(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.MethodNotFound, message, scala.None), id)
}
