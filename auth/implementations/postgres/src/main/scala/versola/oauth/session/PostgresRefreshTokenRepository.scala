package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.pg.{PgCodec, SqlArrayCodec}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{AccessToken, Nonce, RefreshToken}
import versola.oauth.session.model.{RefreshAlreadyExchanged, RefreshTokenRecord, SessionId, WithTtl}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.prelude.These
import zio.{Clock, IO, Task, ZIO, ZLayer}

import java.sql.Connection
import java.time.Instant
import java.util.UUID

class PostgresRefreshTokenRepository(xa: TransactorZIO) extends RefreshTokenRepository, BasicCodecs:
  import PgCodec.ListCodec
  import SqlArrayCodec.ListSqlArrayCodec

  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[AccessToken] = DbCodec.ByteArrayCodec.biMap(AccessToken(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given SqlArrayCodec[ClientId] = SqlArrayCodec.StringSqlArrayCodec.asInstanceOf[SqlArrayCodec[ClientId]]
  given DbCodec[ScopeToken] = DbCodec.StringCodec.biMap(ScopeToken(_), identity[String])
  given listStringDbCodec: DbCodec[List[String]] = PgCodec.SeqCodec[String].biMap(_.toList, _.toSeq)
  given listClientIdDbCodec: DbCodec[List[ClientId]] = PgCodec.SeqCodec[String].biMap(_.map(ClientId(_)).toList, _.map(identity[String]))
  given DbCodec[Nonce] = DbCodec.StringCodec.biMap(Nonce(_), identity[String])
  given DbCodec[RequestedClaims] = jsonCodec[RequestedClaims]
  given DbCodec[RefreshTokenRecord] = DbCodec.derived[RefreshTokenRecord]

  override def create(
      refreshToken: MAC.Of[RefreshToken],
      record: RefreshTokenRecord,
  ): IO[Throwable | RefreshAlreadyExchanged, Unit] =
    xa.withConnectionConfig(
      _.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ),
    ).transact {
      record.previousRefreshToken
        .map { oldToken =>
          sql"""DELETE FROM refresh_tokens WHERE id = $oldToken""".update.run()
        } match
        case Some(1) | None =>
          sql"""
            INSERT INTO refresh_tokens (
              id,
              previous_id,
              session_id,
              access_token,
              user_id,
              client_id,
              external_audience,
              scope,
              issued_at,
              expires_at,
              requested_claims,
              ui_locales,
              nonce
            )
            VALUES (
              $refreshToken,
              ${record.previousRefreshToken},
              ${record.sessionId},
              ${record.accessToken},
              ${record.userId},
              ${record.clientId},
              ${record.externalAudience},
              ${record.scope},
              ${record.issuedAt},
              ${record.expiresAt},
              ${record.requestedClaims},
              ${record.uiLocales}::text[],
              ${record.nonce}
            )
          """.update.run()
          ()
        case _ =>
          ()
    }.catchSome {
      case e: java.sql.SQLException if e.getSQLState == "40001" =>
        // Serialization failure (SQLSTATE 40001) - concurrent rotation detected
        ZIO.fail(RefreshAlreadyExchanged())
    }

  override def find(token: MAC.Of[RefreshToken]): Task[Option[RefreshTokenRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT session_id, access_token, user_id, client_id,
                 external_audience, scope, issued_at,
                 expires_at, requested_claims, ui_locales, nonce, previous_id
          FROM refresh_tokens
          WHERE id = $token
        """.query[RefreshTokenRecord]
          .run()
          .headOption
          .filter(_.expiresAt.isAfter(now))
    yield result

  override def delete(token: MAC.Of[RefreshToken]): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM refresh_tokens WHERE id = $token""".update.run()
    .unit

  override def deleteByAccessToken(token: AccessToken): Task[Unit] =
    xa.connect:
      sql"""DELETE FROM refresh_tokens WHERE access_token = $token""".update.run()
    .unit

object PostgresRefreshTokenRepository:
  def live: ZLayer[TransactorZIO, Throwable, RefreshTokenRepository] =
    ZLayer.fromFunction(PostgresRefreshTokenRepository(_))
