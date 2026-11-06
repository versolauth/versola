package versola.oauth.session.model

import java.time.Instant

/** A user agent (device or browser install) as auth stores it in `user_agents`: the raw
 *  `User-Agent` header seen when it was last (re)issued, its parsed parts, when it was first
 *  seen and until when it is remembered. The remembered-until instant slides forward every time
 *  the device signs in again; there is no separate last-seen column.
 */
case class UserAgentRecord(
    id: UserAgentId,
    userAgent: Option[String],
    platform: Option[String],
    os: Option[String],
    browser: Option[String],
    version: Option[String],
    createdAt: Instant,
    expiresAt: Instant,
)
