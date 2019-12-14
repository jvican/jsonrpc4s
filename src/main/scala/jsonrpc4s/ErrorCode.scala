package jsonrpc4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter

sealed abstract class ErrorCode(val value: Int)
case object ErrorCode {
  case object ParseError extends ErrorCode(-32700)
  case object InvalidRequest extends ErrorCode(-32600)
  case object MethodNotFound extends ErrorCode(-32601)
  case object InvalidParams extends ErrorCode(-32602)
  case object InternalError extends ErrorCode(-32603)
  case object RequestCancelled extends ErrorCode(-32800)

  // Server error: -32000 to -32099
  case class Unknown(override val value: Int) extends ErrorCode(value)

  val builtin: Array[ErrorCode] = Array(
    ParseError,
    InvalidRequest,
    MethodNotFound,
    InvalidParams,
    InternalError,
    RequestCancelled
  )

  implicit val errorCodeCodec: JsonValueCodec[ErrorCode] = new JsonValueCodec[ErrorCode] {
    def nullValue: ErrorCode = null
    def encodeValue(x: ErrorCode, out: JsonWriter): Unit = out.writeVal(x.value)
    def decodeValue(in: JsonReader, default: ErrorCode): ErrorCode = {
      val x = in.readInt()
      builtin.find(_.value == x).getOrElse(Unknown(x))
    }
  }
}
