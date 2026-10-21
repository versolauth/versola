package versola.oauth.session.model

import java.time.Instant

/** A live session together with the `idle_expires_at` column [[SessionRecord]] does not carry:
 *  `None` for a session created without an idle window.
 */
case class StoredSession(
    record: SessionRecord,
    idleExpiresAt: Option[Instant],
)
