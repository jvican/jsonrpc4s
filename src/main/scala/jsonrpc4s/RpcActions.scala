package jsonrpc4s

import monix.eval.Task
import monix.execution.Ack

import scala.concurrent.Future
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

trait RpcActions {
  final def notify[A](
      endpoint: Endpoint[A, Unit],
      notification: A
  ): Future[Ack] = notify[A](endpoint.method, notification)(endpoint.codecA)
  def notify[A: JsonValueCodec](method: String, notification: A): Future[Ack]
  def serverRespond(response: Response): Future[Ack]
  def clientRespond(response: Response): Unit

  final def request[A, B](
      endpoint: Endpoint[A, B],
      req: A
  ): Task[Either[Response.Error, B]] =
    request[A, B](endpoint.method, req)(endpoint.codecA, endpoint.codecB)

  def request[A: JsonValueCodec, B: JsonValueCodec](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]]
}

object RpcActions {
  val empty: RpcActions = new RpcActions {
    override def request[A: JsonValueCodec, B: JsonValueCodec](
        method: String,
        request: A
    ): Task[Either[Response.Error, B]] = Task.never

    override def notify[A: JsonValueCodec](
        method: String,
        notification: A
    ): Future[Ack] = Ack.Continue

    override def serverRespond(response: Response): Future[Ack] = Ack.Continue
    override def clientRespond(response: Response): Unit = ()
  }
}
