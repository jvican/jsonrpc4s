package jsonrpc4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException

sealed trait RequestId
object RequestId {
  import jsonrpc4s.BaseMessageCodecs.{stringCodec, doubleCodec}
  final case object Null extends RequestId
  final case class NumberRequest(value: Double) extends RequestId
  final case class StringRequest(value: String) extends RequestId

  def apply(n: Int): RequestId = RequestId.NumberRequest(n.toDouble)
  def apply(n: Long): RequestId.NumberRequest = RequestId.NumberRequest(n.toDouble)

  implicit val requestIdCodec: JsonValueCodec[RequestId] = new JsonValueCodec[RequestId] {
    def nullValue: RequestId = RequestId.Null
    def decodeValue(in: JsonReader, default: RequestId): RequestId = {
      val json = RawJson.codec.decodeValue(in, RawJson.codec.nullValue)
      try {
        RequestId.NumberRequest(readFromString[Double](new String(json.value)))
      } catch {
        case err: JsonReaderException =>
          try {
            RequestId.StringRequest(readFromString[String](new String(json.value)))
          } catch {
            case _: JsonReaderException =>
              in.readNullOrError("", "Expected valid JSON-RPC request id: string, numnber or null")
              // If null read succeeds, return it, otherwise error will be thrown
              RequestId.Null
          }
      }
    }

    def encodeValue(x: RequestId, out: JsonWriter): Unit = {
      x match {
        case Null => out.writeNull()
        case NumberRequest(n) =>
          if (n % 1 == 0) out.writeVal(n.toLong)
          else out.writeVal(n)
        case StringRequest(s) => out.writeVal(s)
      }
    }
  }
}
