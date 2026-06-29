package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.ClientId
import versola.oauth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.{PassedAuthFactor, PassedFactorRecord}
import versola.oauth.session.model.{SessionId, SessionRecord, UserAgentInfo, WithTtl}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.prelude.These
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresSessionRepository(xa: TransactorZIO) extends SessionRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[UserAgentInfo] = jsonBCodec[UserAgentInfo]
  given amrCodec: DbCodec[Map[PassedAuthFactor, PassedFactorRecord]] = jsonBCodec[Map[PassedAuthFactor, PassedFactorRecord]]
  given DbCodec[SessionRecord] = DbCodec.derived[SessionRecord]

  override def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: zio.Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("create-session"):
        sql"""
          INSERT INTO sso_sessions (id, client_id, user_id, user_agent, created_at, amr, expires_at)
          VALUES (
            $id,
            ${session.clientId},
            ${session.userId},
            ${session.userAgent},
            ${session.createdAt},
            ${session.amr},
            ${now.plusSeconds(ttl.toSeconds)}
          )
        """.update.run()
      .unit

  override def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    for
      now <- Clock.instant
      result <- xa.connectMeasured("find-session"):
        sql"""
          SELECT user_id, client_id, user_agent, created_at, amr, expires_at
          FROM sso_sessions
          WHERE id = $id
        """.query[(SessionRecord, Instant)].run().headOption
          .collect { case (record, expiresAt) if expiresAt.isAfter(now) => record }
    yield result

  override def findByUserId(
      userId: UserId,
  ): Task[List[SessionRecord]] =
    for
      now <- Clock.instant
      result <- xa.connectMeasured("find-sessions-by-user"):
        sql"""
        SELECT user_id, client_id, user_agent, created_at, amr
        FROM sso_sessions
        WHERE
          user_id = $userId
          AND expires_at > $now
        ORDER BY created_at DESC
      """.query[SessionRecord].run().toList
    yield result

  override def invalidateByUserId(
      userId: UserId,
  ): Task[Unit] =
    for
      now <- Clock.instant
      _ <- xa.connectMeasured("invalidate-sessions-by-user"):
        sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE user_id = $userId
        """.update.run()
    yield ()

object PostgresSessionRepository:
  def live: ZLayer[TransactorZIO, Throwable, SessionRepository] =
    ZLayer.fromFunction(PostgresSessionRepository(_))
