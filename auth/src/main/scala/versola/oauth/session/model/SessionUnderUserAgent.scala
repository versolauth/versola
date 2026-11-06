package versola.oauth.session.model

import java.time.Instant

/** The active session for a user agent (device/browser), flattened together with
 *  that user agent's details, so callers (e.g. controllers) never need to look up
 *  user agents or join session records on their own.
 *
 *  Sibling sessions sharing the same (userId, userAgentId) are invalidated on
 *  creation, so there is at most one active session per user agent.
 */
case class SessionUnderUserAgent(
    /** Not rendered to end users (see [[PublicSessionId]]'s scaladoc), but kept
     *  available to internal callers (e.g. central) that may need to reference or
     *  invalidate this specific session. */
    publicId: PublicSessionId,
    clients: List[ClientEntry],
    createdAt: Instant,
    platform: Option[String],
    os: Option[String],
    browser: Option[String],
    version: Option[String],
    expiresAt: Instant,
    /** `None` when the session was created without an idle window. */
    idleExpiresAt: Option[Instant],
    /** The user agent row this session runs under; `None` if it has expired and been swept. */
    userAgent: Option[UserAgentRecord],
)
