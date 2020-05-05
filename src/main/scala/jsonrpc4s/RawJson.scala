package jsonrpc4s

import java.nio.charset.StandardCharsets
import java.{util => ju}

import scala.util.Try
import scala.util.hashing.MurmurHash3
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException

final case class RawJson(value: Array[Byte]) {
  override lazy val hashCode: Int = MurmurHash3.arrayHash(value)

  override def equals(obj: Any): Boolean = {
    obj match {
      case that: RawJson => ju.Arrays.equals(value, that.value)
      case _ => false
    }
  }

  def duplicate: RawJson = {
    val dest = new Array[Byte](value.length)
    System.arraycopy(value, 0, dest, 0, value.length)
    RawJson(dest)
  }

  override def toString: String = {
    Try(new String(value, StandardCharsets.UTF_8)).toOption
      .getOrElse(value.toString)
  }
}

object RawJson {
  import com.github.plokhotnyuk.jsoniter_scala.core.{writeToArray, readFromArray}
  import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, JsonReader, JsonWriter}

  implicit val codec: JsonValueCodec[RawJson] = new JsonValueCodec[RawJson] {
    val nullValue: RawJson = new RawJson(new Array[Byte](0))
    def encodeValue(x: RawJson, out: JsonWriter): Unit = out.writeRawVal(x.value)
    def decodeValue(in: JsonReader, default: RawJson): RawJson = new RawJson(in.readRawValAsBytes())
  }

  val emptyObj: RawJson = RawJson("{}".getBytes(StandardCharsets.UTF_8))
  val nullValue: RawJson = RawJson("null".getBytes(StandardCharsets.UTF_8))

  def toJson[T: JsonValueCodec](value: T): RawJson = {
    RawJson(writeToArray(value))
  }

  def parseJsonTo[T: JsonValueCodec](json: RawJson): Either[Throwable, T] = {
    try Right(readFromArray[T](json.value))
    catch {
      case err: NullPointerException => Left(err)
      case err: JsonReaderException => Left(err)
    }
  }
}
