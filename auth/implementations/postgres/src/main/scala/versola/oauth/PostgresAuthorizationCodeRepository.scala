package versola.oauth

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.PgCodec
import versola.oauth.client.model.{Claim, ClientId, ScopeToken}
import versola.oauth.model.*
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.model.{ClaimRequest, RequestedClaims}
import versola.user.model.UserId
import versola.util.{MAC, Secret}
import versola.util.postgres.BasicCodecs
import zio.http.URL
import zio.json.*
import zio.{Clock, Duration, Task}

import java.time.Instant
import java.util.UUID

class PostgresAuthorizationCodeRepository(
    xa: TransactorZIO,
) extends AuthorizationCodeRepository, BasicCodecs:

  private given JsonCodec[Claim] = JsonCodec.string.transform(Claim(_), identity[String])
  private given JsonFieldEncoder[Claim] = JsonFieldEncoder.string.contramap(identity)
  private given JsonFieldDecoder[Claim] = JsonFieldDecoder.string.map(Claim(_))
  private given JsonCodec[ClaimRequest] = DeriveJsonCodec.gen[ClaimRequest]
  private given JsonCodec[RequestedClaims] = DeriveJsonCodec.gen[RequestedClaims]

  private given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  private given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  private given DbCodec[Instant] = DbCodec.InstantCodec
  private given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  private given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  private given DbCodec[CodeChallengeMethod] = DbCodec.StringCodec.biMap(CodeChallengeMethod.valueOf, _.toString)
  private given DbCodec[CodeChallenge] = DbCodec.StringCodec.biMap(CodeChallenge(_), identity[String])
  private given DbCodec[URL] = DbCodec.StringCodec.biMap(URL.decode(_).fold(throw _, identity), _.toString)
  private given DbCodec[RequestedClaims] = jsonCodec[RequestedClaims]
  private given DbCodec[Vector[String]] = PgCodec.SeqCodec[String].biMap(_.toVector, _.toSeq)
  private given DbCodec[AuthorizationCodeRecord] = DbCodec.derived[AuthorizationCodeRecord]

  override def find(code: MAC.Of[AuthorizationCode]): Task[Option[AuthorizationCodeRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, client_id, user_id, redirect_uri, scope, code_challenge, code_challenge_method, requested_claims, ui_locales, expires_at
          FROM authorization_codes
          WHERE code = $code
        """.query[(AuthorizationCodeRecord, Instant)].run().headOption
          .collect { case (record, expiresAt) if expiresAt.isAfter(now) => record }
    yield result

  override def create(
      code: MAC.Of[AuthorizationCode],
      record: AuthorizationCodeRecord,
      ttl: Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connect:
        sql"""
          INSERT INTO authorization_codes (code, session_id, client_id, user_id, redirect_uri, scope, code_challenge, code_challenge_method, requested_claims, ui_locales, expires_at)
          VALUES (
            $code,
            ${record.sessionId},
            ${record.clientId},
            ${record.userId},
            ${record.redirectUri},
            ${record.scope},
            ${record.codeChallenge},
            ${record.codeChallengeMethod.toString},
            ${record.requestedClaims},
            ${record.uiLocales}::text[],
            ${now.plusSeconds(ttl.toSeconds)}
          )
        """.update.run()
    .unit

  override def delete(code: MAC.Of[AuthorizationCode]): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM authorization_codes WHERE code = $code""".update.run()
    .unit
