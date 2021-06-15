package jsonrpc4s.tests

import minitest.SimpleTestSuite
import scribe.Logger
import jsonrpc4s.Endpoint
import jsonrpc4s.Services
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig

object ServicesSuite extends SimpleTestSuite {
  implicit val intCodec: JsonValueCodec[Int] = JsonCodecMaker.make(CodecMakerConfig)
  test("duplicate method throws IllegalArgumentException") {
    val duplicate = Endpoint.notification[Int]("duplicate")
    val base = Services.empty(Logger.root).notification(duplicate)(_ => ())
    intercept[IllegalArgumentException] {
      base.notification(duplicate)(_ => ())
    }
    ()
  }
}
