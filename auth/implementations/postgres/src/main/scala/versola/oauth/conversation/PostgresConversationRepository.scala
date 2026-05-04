package versola.oauth.conversation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.auth.model.OtpCode
import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.{Login, UserId}
import versola.util.postgres.BasicCodecs
import versola.util.{Email, Phone}
import zio.http.URL
import zio.json.*
import zio.prelude.NonEmptySet
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresConversationRepository(xa: TransactorZIO) extends ConversationRepository, BasicCodecs:
  import PgCodec.{ListCodec, SeqCodec}
  import SqlArrayCodec.ListSqlArrayCodec

  given JsonCodec[OtpCode] = JsonCodec.string.transform(OtpCode(_), identity[String])
  given JsonCodec[ConversationStep.Otp.Real] = DeriveJsonCodec.gen[ConversationStep.Otp.Real]
  given JsonCodec[PrimaryCredential] = JsonCodec.string.transform(PrimaryCredential.valueOf, _.toString)
  given JsonCodec[ConversationStep] = DeriveJsonCodec.gen[ConversationStep]

  given DbCodec[Email] = DbCodec.StringCodec.biMap(Email(_), identity[String])
  given DbCodec[Phone] = DbCodec.StringCodec.biMap(Phone(_), identity[String])
  given DbCodec[Login] = DbCodec.StringCodec.biMap(Login(_), identity[String])
  given DbCodec[Either[Email, Phone]] = DbCodec.StringCodec.biMap(
    str => Either.cond(str.startsWith("+"), Phone(str), Email(str)),
    _.merge,
  )
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given DbCodec[List[String]] = PgCodec.SeqCodec[String].biMap(_.toList, _.toSeq)
  given DbCodec[CodeChallengeMethod] = DbCodec.StringCodec.biMap(CodeChallengeMethod.valueOf, _.toString)
  given DbCodec[CodeChallenge] = DbCodec.StringCodec.biMap(CodeChallenge(_), identity[String])
  given DbCodec[State] = DbCodec.StringCodec.biMap(State(_), identity[String])
  given DbCodec[Nonce] = DbCodec.StringCodec.biMap(Nonce(_), identity[String])
  given DbCodec[URL] = DbCodec.StringCodec.biMap(URL.decode(_).fold(throw _, identity), _.toString)
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[AuthId] = DbCodec.UUIDCodec.biMap(AuthId(_), identity[UUID])
  given DbCodec[Instant] = DbCodec.InstantCodec
  given DbCodec[ConversationStep] = jsonCodec[ConversationStep]
  given DbCodec[RequestedClaims] = jsonCodec[RequestedClaims]
  given DbCodec[zio.json.ast.Json.Obj] = jsonCodec[zio.json.ast.Json.Obj]
  given DbCodec[ResponseTypeEntry] = DbCodec.StringCodec.biMap(ResponseTypeEntry.valueOf, _.toString)
  given DbCodec[NonEmptySet[ResponseTypeEntry]] = DbCodec.StringCodec.biMap(
    str => NonEmptySet.fromIterableOption(str.split(" ").map(ResponseTypeEntry.valueOf)).getOrElse(NonEmptySet(ResponseTypeEntry.Code)),
    _.toSet.map(_.toString).mkString(" "),
  )
  given DbCodec[ConversationRecord] = DbCodec.derived[ConversationRecord]

  override def find(authId: AuthId): Task[Option[ConversationRecord]] =
    Clock.instant.flatMap: now =>
      xa.connect {
        sql"""select client_id, redirect_uri, scope, code_challenge, code_challenge_method, state, user_id, credential, step, requested_claims, ui_locales, nonce, response_type, user_email, user_phone, user_login, user_claims, expires_at
              from auth_conversations
              where id = $authId"""
          .query[(ConversationRecord, Instant)]
          .run()
          .headOption
          .collect { case (conversation, expiresAt) if expiresAt.isAfter(now) => conversation }
      }

  override def create(authId: AuthId, record: ConversationRecord, ttl: Duration): Task[Unit] =
    xa.connect {
      sql"""insert into auth_conversations (
                id,
                client_id,
                redirect_uri,
                scope,
                code_challenge,
                code_challenge_method,
                state,
                user_id,
                credential,
                step,
                requested_claims,
                ui_locales,
                nonce,
                response_type,
                user_email,
                user_phone,
                user_login,
                user_claims,
                expires_at
            ) values (
                $authId,
                ${record.clientId},
                ${record.redirectUri},
                ${record.scope},
                ${record.codeChallenge},
                ${record.codeChallengeMethod},
                ${record.state},
                ${record.userId},
                ${record.credential},
                ${record.step},
                ${record.requestedClaims},
                ${record.uiLocales}::text[],
                ${record.nonce},
                ${record.responseType},
                ${record.userEmail},
                ${record.userPhone},
                ${record.userLogin},
                ${record.userClaims},
                ${authId.createdAt.plusSeconds(ttl.toSeconds)})
         """
        .update.run()
    }.unit

  override def overwrite(authId: AuthId, record: ConversationRecord): Task[Unit] =
    xa.connect {
      sql"""update auth_conversations set
              user_id = ${record.userId},
              credential = ${record.credential},
              step = ${record.step},
              user_email = ${record.userEmail},
              user_phone = ${record.userPhone},
              user_login = ${record.userLogin},
              user_claims = ${record.userClaims}
            where id = $authId"""
        .update.run()
    }.unit

  override def delete(authId: AuthId): Task[Unit] =
    xa.connect {
      sql"""delete from auth_conversations where id = $authId"""
        .update.run()
    }.unit

object PostgresConversationRepository:
  def live: ZLayer[TransactorZIO, Throwable, ConversationRepository] =
    ZLayer.fromFunction(PostgresConversationRepository(_))