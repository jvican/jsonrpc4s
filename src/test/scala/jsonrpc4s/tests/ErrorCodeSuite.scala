package jsonrpc4s.tests

import minitest.SimpleTestSuite
import jsonrpc4s.ErrorCode
import jsonrpc4s.BaseMessageCodecs.intCodec
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray, writeToString}

object ErrorCodeSuite extends SimpleTestSuite {

  def check(code: Int, expected: ErrorCode): Unit = {
    test(expected.toString) {
      val obtained = readFromArray[ErrorCode](writeToArray(code))
      assertEquals(obtained, expected)
      val obtainedJson = writeToString(expected)
      assertEquals(obtainedJson, code.toString)
    }
  }

  check(666, ErrorCode.Unknown(666))
  check(-32000, ErrorCode.Unknown(-32000))
  check(-32099, ErrorCode.Unknown(-32099))
  ErrorCode.builtin.foreach { code =>
    check(code.value, code)
  }
}
