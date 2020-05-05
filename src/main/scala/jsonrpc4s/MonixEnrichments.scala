package jsonrpc4s

import java.io.OutputStream
import java.nio.ByteBuffer
import monix.execution.Ack
import monix.reactive.Observer
import scribe.LoggerSupport
import java.nio.channels.Channels

object MonixEnrichments {

  implicit class XtensionObserverCompanion[A](val `_`: Observer.type) extends AnyVal {

    /**
     * An observer implementation that writes byte buffers to an underlying
     * output stream.
     */
    def bytesToOutputStream(
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

    def messagesFromByteStream(
        out: Observer.Sync[ByteBuffer],
        logger: LoggerSupport
    ): Observer.Sync[Message] = {
      new Observer.Sync[Message] {
        private[this] var isClosed = false
        private[this] val writer = new LowLevelMessageWriter(out, logger)
        override def onNext(elem: Message): Ack = writer.synchronized {
          if (isClosed) Ack.Stop
          else {
            try {
              writer.write(elem)
              Ack.Continue
            } catch {
              case t: java.io.IOException =>
                logger.trace("Message stream closed!", t)
                isClosed = true
                Ack.Stop
            }
          }
        }
        override def onError(ex: Throwable): Unit = ()
        override def onComplete(): Unit = {
          out.synchronized {
            out.onComplete()
          }
        }
      }
    }
  }
}
