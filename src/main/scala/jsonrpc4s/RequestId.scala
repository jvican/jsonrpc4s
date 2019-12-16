package jsonrpc4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException

sealed trait RequestId
object RequestId {
  final case object Null extends RequestId
  final case class Number(value: Double) extends RequestId
  final case class String(value: java.lang.String) extends RequestId

  def apply(n: Int): RequestId = RequestId.Number(n.toDouble)
  def apply(n: Long): RequestId = RequestId.Number(n.toDouble)

  implicit val requestIdCodec: JsonValueCodec[RequestId] = new JsonValueCodec[RequestId] {
    def nullValue: RequestId = RequestId.Null
    def decodeValue(in: JsonReader, default: RequestId): RequestId = {
      in.setMark()
      if (in.isNextToken('"')) {
        in.rollbackToken()
        val str = in.readString(null)
        if (str == null) RequestId.Null
        else RequestId.String(str)
      } else {
        in.rollbackToMark()
        try RequestId.Number(in.readDouble())
        catch {
          case _: JsonReaderException =>
            in.rollbackToMark()
            in.readNullOrError("", "Expected valid JSON-RPC request id: string, numnber or null")
            // If null read succeeds, return it, otherwise error will be thrown
            RequestId.Null
        }
      }
    }

    def encodeValue(x: RequestId, out: JsonWriter): Unit = {
      x match {
        case Null => out.writeNull()
        case Number(n) =>
          if (n % 1 == 0) out.writeVal(n.toLong)
          else out.writeVal(n)
        case String(s) => out.writeVal(s)
      }
    }
  }
}
