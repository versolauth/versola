package versola.auth.model

import zio.schema.{Schema, derived}

import java.time.Instant

case class ConversationRecord(
    authId: AuthId,
    steps: Vector[ConversationStep],
    currentStep: ConversationStep,
    createdAt: Instant,
    updatedAt: Instant,
) derives Schema

object ConversationRecord:
  def create(authId: AuthId, initialStep: ConversationStep): ConversationRecord =
    val now = authId.createdAt
    ConversationRecord(
      authId = authId,
      steps = Vector(initialStep),
      currentStep = initialStep,
      createdAt = now,
      updatedAt = now,
    )
