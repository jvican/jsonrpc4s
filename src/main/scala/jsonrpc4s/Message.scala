package jsonrpc4s

import monix.eval.Task
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader

sealed trait Message {
  def jsonrpc: String
}

object Message {
  implicit val messageCodec = new JsonValueCodec[Message] {
    def decodeValue(in: JsonReader, default: Message): Message = {
      val json = RawJson.codec.decodeValue(in, RawJson.codec.nullValue)
      val msg = {
        RawJson.parseJsonTo[Request](json) match {
          case Right(msg) => msg
          case Left(err) =>
            RawJson.parseJsonTo[Notification](json) match {
              case Right(msg) => msg
              case Left(err) =>
                RawJson.parseJsonTo[Response](json) match {
                  case Right(msg) => msg
                  case Left(err) =>
                    in.decodeError(
                      "Invalid JSON-RPC message, expected request, notification or response type"
                    )
                }
            }
        }
      }

      if (msg == null) {
        in.decodeError("Invalid JSON-RPC message, expected request, notification or response type")
      } else if (msg.jsonrpc != "2.0") {
        in.decodeError(s"Expected JSON-RPC version 2.0 message, obtained version ${msg.jsonrpc}")
      } else {
        msg
      }
    }

    def encodeValue(msg: Message, out: JsonWriter): Unit = {
      msg match {
        case r: Request => Request.requestCodec.encodeValue(r, out)
        case r: Notification => Notification.notificationCodec.encodeValue(r, out)
        case r: Response => Response.responseCodec.encodeValue(r, out)
      }
    }

    def nullValue: Message = null
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
