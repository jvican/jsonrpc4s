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

  private val counter: AtomicInt = Atomic(1)
  private val activeServerRequests = TrieMap.empty[RequestId, Callback[Throwable, Response]]

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
        case Response.Success(_, requestId, jsonrpc) => Some(requestId)
        case Response.Error(_, requestId, jsonrpc) => Some(requestId)
      }
      callback <- activeServerRequests.remove(id).orElse {
        logger.error(s"Response to unknown request: $response")
        None
      }
    } {
      callback.onSuccess(response)
    }
  }

  private val notificationsLock = new Object()
  private def toJson[R: JsonValueCodec](r: R): RawJson = RawJson(writeToArray(r))
  def notify[A: JsonValueCodec](method: String, notification: A): Future[Ack] = {
    // Send notifications in the order they are sent by the caller
    notificationsLock.synchronized {
      out.onNext(Notification(method, Some(toJson(notification))))
    }
  }

  def request[A: JsonValueCodec, B: JsonValueCodec](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]] = {
    val nextId = RequestId(counter.incrementAndGet())
    val response = Task.create[Response] { (s, cb) =>
      val scheduled = s.scheduleOnce(Duration(0, "s")) {
        val json = Request(method, Some(toJson(request)), nextId)
        activeServerRequests.put(nextId, cb)
        out.onNext(json)
      }
      Cancelable { () =>
        scheduled.cancel()
        this.notify("$/cancelRequest", CancelParams(nextId))
      }
    }

    response.map {
      // This case can never happen given that no response isn't a valid JSON-RPC message
      case Response.None => sys.error("Fatal error: obtained `Response.None`!")
      case err: Response.Error => Left(err)
      case Response.Success(result, _, _) =>
        Try(readFromArray[B](result.value)).toEither.left.map(err =>
          Response.invalidParams(err.toString, nextId)
        )
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
