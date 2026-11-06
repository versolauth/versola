package versola.oauth.revocation

import versola.oauth.client.model.{ClientId, OAuthClientRecord}
import zio.{Duration, durationInt}

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
  case PasswordChanged extends RevocationCause("password_changed")
  case OfflineAccessRevoked extends RevocationCause("offline_access_revoked")
  case RolesChanged extends RevocationCause("roles_changed")

/** What a repository method that ends a session or a refresh family needs to write the revocation
  * in the same transaction: the method knows the key (it is what the method ends) and the instant
  * (its own clock), the caller knows why and for how long a token could still be alive.
  *
  * @param ttl how long after the revocation a token it covers can still be valid: the client's
  *            `accessTokenTtl` for a family, the widest `accessTokenTtl` of any client for a
  *            user, whose tokens are not known, and for a session the fallback when the clients
  *            it served are not all known (see [[ttlOfSession]]).
  * @param ttlByClient every client's `accessTokenTtl`, which a session's entry is bounded by the
  *                    clients that session served with, instead of by the longest-lived client
  *                    of the deployment. Empty where the key is not a session's.
  */
case class RevocationTerms(cause: RevocationCause, ttl: Duration, ttlByClient: Map[ClientId, Duration]):
  /** A session's tokens were issued to the clients it served and to no others, so the longest of
    * their TTLs bounds them. A client this deployment no longer knows could have had any TTL, so
    * one unknown client puts the whole bound back at [[ttl]]: over-retained, never under-retained.
    */
  def ttlOfSession(clients: List[ClientId]): Duration =
    val known = clients.flatMap(ttlByClient.get)
    if clients.nonEmpty && known.sizeIs == clients.size then known.max else ttl

object RevocationTerms:
  /** For a key that names a user, whose tokens went to clients that are not known: bounded by the widest
    * `accessTokenTtl` of any client. With no client configured nothing could have been issued, and an
    * hour is the answer that over-retains rather than under-retains.
    */
  def widest(cause: RevocationCause, accessTokenTtls: Map[ClientId, Duration]): RevocationTerms =
    RevocationTerms(cause, accessTokenTtls.values.maxOption.getOrElse(1.hour), accessTokenTtls)

  /** A refresh family: every token it issued was issued to this client, so its own TTL bounds them. */
  def forFamily(client: OAuthClientRecord, cause: RevocationCause): RevocationTerms =
    RevocationTerms(cause, client.accessTokenTtl, Map.empty)
