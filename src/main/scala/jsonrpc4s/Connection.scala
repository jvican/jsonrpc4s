package jsonrpc4s

import monix.execution.Cancelable
import monix.execution.CancelableFuture
import monix.execution.Scheduler

import scribe.Logger
import scribe.LoggerSupport
import monix.eval.Task

/**
 * A connection with another JSON-RPC entity.
 *
 * @param client used to send requests/notification to the other entity.
 * @param server server on this side listening to input streams from the other entity.
 */
final case class Connection(
    client: RpcClient,
    server: CancelableFuture[Unit]
) extends Cancelable {
  override def cancel(): Unit = server.cancel()
}

object Connection {

  def simple(io: InputOutput, name: String)(
      f: RpcClient => Services
  )(implicit s: Scheduler): Connection = {
    Connection(io, Logger(s"$name-server"), Logger(s"$name-client"))(f)
  }

  def apply(
      io: InputOutput,
      serverLogger: LoggerSupport,
      clientLogger: LoggerSupport
  )(
      f: RpcClient => Services
  )(implicit s: Scheduler): Connection = {
    val messages = LowLevelMessage
      .fromInputStream(io.in, serverLogger)
      .mapEval(msg => Task(LowLevelMessage.toMsg(msg)))
    val client = RpcClient.fromOutputStream(io.out, clientLogger)
    val server = RpcServer(messages, client, f(client), s, serverLogger)
    Connection(client, server.startTask(Task.unit).executeAsync.runToFuture)
  }
}
