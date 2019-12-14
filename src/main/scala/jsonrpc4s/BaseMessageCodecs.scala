package jsonrpc4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig

object BaseMessageCodecs {
  implicit val unitCodec: JsonValueCodec[Unit] = {
    final case class Empty()
    val empty = Empty()
    val emptyCodec = JsonCodecMaker.make[Empty](CodecMakerConfig)

    new JsonValueCodec[Unit] {
      def decodeValue(in: JsonReader, default: Unit) = emptyCodec.decodeValue(in, empty)
      def encodeValue(x: Unit, out: JsonWriter) = emptyCodec.encodeValue(empty, out)
      def nullValue = ()
    }
  }

  implicit val byteCodec: JsonValueCodec[Byte] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val charCodec: JsonValueCodec[Char] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val intCodec: JsonValueCodec[Int] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val longCodec: JsonValueCodec[Long] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val doubleCodec: JsonValueCodec[Double] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val floatCodec: JsonValueCodec[Float] = JsonCodecMaker.make(CodecMakerConfig)
  implicit val stringCodec: JsonValueCodec[String] = JsonCodecMaker.make(CodecMakerConfig)
}
