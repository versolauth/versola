package versola.oauth.session.model

/** A session that is no longer live (expired, idle-timed-out or ended) but whose row has not been
 *  swept yet. Refresh tokens name only their session, so this is what ties a token that outlived
 *  its session back to the user agent it was issued on.
 */
case class EndedSession(
    publicId: PublicSessionId,
    userAgentId: UserAgentId,
)
