package versola.auth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.{AuthId, ConversationRecord, ConversationStep}
import versola.util.postgres.BasicCodecs
import zio.{Clock, Task}
import zio.json.*

import java.time.Instant
import java.util.UUID

class PostgresConversationRepository(xa: TransactorZIO) extends ConversationRepository, BasicCodecs:

  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[Instant] = DbCodec.InstantCodec

  // JSON codecs for ConversationStep
  given JsonCodec[ConversationStep] = DeriveJsonCodec.gen[ConversationStep]
  given JsonEncoder[Vector[ConversationStep]] = JsonEncoder.vector[ConversationStep]
  given JsonDecoder[Vector[ConversationStep]] = JsonDecoder.vector[ConversationStep]

  // JSONB DbCodecs using BasicCodecs
  given DbCodec[ConversationStep] = jsonCodec[ConversationStep]
  given DbCodec[Vector[ConversationStep]] = jsonCodec[Vector[ConversationStep]]

  given DbCodec[ConversationRecord] = DbCodec.derived[ConversationRecord]

  override def create(authId: AuthId, initialStep: ConversationStep): Task[ConversationRecord] =
    for
      now <- Clock.instant
      record = ConversationRecord(
        authId = authId,
        steps = Vector(initialStep),
        currentStep = initialStep,
        createdAt = now,
        updatedAt = now,
      )
      _ <- xa.connect:
        sql"""insert into conversations (auth_id, steps, current_step, created_at, updated_at)
              values (${record.authId}, ${record.steps}, ${record.currentStep}, ${record.createdAt}, ${record.updatedAt})"""
          .update.run()
    yield record

  override def find(authId: AuthId): Task[Option[ConversationRecord]] =
    xa.connect:
      sql"""select auth_id, steps, current_step, created_at, updated_at
            from conversations
            where auth_id = $authId"""
        .query[ConversationRecord]
        .run()
        .headOption

  override def updateStep(authId: AuthId, step: ConversationStep): Task[Unit] =
    for
      now <- Clock.instant
      // First get current steps, then append the new step
      currentConversation <- find(authId)
      updatedSteps = currentConversation.map(_.steps :+ step).getOrElse(Vector(step))
      _ <- xa.connect:
        sql"""update conversations
              set current_step = $step,
                  steps = $updatedSteps,
                  updated_at = $now
              where auth_id = $authId"""
          .update.run()
    yield ()

  override def delete(authId: AuthId): Task[Unit] =
    xa.connect:
      sql"""delete from conversations where auth_id = $authId"""
        .update.run()
