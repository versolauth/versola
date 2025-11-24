package versola.oauth.conversation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.auth.model.OtpCode
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod}
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import versola.util.{Email, Phone}
import zio.http.URL
import zio.json.*
import zio.{Clock, Duration, Task}

import java.time.Instant
import java.util.UUID

class PostgresConversationRepository(xa: TransactorZIO) extends ConversationRepository, BasicCodecs:
  given JsonCodec[OtpCode] = JsonCodec.string.transform(OtpCode(_), identity[String])
  given JsonCodec[ConversationStep.Otp.Real] = DeriveJsonCodec.gen[ConversationStep.Otp.Real]
  given JsonCodec[PrimaryCredential] = JsonCodec.string.transform(PrimaryCredential.valueOf, _.toString)
  given JsonCodec[ConversationStep] = DeriveJsonCodec.gen[ConversationStep]

  given DbCodec[Either[Email, Phone]] = DbCodec.StringCodec.biMap(
    str => Either.cond(str.startsWith("+"), Phone(str), Email(str)),
    _.merge,
  )
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[CodeChallengeMethod] = DbCodec.StringCodec.biMap(CodeChallengeMethod.valueOf, _.toString)
  given DbCodec[CodeChallenge] = DbCodec.StringCodec.biMap(CodeChallenge(_), identity[String])
  given DbCodec[URL] = DbCodec.StringCodec.biMap(URL.decode(_).fold(throw _, identity), _.toString)
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[ConversationStep] = jsonCodec[ConversationStep]
  given DbCodec[ConversationRecord] = DbCodec.derived[ConversationRecord]

  override def find(authId: AuthId): Task[Option[ConversationRecord]] =
    Clock.instant.flatMap: now =>
      xa.connect {
        sql"""select client_id, redirect_uri, scope, code_challenge, code_challenge_method, user_id, credential, step, expires_at
              from auth_conversations
              where id = $authId"""
          .query[(ConversationRecord, Instant)]
          .run()
          .headOption
          .collect { case (conversation, expiresAt) if expiresAt.isAfter(now) => conversation }
      }

  override def create(authId: AuthId, record: ConversationRecord, ttl: Duration): Task[Unit] =
    xa.connect {
      sql"""insert into auth_conversations (id, client_id, redirect_uri, scope, code_challenge, code_challenge_method, user_id, credential, step, expires_at)
              values (
                $authId,
                ${record.clientId},
                ${record.redirectUri},
                ${record.scope},
                ${record.codeChallenge},
                ${record.codeChallengeMethod},
                ${record.userId},
                ${record.credential},
                ${record.step},
                ${authId.createdAt.plusSeconds(ttl.toSeconds)})
         """
        .update.run()
    }.unit

  override def overwrite(authId: AuthId, record: ConversationRecord): Task[Unit] =
    xa.connect {
      sql"""update auth_conversations set
              user_id = ${record.userId},
              credential = ${record.credential},
              step = ${record.step}
              where id = $authId"""
        .update.run()
    }.unit

  override def delete(authId: AuthId): Task[Unit] =
    xa.connect {
      sql"""delete from auth_conversations where id = $authId"""
        .update.run()
    }.unit
