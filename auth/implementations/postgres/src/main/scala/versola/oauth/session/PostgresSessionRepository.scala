package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.fasterxml.uuid.Generators
import versola.oauth.client.model.{ClientId, PassedAuthFactor, PassedFactorRecord}
import versola.oauth.session.model.{SessionId, SessionRecord, UserAgentInfo}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.json.*
import zio.{Clock, Duration, Task, ZLayer}

import java.time.Instant
import java.util.UUID

class PostgresSessionRepository(xa: TransactorZIO) extends SessionRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UUID] = DbCodec.UUIDCodec
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[UserAgentInfo] = jsonBCodec[UserAgentInfo]
  given amrCodec: DbCodec[Map[PassedAuthFactor, PassedFactorRecord]] = jsonBCodec[Map[PassedAuthFactor, PassedFactorRecord]]
  given DbCodec[SessionRecord] = DbCodec.derived[SessionRecord]

  override def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: zio.Duration,
      idleTtl: Option[zio.Duration],
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      val publicSessionId = Generators.timeBasedEpochGenerator().construct(now.toEpochMilli)
      val idleExpiresAt   = idleTtl.map(t => now.plusSeconds(t.toSeconds))
      xa.connectMeasured("create-session"):
        sql"""
          INSERT INTO sso_sessions (id, client_id, user_id, user_agent, created_at, amr, expires_at, idle_expires_at, public_session_id)
          VALUES (
            $id,
            ${session.clientId},
            ${session.userId},
            ${session.userAgent},
            ${session.createdAt},
            ${session.amr},
            ${now.plusSeconds(ttl.toSeconds)},
            $idleExpiresAt,
            $publicSessionId
          )
        """.update.run()
      .unit

  override def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-session"):
        sql"""
          SELECT user_id, client_id, user_agent, created_at, amr
          FROM sso_sessions
          WHERE id = $id
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
        """.query[SessionRecord].run().headOption

  override def prolongIdle(id: MAC.Of[SessionId], idleTtl: zio.Duration): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("prolong-idle"):
        sql"""
          UPDATE sso_sessions
          SET idle_expires_at = ${now.plusSeconds(idleTtl.toSeconds)}
          WHERE id = $id AND idle_expires_at IS NOT NULL
        """.update.run()
      .unit

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
          AND (idle_expires_at IS NULL OR idle_expires_at > $now)
        ORDER BY created_at DESC
      """.query[SessionRecord].run().toList
    yield result

  private case class SessionWithId(
      publicSessionId: UUID,
      userId: UserId,
      clientId: ClientId,
      userAgent: UserAgentInfo,
      createdAt: Instant,
      amr: Map[PassedAuthFactor, PassedFactorRecord],
  )

  private given DbCodec[SessionWithId] = DbCodec.derived[SessionWithId]

  override def findByUserIdWithId(
      userId: UserId,
  ): Task[List[(UUID, SessionRecord)]] =
    for
      now    <- Clock.instant
      result <- xa.connectMeasured("find-sessions-by-user-with-id"):
        sql"""
          SELECT public_session_id, user_id, client_id, user_agent, created_at, amr
          FROM sso_sessions
          WHERE
            user_id = $userId
            AND expires_at > $now
            AND (idle_expires_at IS NULL OR idle_expires_at > $now)
          ORDER BY public_session_id DESC
        """.query[SessionWithId].run().toList
    yield result.map: r =>
      (r.publicSessionId, SessionRecord(r.userId, r.clientId, r.userAgent, r.createdAt, r.amr))

  override def invalidate(publicSessionId: UUID): Task[Unit] =
    for
      now <- Clock.instant
      _   <- xa.connectMeasured("invalidate-session"):
        sql"""
          UPDATE sso_sessions
          SET expires_at = $now
          WHERE public_session_id = $publicSessionId
        """.update.run()
    yield ()

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
