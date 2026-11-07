package versola.oauth.revocation

import versola.oauth.client.model.{ClientId, OAuthClientRecord, TenantId}
import zio.Duration

/** Why a revocation was written. Stored for operators and for whatever reads the table later;
  * nothing in auth decides on it.
  */
enum RevocationCause(val code: String):
  case Revoke extends RevocationCause("revoke")
  case CodeReplay extends RevocationCause("code_replay")
  case RefreshReuse extends RevocationCause("refresh_reuse")
  case Logout extends RevocationCause("logout")
  case AdminRevoked extends RevocationCause("admin_revoked")
  case UserAgentForgotten extends RevocationCause("user_agent_forgotten")
  case UserRevoked extends RevocationCause("user_revoked")
  case UserDeleted extends RevocationCause("user_deleted")

/** What a repository method that ends a session or a refresh family needs to write the revocation
  * in the same transaction: the method knows the key (it is what the method ends) and the instant
  * (its own clock), the caller knows why and for how long a token could still be alive.
  *
  * @param ttl how long after the revocation a token it covers can still be valid: the client's
  *            `accessTokenTtl` for a family, the widest `accessTokenTtl` of any client for a
  *            user, whose tokens are not known, and for a session the fallback when the clients
  *            it served are not all known (see [[ttlOfSession]]).
  * @param tenantId the tenant the key belongs to, for the feed that serves edges only their tenants' rows:
  *                 the client's for a family, `None` for a session or a user, which no one tenant owns.
  * @param ttlByClient every client's `accessTokenTtl`, which a session's entry is bounded by the
  *                    clients that session served with, instead of by the longest-lived client
  *                    of the deployment. Empty where the key is not a session's.
  */
case class RevocationTerms(
    cause: RevocationCause,
    ttl: Duration,
    tenantId: Option[TenantId],
    ttlByClient: Map[ClientId, Duration],
):
  /** A session's tokens were issued to the clients it served and to no others, so the longest of
    * their TTLs bounds them. A client this deployment no longer knows could have had any TTL, so
    * one unknown client puts the whole bound back at [[ttl]]: over-retained, never under-retained.
    */
  def ttlOfSession(clients: List[ClientId]): Duration =
    val known = clients.flatMap(ttlByClient.get)
    if clients.nonEmpty && known.sizeIs == clients.size then known.max else ttl

object RevocationTerms:
  /** A refresh family: every token it issued was issued to this client, so its own TTL bounds them. */
  def forFamily(client: OAuthClientRecord, cause: RevocationCause): RevocationTerms =
    RevocationTerms(cause = cause, ttl = client.accessTokenTtl, tenantId = Some(client.tenantId), ttlByClient = Map.empty)
