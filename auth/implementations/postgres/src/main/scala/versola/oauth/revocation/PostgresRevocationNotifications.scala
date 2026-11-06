package versola.oauth.revocation

import versola.util.postgres.{NotificationEvent, PostgresConfig, PostgresNotificationListener}
import zio.json.{DecoderOps, JsonDecoder}
import zio.stream.Stream
import zio.{Scope, ZLayer}

import java.time.Instant

class PostgresRevocationNotifications(listener: PostgresNotificationListener) extends RevocationNotifications:

  override def notifications: Stream[Throwable, RevocationEvent] =
    listener.notifications.mapConcat:
      case NotificationEvent.Resubscribed =>
        List(RevocationEvent.Resubscribed)
      case NotificationEvent.Received(notification) =>
        PostgresRevocationNotifications.parseNotification(notification.getParameter)
          .map(RevocationEvent.Revoked(_))
          .toList

object PostgresRevocationNotifications:
  // Not `revocation`, which edge listens on: see V0032 for why the names a database shares carry a prefix.
  private val Channel = "auth_revocation"

  private case class RevocationPayload(key: String, exp: Long, before: Option[Long]) derives JsonDecoder

  /** Total: a payload this version cannot make sense of is dropped rather than failing the
    * stream. The periodic reload reads the same rows from the table, so nothing is lost
    * permanently by ignoring one notification.
    */
  private[revocation] def parseNotification(rawPayload: String): Option[Revocation] =
    for
      payload <- rawPayload.fromJson[RevocationPayload].toOption
      key <- RevocationKey.decode(payload.key)
    yield Revocation(key, Instant.ofEpochSecond(payload.exp), payload.before.map(Instant.ofEpochSecond))

  def live: ZLayer[PostgresConfig & Scope, Throwable, RevocationNotifications] =
    ZLayer:
      PostgresNotificationListener.make(List(Channel)).map(PostgresRevocationNotifications(_))
