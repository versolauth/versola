package versola.oauth.session

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.UserAgentData
import versola.oauth.session.model.{UserAgentDetails, UserAgentId}
import versola.user.model.UserId
import versola.util.postgres.BasicCodecs
import zio.{Clock, Duration, Task, ZIO, ZLayer}

import java.util.UUID

class PostgresUserAgentRepository(xa: TransactorZIO) extends UserAgentRepository, BasicCodecs:

  given DbCodec[UserAgentId] = DbCodec.UUIDCodec.biMap(UserAgentId(_), identity[UUID])
  given DbCodec[UserId] = DbCodec.UUIDCodec.biMap(UserId(_), identity[UUID])
  given DbCodec[UserAgentDetails] = DbCodec.derived[UserAgentDetails]

  override def find(id: UserAgentId): Task[Option[UserAgentDetails]] =
    Clock.instant.flatMap: now =>
      xa.connectMeasured("find-user-agent"):
        sql"""
          SELECT platform, os, browser, version
          FROM user_agents
          WHERE id = $id AND expires_at > $now
        """.query[UserAgentDetails].run().headOption

  override def findMany(ids: List[UserAgentId]): Task[Map[UserAgentId, UserAgentDetails]] =
    if ids.isEmpty then ZIO.succeed(Map.empty)
    else
      Clock.instant.flatMap: now =>
        xa.connectMeasured("find-many-user-agents"):
          sql"""
            SELECT id, platform, os, browser, version
            FROM user_agents
            WHERE id = ANY($ids) AND expires_at > $now
          """.query[(UserAgentId, UserAgentDetails)].run().toMap

  override def create(id: UserAgentId, data: UserAgentData, ttl: Duration): Task[Unit] =
    val details = data.details
    Clock.instant.flatMap: now =>
      xa.connectMeasured("create-user-agent"):
        sql"""
          INSERT INTO user_agents (id, user_id, platform, os, browser, version, raw, created_at, expires_at)
          VALUES (
            $id, ${data.userId}, ${details.platform}, ${details.os}, ${details.browser}, ${details.version}, ${data.userAgent},
            $now, ${now.plusSeconds(ttl.toSeconds)}
          )
          ON CONFLICT (id) DO NOTHING
        """.update.run()
    .unit

  override def touch(id: UserAgentId, data: UserAgentData, ttl: Duration): Task[Boolean] =
    val details = data.details
    Clock.instant.flatMap: now =>
      xa.connectMeasured("touch-user-agent"):
        sql"""
          UPDATE user_agents
          SET expires_at = ${now.plusSeconds(ttl.toSeconds)}, user_id = ${data.userId},
              platform = ${details.platform}, os = ${details.os}, browser = ${details.browser}, version = ${details.version}
          WHERE id = $id
        """.update.run() > 0

object PostgresUserAgentRepository:
  def live: ZLayer[TransactorZIO, Throwable, UserAgentRepository] =
    ZLayer.fromFunction(PostgresUserAgentRepository(_))
