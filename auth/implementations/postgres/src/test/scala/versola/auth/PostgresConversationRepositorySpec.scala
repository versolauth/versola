package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.conversation.model.AuthId
import versola.oauth.conversation.{ConversationRepositorySpec, PostgresConversationRepository}
import versola.util.postgres.PostgresSpec
import zio.{Task, ZIO, ZLayer}

object PostgresConversationRepositorySpec extends PostgresSpec, ConversationRepositorySpec:

  private class PostgresFixtures(xa: TransactorZIO) extends ConversationRepositorySpec.Fixtures:
    override def withoutStepField(authId: AuthId, step: String, field: String): Task[Unit] =
      xa.connect:
        sql"""UPDATE auth_conversations SET step = (step::jsonb #- ARRAY[$step, $field])::json WHERE id = CAST(${authId.toString} AS uuid)""".update.run()
      .unit

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield ConversationRepositorySpec.Env(PostgresConversationRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: ConversationRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations".update.run())
    yield ()
