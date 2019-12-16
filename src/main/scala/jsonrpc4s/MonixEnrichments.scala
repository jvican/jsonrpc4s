package jsonrpc4s

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import monix.execution.Ack
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.Observer
import scribe.LoggerSupport
import java.nio.channels.Channels

object MonixEnrichments {

  /**
   * Utility to read the latest eagerly computed value from an observable.
   *
   * NOTE. Immediately subscribes to the value and eagerly computes the value on every update.
   * Subscription can be cancelled with .cancel()
   *
   * @param obs The observable to convert into a reactive variable.
   * @param s The scheduler to compute the variable on.
   */
  class ObservableCurrentValue[+A](obs: Observable[A])(implicit s: Scheduler)
      extends (() => A)
      with Cancelable {
    private var value: Any = _
    private val cancelable = obs.foreach(newValue => value = newValue)
    override def apply(): A = {
      if (value == null) {
        throw new NoSuchElementException(
          "Reading from empty Observable, consider using MulticastStrategy.behavior(initialValue)"
        )
      } else {
        value.asInstanceOf[A]
      }
    }
    override def cancel(): Unit = cancelable.cancel()
  }

  implicit class XtensionObservable[A](val obs: Observable[A]) extends AnyVal {

    def focus[B: cats.Eq](f: A => B): Observable[B] =
      obs.distinctUntilChangedByKey(f).map(f)

    def toFunction0()(implicit s: Scheduler): () => A =
      toObservableCurrentValue()

    def toObservableCurrentValue()(
        implicit s: Scheduler
    ): ObservableCurrentValue[A] =
      new ObservableCurrentValue[A](obs)
  }

  implicit class XtensionObserverCompanion[A](val `_`: Observer.type) extends AnyVal {

    /**
     * An observer implementation that writes messages to the underlying output
     * stream. This class is copied over from lsp4s but has been modified to
     * synchronize writing on the output stream. Synchronizing makes sure BSP
     * clients see server responses in the order they were sent.
     *
     * If this is a bottleneck in the future, we can consider removing the
     * synchronized blocks here and in the body of `BloopLanguageClient` and
     * replace them with a ring buffer and an id generator to make sure all
     * server interactions are sent out in order. As it's not a performance
     * blocker for now, we opt for the synchronized approach.
     */
    def fromOutputStream(
        out: OutputStream,
        logger: LoggerSupport
    ): Observer.Sync[ByteBuffer] = {
      new Observer.Sync[ByteBuffer] {
        private[this] var isClosed: Boolean = false
        private[this] val channel = Channels.newChannel(out)
        override def onNext(elem: ByteBuffer): Ack = out.synchronized {
          if (isClosed) Ack.Stop
          else {
            try {
              channel.write(elem)
              out.flush()
              Ack.Continue
            } catch {
              case t: java.io.IOException =>
                logger.trace("OutputStream closed!", t)
                isClosed = true
                Ack.Stop
            }
          }
        }
        override def onError(ex: Throwable): Unit = ()
        override def onComplete(): Unit = {
          out.synchronized {
            channel.close()
            out.close()
          }
        }
      }
    }
  }
}
