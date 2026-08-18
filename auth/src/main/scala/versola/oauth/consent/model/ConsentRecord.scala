package versola.oauth.consent.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.user.model.UserId
import zio.prelude.Equal

import java.time.Instant

/** A consent grant recorded for a (user, client) pair. Outlives the SSO session so a new
  * session reuses the grant instead of re-prompting.
  *
  * @param scope the scope the user actually granted, which may be narrower than what was requested
  * @param expiresAt when the grant stops being reused; `None` means until explicitly revoked
  */
case class ConsentRecord(
    userId: UserId,
    clientId: ClientId,
    scope: Set[ScopeToken],
    grantedAt: Instant,
    expiresAt: Option[Instant],
) derives CanEqual, Equal:

  /** Whether this grant may be reused for `requested` at `now`, i.e. it has not expired and
    * already covers everything now being asked for. */
  def covers(requested: Set[ScopeToken], now: Instant): Boolean =
    expiresAt.forall(_.isAfter(now)) && requested.subsetOf(scope)

object ConsentRecord:
  given Equal[Instant] = Equal.default
