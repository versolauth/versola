package versola.edge

import com.zaxxer.hikari.HikariDataSource
import versola.edge.revocation.{Revocation, RevocationKey, RevocationNotifications}
import versola.util.postgres.PostgresNotificationListener
import zio.json.{DecoderOps, JsonDecoder}
import zio.stream.Stream
import zio.{Scope, ZIO, ZLayer}

import java.time.Instant

class PostgresRevocationNotifications(listener: PostgresNotificationListener) extends RevocationNotifications:

  override def notifications: Stream[Throwable, Revocation] =
    listener.notifications
      .map(notification => PostgresRevocationNotifications.parseNotification(notification.getParameter))
      .collectSome

object PostgresRevocationNotifications:
  private val Channel = "revocation"

  private case class RevocationPayload(key: String, exp: Long, before: Option[Long]) derives JsonDecoder

  /** Total: a payload this version cannot make sense of is dropped rather than failing the
    * stream. The periodic reload reads the same rows from the table, so nothing is lost
    * permanently by ignoring one notification.
    */
  private[edge] def parseNotification(rawPayload: String): Option[Revocation] =
    for
      payload <- rawPayload.fromJson[RevocationPayload].toOption
      key <- RevocationKey.decode(payload.key)
    yield Revocation(key, Instant.ofEpochSecond(payload.exp), payload.before.map(Instant.ofEpochSecond))

  def live: ZLayer[HikariDataSource & Scope, Throwable, RevocationNotifications] =
    ZLayer:
      PostgresNotificationListener.make(List(Channel)).map(PostgresRevocationNotifications(_))
