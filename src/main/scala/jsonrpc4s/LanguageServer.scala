package jsonrpc4s

import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Try, Success, Failure}
import scribe.LoggerSupport
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray

final class LanguageServer(
    in: Observable[BaseProtocolMessage],
    client: LanguageClient,
    services: Services,
    requestScheduler: Scheduler,
    logger: LoggerSupport
) {
  private val activeClientRequests: TrieMap[RequestId, Cancelable] = TrieMap.empty
  private val cancelNotification = {
    Service.notification[CancelParams]("$/cancelRequest", logger) {
      new Service[CancelParams, Unit] {
        def handle(params: CancelParams): Task[Unit] = {
          val id = params.id
          activeClientRequests.get(id) match {
            case None =>
              Task.evalAsync {
                logger.warn(
                  s"Can't cancel request $id, no active request found."
                )
                ()
              }
            case Some(request) =>
              Task.evalAsync {
                logger.info(s"Cancelling request $id")
                request.cancel()
                activeClientRequests.remove(id)
                Response.cancelled(id)
                ()
              }
          }
        }
      }
    }
  }

  private val handlersByMethodName: Map[String, NamedJsonRpcService] =
    services.addService(cancelNotification).byMethodName

  def handleValidMessage(message: Message): Task[Response] = message match {
    case response: Response =>
      Task.evalAsync {
        client.clientRespond(response)
        Response.None
      }
    case Notification(method, _, _) =>
      handlersByMethodName.get(method) match {
        case None =>
          Task.evalAsync {
            // Can't respond to invalid notifications
            logger.error(s"Unknown method '$method'")
            Response.None
          }
        case Some(handler) =>
          handler
            .handle(message)
            .map {
              case Response.None => Response.None
              case nonEmpty =>
                logger.error(
                  s"Obtained non-empty response $nonEmpty for notification $message. " +
                    s"Expected Response.empty"
                )
                Response.None
            }
            .onErrorRecover {
              case NonFatal(e) =>
                logger.error(s"Error handling notification $message", e)
                Response.None
            }
      }
    case request @ Request(method, _, id, _) =>
      handlersByMethodName.get(method) match {
        case None =>
          Task.evalAsync {
            logger.info(s"Method not found '$method'")
            Response.methodNotFound(method, id)
          }
        case Some(handler) =>
          val response = handler.handle(request).onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Unhandled error handling request $request", e)
              Response.internalError(e.getMessage, request.id)
          }
          val runningResponse = response.runToFuture(requestScheduler)
          activeClientRequests.put(request.id, runningResponse)
          Task.fromFuture(runningResponse)
      }

  }

  def handleMessage(message: BaseProtocolMessage): Task[Response] = {
    Try(readFromArray[Message](message.content)) match {
      case Success(msg) => handleValidMessage(msg)
      case Failure(err) => Task.now(Response.parseError(err.toString))
    }
  }

  def startTask: Task[Unit] = {
    in.foreachL { msg =>
      handleMessage(msg)
        .map {
          case Response.None => ()
          case response => client.serverRespond(response)
        }
        .onErrorRecover {
          case NonFatal(e) =>
            logger.error("Unhandled error", e)
        }
        .runToFuture(requestScheduler)
    }
  }

  def listen(): Unit = {
    val f = startTask.runToFuture(requestScheduler)
    logger.info("Listening....")
    Await.result(f, Duration.Inf)
  }
}
