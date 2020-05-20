package jsonrpc4s.tests

import java.util.concurrent.ConcurrentLinkedQueue
import minitest.SimpleTestSuite
import monix.execution.Scheduler.Implicits.global
import scala.jdk.CollectionConverters._
import scala.concurrent.Promise
import scribe.Logger
import jsonrpc4s.Endpoint
import jsonrpc4s.Services
import jsonrpc4s.RpcClient
import jsonrpc4s.testkit.TestConnection
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import jsonrpc4s.RpcSuccess

/**
 * Tests the following sequence:
 *
 * Alice     Bob
 * =============
 * |  Ping   |
 * | ------> |
 * |  Pong   |
 * | <------ |
 * |         |
 * |  Ping   |
 * | <------ |
 * |  Pong   |
 * | ------> |
 * |         |
 * |  Hello  |
 * | ----->> |
 * | <<<---- |
 *
 * Where:
 * ---> indicates notification
 * -->> indicates request
 * ->>> indicates response
 */
object PingPongSuite extends SimpleTestSuite {

  implicit val stringCodec: JsonValueCodec[String] = JsonCodecMaker.make(CodecMakerConfig)
  private val Ping = Endpoint.notification[String]("ping")
  private val Pong = Endpoint.notification[String]("pong")
  private val Hello = Endpoint.request[String, String]("hello")

  testAsync("ping pong") {
    val promise = Promise[Unit]()
    val pongs = new ConcurrentLinkedQueue[String]()
    val services = Services
      .empty(Logger.root)
      .request(Hello) { msg => s"$msg, World!" }
      .notification(Pong) { message =>
        assert(pongs.add(message))
        if (pongs.size() == 2) {
          promise.complete(util.Success(()))
        }
      }

    val pongBack: RpcClient => Services = { implicit client =>
      services.notification(Ping) { message => Pong.notify(message.replace("Ping", "Pong")) }
    }

    val conn = TestConnection(pongBack, pongBack)
    for {
      _ <- conn.alice.client.notify(Ping, "Ping from client")
      _ <- conn.bob.client.notify(Ping, "Ping from server")
      RpcSuccess(helloWorld, msg) <- {
        val headers = Map("Custom-Header" -> "Custom-Value")
        Hello.request("Hello", headers)(conn.alice.client).runToFuture
      }
      _ <- promise.future
    } yield {
      assertEquals(helloWorld, "Hello, World!")
      val obtainedPongs = pongs.asScala.toList.sorted
      val expectedPongs = List("Pong from client", "Pong from server")
      assertEquals(obtainedPongs, expectedPongs)
      conn.cancel()
    }
  }
}
