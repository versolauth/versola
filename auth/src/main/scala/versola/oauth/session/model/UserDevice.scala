package versola.oauth.session.model

/** One device of a user as auth stores it: the user agent, at most one live SSO session under it,
 *  and, when there is none, the public ids of the sessions that ended and are still stored.
 *
 *  `userAgent` is `None` only for a live session whose user agent row is gone (expired and swept
 *  before the session was); the session then carries no parsed details either.
 */
case class UserDevice(
    userAgent: Option[UserAgentRecord],
    session: Option[SessionUnderUserAgent],
    endedSessionIds: List[PublicSessionId],
)
