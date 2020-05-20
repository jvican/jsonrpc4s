package jsonrpc4s

import monix.eval.Task
import scribe.LoggerSupport

import scala.util.{Try, Success, Failure}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray

trait Service[A, B] {
  def handle(request: A): Task[B]
}

trait MethodName {
  def methodName: String
}

trait JsonRpcService extends Service[Message, Response]
trait NamedJsonRpcService extends JsonRpcService with MethodName

object Service {
  def request[A, B](endpoint: Endpoint[A, B])(
      f: Service[A, B]
  ): NamedJsonRpcService = new NamedJsonRpcService {
    override def methodName: String = endpoint.method
    override def handle(message: Message): Task[Response] = {
      import endpoint.{codecA, codecB}
      val method = endpoint.method
      message match {
        case Request(`method`, params, id, jsonrpc, headers) =>
          val paramsJson = params.getOrElse(RawJson.nullValue)
          Try(readFromArray[A](paramsJson.value)) match {
            case Success(value) =>
              f.handle(value).materialize.map {
                case Success(response) =>
                  Response.ok(RawJson.toJson(response), id)
                // Errors always have a null id because services don't have access to the real id
                case Failure(err: Response.Error) => err.copy(id = id)
                case Failure(err) => Response.internalError(err, id)
              }
            case Failure(err) => Task(Response.invalidParams(err.toString, id))
          }

        case Request(invalidMethod, _, id, _, _) => Task(Response.methodNotFound(invalidMethod, id))
        case _ => Task(Response.invalidRequest(s"Expected request, obtained $message"))
      }
    }
  }

  def notification[A](endpoint: Endpoint[A, Unit], logger: LoggerSupport)(
      f: Service[A, Unit]
  ): NamedJsonRpcService = {
    new NamedJsonRpcService {
      override def methodName: String = endpoint.method
      private def fail(msg: String): Task[Response] = Task.evalAsync {
        logger.error(msg)
        Response.None
      }

      override def handle(message: Message): Task[Response] = {
        import endpoint.codecA
        val method = endpoint.method
        message match {
          case Notification(`method`, params, _, headers) =>
            val paramsJson = params.getOrElse(RawJson.nullValue)
            Try(readFromArray[A](paramsJson.value)) match {
              case Success(value) => f.handle(value).map(a => Response.None)
              case Failure(err) => fail(s"Failed to parse notification $message. Errors: $err")
            }
          case Notification(invalidMethod, _, _, headers) =>
            fail(s"Expected method '$method', obtained '$invalidMethod'")
          case _ => fail(s"Expected notification, obtained $message")
        }
      }
    }
  }
}

object Services {
  def empty(logger: LoggerSupport): Services = new Services(Nil, logger)
}

class Services private (
    val services: List[NamedJsonRpcService],
    logger: LoggerSupport
) {
  def request[A, B](endpoint: Endpoint[A, B])(f: A => B): Services = {
    requestAsync[A, B](endpoint)(new Service[A, B] {
      def handle(request: A): Task[B] = Task.evalAsync(f(request))
    })
  }

  def requestAsync[A, B](
      endpoint: Endpoint[A, B]
  )(f: Service[A, B]): Services = {
    addService(Service.request[A, B](endpoint)(f))
  }

  def notification[A](endpoint: Endpoint[A, Unit])(f: A => Unit): Services = {
    notificationAsync[A](endpoint)(new Service[A, Unit] {
      def handle(request: A): Task[Unit] = Task.evalAsync(f(request))
    })
  }

  def notificationAsync[A](
      endpoint: Endpoint[A, Unit]
  )(f: Service[A, Unit]): Services = {
    addService(Service.notification[A](endpoint, logger)(f))
  }

  def byMethodName: Map[String, NamedJsonRpcService] =
    services.iterator.map(s => s.methodName -> s).toMap
  def addService(service: NamedJsonRpcService): Services = {
    val duplicate = services.find(_.methodName == service.methodName)
    require(
      duplicate.isEmpty,
      s"Duplicate service handler for method ${duplicate.get.methodName}"
    )
    new Services(service :: services, logger)
  }
}
