package org.sandbox.chat.http

import scala.concurrent.duration.DurationInt

import org.sandbox.chat.ChatServer.Ack
import org.sandbox.chat.ChatServer.Ackable
import org.sandbox.chat.ChatServer.ChatServerMsg
import org.sandbox.chat.ChatServer.Contribution
import org.sandbox.chat.ChatServer.Join

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.http.marshalling.ToResponseMarshallable
import akka.http.marshalling.ToResponseMarshallable.apply
import akka.stream.scaladsl.Source
import de.heikoseeberger.akkasse.EventStreamMarshalling
import de.heikoseeberger.akkasse.ServerSentEvent

import SseChatServerActions._

class SseChatServerActions(val chatServer: ActorRef, ssePublisher: ActorRef,
    val system: ActorSystem)
  extends ChatServerActions[ToResponseMarshallable] with Participants[ToResponseMarshallable]
  with EventStreamMarshalling
{
  import org.sandbox.chat.ChatServer._
  import system.dispatcher

  override def notFound(name: String): ToResponseMarshallable =
    singleSseSource(ServerSentEvent(name, "notfound"))

  private def publish(sse: ServerSentEvent) = {
    ssePublisher ! sse
    sse
  }

  private def singleSseSource(sse: ServerSentEvent): Source[ServerSentEvent, Unit] =
    Source.single(sse) map publish

  private def tellWithAckReceiver(who: ActorRef, msg: Ackable, onAckReceived: => Unit = ()) = {
    val ack = Ack(msg)
    def onTimeout = system.log.error(s"timeout for $ack")
    def createAckReceiver: ActorRef = {
      def onAck = {
        onAckReceived
        system.log.debug(s"received $ack")
        singleSseSource(ack)
      }
      system.actorOf(AckReceiver.props(ack, onAck, 1 second, onTimeout))
    }

    val ackReceiver = createAckReceiver
    who tell (msg, ackReceiver)
  }

  def onContribution(name: String, msg: String): ToResponseMarshallable = {
    forParticipant(name) { participant =>
      val contribution = Contribution(participant, msg)
      tellWithAckReceiver(participant.who, contribution)
      singleSseSource(contribution)
    }
  }

  def onJoin(name: String): ToResponseMarshallable = {
    val participant = createParticipant(name)
    val msg = Join(participant)
    tellWithAckReceiver(participant.who, msg, addParticipant(participant))
    singleSseSource(msg)
  }

  def onLeave(name: String): ToResponseMarshallable = {
    ???
  }

  def onPoll(name: String): ToResponseMarshallable = {
    ???
  }

  def onShutdown: ToResponseMarshallable = {
    system.scheduler.scheduleOnce(500 millis)(system.shutdown)
    val sse =
      ServerSentEvent(s"shutdown: ${system.name} (participants: ${participantNames.mkString(",")})",
          Some("shutdown"))
    singleSseSource(sse)
  }
}

object SseChatServerActions {
  import org.sandbox.chat.ChatServer._
  implicit def chatServerMsgToServerSentEvent(message: ChatServerMsg): ServerSentEvent = {
    val event = message.getClass.getSimpleName.toLowerCase
    message match {
      case m => ServerSentEvent(m.toString, event)
    }
  }
}