package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.conversation.{ConversationRepositorySpec, PostgresConversationRepository}
import versola.util.postgres.PostgresSpec
import zio.{ZIO, ZLayer}

object PostgresConversationRepositorySpec extends PostgresSpec, ConversationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield ConversationRepositorySpec.Env(PostgresConversationRepository(xa))

  override def beforeEach(env: ConversationRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"TRUNCATE TABLE auth_conversations".update.run())
    yield ()

