package jsonrpc4s.tests

import minitest.SimpleTestSuite
import jsonrpc4s._
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig

object MessageCodecSuite extends SimpleTestSuite {

  def check(testName: String, message: Message): Unit = {
    test(testName) {
      val obtained = readFromArray[Message](writeToArray(message))
      assertEquals(obtained, message)
    }
  }

  check("Request", Request("method", Some(RawJson("1".getBytes)), RequestId(1), Map.empty))
  check("Notification", Notification("method", Some(RawJson("2".getBytes)), Map.empty))
  check("Response.Success", Response.Success(RawJson("3".getBytes), RequestId(2)))
  check(
    "Response.Error",
    Response
      .Error(ErrorObject(ErrorCode.ParseError, "method", Some(RawJson("4".getBytes))), RequestId(3))
  )
}
