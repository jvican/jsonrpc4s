package jsonrpc4s

import java.io.OutputStream
import monix.execution.Callback
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.atomic.AtomicInt
import monix.reactive.Observer
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scribe.LoggerSupport
import scala.util.Try

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import java.nio.channels.WritableByteChannel

class RpcClient(
    out: Observer[Message],
    logger: LoggerSupport
) extends RpcActions {

  protected val counter: AtomicInt = Atomic(1)
  protected val activeServerRequests = TrieMap.empty[RequestId, Callback[Throwable, Response]]

  protected val notificationsLock = new Object()
  protected def toJson[R: JsonValueCodec](r: R): RawJson = RawJson(writeToArray(r))

  def serverRespond(response: Response): Future[Ack] = {
    response match {
      case Response.None => Ack.Continue
      case x: Response.Success => out.onNext(x)
      case x: Response.Error => out.onNext(x)
    }
  }

  def clientRespond(response: Response): Unit = {
    for {
      id <- response match {
        case Response.None => Some(RequestId.Null)
        case Response.Success(_, requestId, jsonrpc, _) => Some(requestId)
        case Response.Error(_, requestId, jsonrpc, _) => Some(requestId)
      }
      callback <- activeServerRequests.remove(id).orElse {
        logger.error(s"Response to unknown request: $response")
        None
      }
    } {
      callback.onSuccess(response)
    }
  }

  def notify[A](
      endpoint: Endpoint[A, Unit],
      params: A,
      headers: Map[String, String] = Map.empty
  ): Future[Ack] = {
    import endpoint.codecA
    val msg = Notification(endpoint.method, Some(toJson(params)), headers)

    // Send notifications in the order they are sent by the caller
    notificationsLock.synchronized {
      out.onNext(msg)
    }
  }

  def request[A, B](
      endpoint: Endpoint[A, B],
      params: A,
      headers: Map[String, String] = Map.empty
  ): Task[RpcResponse[B]] = {
    import endpoint.{codecA, codecB}
    val reqId = RequestId(counter.incrementAndGet())
    val response = Task.create[Response] { (s, cb) =>
      val scheduled = s.scheduleOnce(Duration(0, "s")) {
        val json = Request(endpoint.method, Some(toJson(params)), reqId, headers)
        activeServerRequests.put(reqId, cb)
        out.onNext(json)
      }

      Cancelable { () =>
        scheduled.cancel()
        val cancellation = Response.cancelled(reqId)
        val cancelledErr = RpcFailure(endpoint.method, cancellation)
        activeServerRequests.remove(reqId).foreach(_.onError(cancelledErr))
        this.notify(RpcActions.cancelRequest, CancelParams(reqId))
      }
    }

    response.map {
      // This case can never happen given that no response isn't a valid JSON-RPC message
      case Response.None => sys.error("Fatal error: obtained `Response.None`!")
      case err: Response.Error => RpcFailure(endpoint.method, err)
      case suc: Response.Success =>
        Try(readFromArray[B](suc.result.value)).toEither match {
          case Right(value) => RpcSuccess(value, suc)
          case Left(err) =>
            RpcFailure(endpoint.method, Response.invalidParams(err.toString, reqId))
        }
    }
  }
}

object RpcClient {
  def fromOutputStream(out: OutputStream, logger: LoggerSupport): RpcClient = {
    val msgOut = Message.messagesToOutput(Left(out), logger)
    new RpcClient(msgOut, logger)
  }

  def fromChannel(channel: WritableByteChannel, logger: LoggerSupport): RpcClient = {
    val msgOut = Message.messagesToOutput(Right(channel), logger)
    new RpcClient(msgOut, logger)
  }
}
