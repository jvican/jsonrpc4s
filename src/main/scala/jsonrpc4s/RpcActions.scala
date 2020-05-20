package jsonrpc4s

import monix.eval.Task
import monix.execution.Ack

import scala.concurrent.Future
import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.core.WriterConfig

/** Represents a response for a client RPC request.  */
sealed trait RpcResponse[T]

/**
 * Represents a successful client RPC request.
 *
 * @param value is the value that was successfully serialized from `underlying`.
 * @param underlying is the underlying JSON-RPC message where the value comes from.
 */
final case class RpcSuccess[T](
    value: T,
    underlying: jsonrpc4s.Response.Success
) extends RpcResponse[T]

/**
 * Represents a failed client RPC request.
 *
 * @param methodName is the name of the method that failed to complete.
 * @param underlying is the underlying JSON-RPC error message.
 */
final case class RpcFailure[T](
    methodName: String,
    underlying: jsonrpc4s.Response.Error
) extends RuntimeException(RpcFailure.toMsg(methodName, underlying))
    with RpcResponse[T]

object RpcFailure {
  def toMsg(methodName: String, err: Response.Error): String = {
    val errMsg = writeToString(err, config = WriterConfig.withIndentionStep(4))
    s"Unexpected error when calling '$methodName': $errMsg"
  }
}

trait RpcActions {
  def serverRespond(response: Response): Future[Ack]
  def clientRespond(response: Response): Unit

  def notify[A](
      endpoint: Endpoint[A, Unit],
      notification: A,
      headers: Map[String, String] = Map.empty
  ): Future[Ack]

  def request[A, B](
      endpoint: Endpoint[A, B],
      request: A,
      headers: Map[String, String] = Map.empty
  ): Task[RpcResponse[B]]
}

object RpcActions {
  import Endpoint.unitCodec
  object cancelRequest extends Endpoint[CancelParams, Unit]("$/cancelRequest")

  val empty: RpcActions = new RpcActions {
    override def request[A, B](
        endpoint: Endpoint[A, B],
        request: A,
        headers: Map[String, String] = Map.empty
    ): Task[RpcResponse[B]] = Task.never

    override def notify[A](
        endpoint: Endpoint[A, Unit],
        notification: A,
        headers: Map[String, String] = Map.empty
    ): Future[Ack] = Ack.Continue

    override def serverRespond(response: Response): Future[Ack] = Ack.Continue
    override def clientRespond(response: Response): Unit = ()
  }
}
