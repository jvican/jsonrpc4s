package jsonrpc4s

import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import scala.util.{Try, Success, Failure}
import scribe.LoggerSupport
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import monix.execution.CancelableFuture

class RpcServer protected (
    in: Either[Observable[Message], Observable[LowLevelMessage]],
    client: RpcClient,
    services: Services,
    requestScheduler: Scheduler,
    logger: LoggerSupport
) {
  def this(
      in: Observable[LowLevelMessage],
      client: RpcClient,
      services: Services,
      requestScheduler: Scheduler,
      logger: LoggerSupport
  ) = this(Right(in), client, services, requestScheduler, logger)

  protected val activeClientRequests: TrieMap[RequestId, CancelableFuture[Response]] = TrieMap.empty
  protected val cancelNotification = {
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

  protected val handlersByMethodName: Map[String, NamedJsonRpcService] =
    services.addService(cancelNotification).byMethodName

  def cancelActiveClientRequests(): Unit =
    activeClientRequests.values.foreach(_.cancel())

  def waitForActiveClientRequests: Task[Unit] = {
    val futures = activeClientRequests.values.map(fut => Task.fromFuture(fut))
    // Await until completion and ignore task results
    Task.gatherUnordered(futures).materialize.map(_ => ())
  }

  protected def handleResponse(response: Response): Task[Response] = {
    Task.evalAsync {
      client.clientRespond(response)
      Response.None
    }
  }

  protected def handleRequest(request: Request): Task[Response] = {
    val Request(method, _, id, _) = request
    handlersByMethodName.get(method) match {
      case None =>
        Task.eval {
          logger.info(s"Method not found '$method'")
          Response.methodNotFound(method, id)
        }

      case Some(handler) =>
        val response = handler.handle(request).onErrorRecover {
          case NonFatal(e) =>
            logger.error(s"Unhandled JSON-RPC error handling request $request", e)
            Response.internalError(e.getMessage, request.id)
        }
        val runningResponse = response.runToFuture(requestScheduler)
        activeClientRequests.put(request.id, runningResponse)
        Task.fromFuture(runningResponse)
    }
  }

  protected def handleNotification(notification: Notification): Task[Response] = {
    val Notification(method, _, _) = notification
    handlersByMethodName.get(method) match {
      case None =>
        Task.eval {
          // Can't respond to invalid notifications
          logger.error(s"Unknown method '$method'")
          Response.None
        }

      case Some(handler) =>
        val response = handler
          .handle(notification)
          .onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Error handling notification $notification", e)
              Response.None
          }

        response.map {
          case Response.None => Response.None
          case nonEmpty =>
            logger.error(s"Obtained non-empty response $nonEmpty for notification $notification!")
            Response.None
        }
    }
  }

  protected def handleValidMessage(message: Message): Task[Response] = {
    message match {
      case response: Response => handleResponse(response)
      case notification: Notification => handleNotification(notification)
      case request: Request => handleRequest(request)
    }
  }

  protected def handleMessage(message: LowLevelMessage): Task[Response] = {
    Try(readFromArray[Message](message.content)) match {
      case Success(msg) => handleValidMessage(msg)
      case Failure(err) => Task.now(Response.parseError(err.toString))
    }
  }

  def startTask(afterSubscribe: Task[Unit]): Task[Unit] = {
    def serviceResponse(r: Task[Response]) = {
      r.map {
          case Response.None => ()
          case response => client.serverRespond(response)
        }
        .onErrorRecover {
          case NonFatal(e) => logger.error("Unhandled error responding to JSON-RPC client", e)
        }
        .runToFuture(requestScheduler)
    }

    in match {
      case Left(messages) =>
        messages
          .doAfterSubscribe(afterSubscribe)
          .foreachL(msg => serviceResponse(handleValidMessage(msg)))
      case Right(unparsedMessages) =>
        unparsedMessages
          .doAfterSubscribe(afterSubscribe)
          .foreachL(msg => serviceResponse(handleMessage(msg)))
    }
  }
}

object RpcServer {
  def apply(
      in: Observable[Message],
      client: RpcClient,
      services: Services,
      requestScheduler: Scheduler,
      logger: LoggerSupport
  ): RpcServer = {
    new RpcServer(Left(in), client, services, requestScheduler, logger)
  }
}
