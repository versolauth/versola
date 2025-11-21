package versola.auth

import versola.auth.model.{AuthId, ConversationRecord, ConversationStep}
import zio.Task

trait ConversationRepository:
  def create(authId: AuthId, initialStep: ConversationStep): Task[ConversationRecord]
  def find(authId: AuthId): Task[Option[ConversationRecord]]
  def updateStep(authId: AuthId, step: ConversationStep): Task[Unit]
  def delete(authId: AuthId): Task[Unit]
