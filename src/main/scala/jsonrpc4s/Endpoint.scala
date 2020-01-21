package jsonrpc4s

import monix.eval.Task
import monix.execution.Ack
import scala.concurrent.Future
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

class Endpoint[A, B](
    val method: String
)(implicit val codecA: JsonValueCodec[A], val codecB: JsonValueCodec[B]) {
  def request(request: A)(
      implicit client: RpcActions
  ): Task[Either[Response.Error, B]] =
    client.request[A, B](method, request)
  def notify(
      notification: A
  )(implicit client: RpcActions): Future[Ack] =
    client.notify[A](method, notification)
}

object Endpoint {
  import jsonrpc4s.BaseMessageCodecs.unitCodec
  def request[A: JsonValueCodec, B: JsonValueCodec](method: String): Endpoint[A, B] =
    new Endpoint(method)
  def notification[A: JsonValueCodec](method: String): Endpoint[A, Unit] =
    new Endpoint(method)
}
