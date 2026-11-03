package versola.auth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.oauth.client.model.PrimaryCredential
import versola.oauth.conversation.model.ConversationStep
import versola.oauth.conversation.{ConversationRepositorySpec, PostgresConversationRepository}
import versola.util.postgres.PostgresSpec
import zio.test.*
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

  override def implementationCases(env: ConversationRepositorySpec.Env) =
    List(
      test("a step stored before a code step could be left for an alternative reads back with no switches and no code step") {
        val code = realOtp.copy(switched = 3)
        val passkey = ConversationStep.Credential(
          primaryCredentials = List(PrimaryCredential.phone),
          inlinePassword = false,
          passkey = true,
          passkeyOnly = true,
          codeStep = Some(code),
        )
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- env.repository.create(authId1, record1.copy(step = code), ttl)
          _ <- env.repository.create(authId2, record1.copy(step = passkey), ttl)
          // What the rows looked like when the fields did not exist: they are simply not there.
          _ <- xa.connect:
            sql"""UPDATE auth_conversations SET step = (step::jsonb #- '{Otp,switched}')::json WHERE id = CAST(${authId1.toString} AS uuid)""".update.run()
            sql"""UPDATE auth_conversations SET step = (step::jsonb #- '{Credential,codeStep}')::json WHERE id = CAST(${authId2.toString} AS uuid)""".update.run()
          otp <- env.repository.find(authId1).map(_.map(_.step))
          credential <- env.repository.find(authId2).map(_.map(_.step))
        yield assertTrue(otp == Some(realOtp), credential == Some(passkey.copy(codeStep = None)))
      },
    )
