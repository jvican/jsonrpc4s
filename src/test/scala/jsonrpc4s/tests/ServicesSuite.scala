package jsonrpc4s.tests

import minitest.SimpleTestSuite
import scribe.Logger
import jsonrpc4s.Endpoint
import jsonrpc4s.Services
import jsonrpc4s.BaseMessageCodecs.intCodec

object ServicesSuite extends SimpleTestSuite {
  test("duplicate method throws IllegalArgumentException") {
    val duplicate = Endpoint.notification[Int]("duplicate")
    val base = Services.empty(Logger.root).notification(duplicate)(_ => ())
    intercept[IllegalArgumentException] {
      base.notification(duplicate)(_ => ())
    }
  }
}
