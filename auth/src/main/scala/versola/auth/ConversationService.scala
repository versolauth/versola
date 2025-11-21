package versola.auth

import versola.auth.model.{AuthId, ConversationRecord, ConversationStep}
import zio.Task

trait ConversationService:
  def create(authId: AuthId, initialStep: ConversationStep): Task[ConversationRecord]
  def updateStep(authId: AuthId, step: ConversationStep): Task[Unit]
  def getConversation(authId: AuthId): Task[Option[ConversationRecord]]
  def deleteConversation(authId: AuthId): Task[Unit]

object ConversationService:
  class Impl(
      conversationRepository: ConversationRepository,
  ) extends ConversationService:

    override def create(authId: AuthId, initialStep: ConversationStep): Task[ConversationRecord] =
      conversationRepository.create(authId, initialStep)

    override def updateStep(authId: AuthId, step: ConversationStep): Task[Unit] =
      conversationRepository.updateStep(authId, step)

    override def getConversation(authId: AuthId): Task[Option[ConversationRecord]] =
      conversationRepository.find(authId)

    override def deleteConversation(authId: AuthId): Task[Unit] =
      conversationRepository.delete(authId)

