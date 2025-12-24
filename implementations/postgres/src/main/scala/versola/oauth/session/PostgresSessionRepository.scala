package versola.oauth.session

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.*
import versola.auth.model.{AccessToken, RefreshToken}
import versola.oauth.client.model.ClientId
import versola.oauth.session.model.{SessionId, SessionRecord, WithTtl}
import versola.user.model.UserId
import versola.util.MAC
import versola.util.postgres.BasicCodecs
import zio.prelude.These
import zio.{Clock, Duration, Task}

import java.time.Instant
import java.util.UUID

class PostgresSessionRepository(xa: TransactorZIO) extends SessionRepository, BasicCodecs:
  given DbCodec[MAC] = DbCodec.ByteArrayCodec.biMap(MAC(_), identity[Array[Byte]])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[ClientId] = DbCodec.StringCodec.biMap(ClientId(_), identity[String])
  given DbCodec[SessionRecord] = DbCodec.derived[SessionRecord]

  override def create(
      id: MAC.Of[SessionId],
      session: SessionRecord,
      ttl: zio.Duration,
  ): Task[Unit] =
    Clock.instant.flatMap: now =>
      xa.connect:
        sql"""
          INSERT INTO sso_sessions (id, client_id, user_id, expires_at)
          VALUES (
            $id,
            ${session.clientId},
            ${session.userId},
            ${now.plusSeconds(ttl.toSeconds)}
          )
        """.update.run()
      .unit

  override def find(id: MAC.Of[SessionId]): Task[Option[SessionRecord]] =
    for
      now <- Clock.instant
      result <- xa.connect:
        sql"""
          SELECT user_id, client_id, expires_at
          FROM sso_sessions
          WHERE id = $id
        """.query[(SessionRecord, Instant)].run().headOption
          .collect { case (record, expiresAt) if expiresAt.isAfter(now) => record }
    yield result

