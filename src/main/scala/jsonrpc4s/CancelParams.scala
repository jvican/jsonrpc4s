package jsonrpc4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig

case class CancelParams(id: RequestId)
object CancelParams {
  implicit val cancelParamsCodec: JsonValueCodec[CancelParams] =
    JsonCodecMaker.make(CodecMakerConfig)
}
