package versola.oauth.revocation

import versola.oauth.client.model.{ClientId, OAuthClientRecord, TenantId}
import versola.oauth.model.AccessTokenPayload
import versola.oauth.session.model.{PublicSessionId, RefreshTokenFamilyId}
import zio.Duration

import java.time.Instant

// A copy of `versola.edge.revocation.Revocation`, on purpose and for now: the module moves to `util`
// in the next step of the plan (design-access-token-revocation.md §5, stage 2), and edge and auth
// then share one definition. Until then the key format below is the contract between the two, so
// a change here is a change there.

/** What a token has to be checked against to be rejected before its `exp`.
  *
  * Each kind is the narrowest key that covers what its caller means to end: a client's `/revoke`
  * replaces one token and leaves the session running, a leaked refresh chain takes down
  * everything it ever issued and leaves the session's other grants running, a logout ends one SSO
  * session and leaves the user's other sessions running, and an administrator ending a user's
  * access means all of them at once.
  */
enum RevocationKey:
  /** One access token, named by its `jti` as it appears in the token (base64url). A string
    * rather than [[versola.oauth.model.AccessToken]]: that is an array, and an array has no
    * value equality, so as a map key it would never match.
    */
  case Jti(id: String)

  /** Every access token this refresh-token family ever issued, named by their shared `fam`.
    *
    * Auth revokes a leaked chain without enumerating what it issued: the tokens are named
    * collectively by the family they came from, so a token minted by a generation auth no longer
    * keeps a record of is still covered.
    */
  case Fam(id: RefreshTokenFamilyId)

  /** Every access token carrying this `sid` (the public session id). */
  case Sid(id: PublicSessionId)

  /** Every access token issued to this user across every session and every client.
    *
    * Unlike the first three this one outlives what it revokes: the user can log in again and must
    * not be locked out by an entry aimed at the tokens they held before. Which is why an entry
    * under this key carries [[Revocation.issuedBefore]] and the first three do not.
    */
  case Sub(subject: String)

  /** Reserved: every access token of a tenant, issued before [[Revocation.issuedBefore]]. Nothing
    * writes it yet; it is in the codec so that the key format does not change when something
    * does.
    */
  case Tenant(id: TenantId)

  /** Reserved, as [[Tenant]]: every access token of one client. */
  case Client(id: ClientId)

  def encoded: String = this match
    case Jti(id) => s"${RevocationKey.JtiPrefix}$id"
    case Fam(id) => s"${RevocationKey.FamPrefix}$id"
    case Sid(id) => s"${RevocationKey.SidPrefix}$id"
    case Sub(subject) => s"${RevocationKey.SubPrefix}$subject"
    case Tenant(id) => s"${RevocationKey.TenantPrefix}$id"
    case Client(id) => s"${RevocationKey.ClientPrefix}$id"

object RevocationKey:
  private val JtiPrefix = "jti:"
  private val FamPrefix = "fam:"
  private val SidPrefix = "sid:"
  private val SubPrefix = "sub:"
  private val TenantPrefix = "tenant:"
  private val ClientPrefix = "client:"

  /** `None` for a prefix this version has no case for, or an empty id: a row written by a newer
    * version is skipped rather than failing the load.
    */
  def decode(encoded: String): Option[RevocationKey] =
    if encoded.startsWith(JtiPrefix) then nonEmptyId(encoded, JtiPrefix).map(Jti(_))
    else if encoded.startsWith(FamPrefix) then nonEmptyId(encoded, FamPrefix).map(id => Fam(RefreshTokenFamilyId(id)))
    else if encoded.startsWith(SidPrefix) then nonEmptyId(encoded, SidPrefix).map(id => Sid(PublicSessionId(id)))
    else if encoded.startsWith(SubPrefix) then nonEmptyId(encoded, SubPrefix).map(Sub(_))
    else if encoded.startsWith(TenantPrefix) then nonEmptyId(encoded, TenantPrefix).map(id => Tenant(TenantId(id)))
    else if encoded.startsWith(ClientPrefix) then nonEmptyId(encoded, ClientPrefix).map(id => Client(ClientId(id)))
    else None

  private def nonEmptyId(encoded: String, prefix: String): Option[String] =
    Some(encoded.stripPrefix(prefix)).filter(_.nonEmpty)

  /** Everything that could have revoked this token, widening as it goes: the token itself, the
    * refresh chain that issued it, its session, its user, and its client. The client kind is looked
    * up although nothing writes it yet, so that an entry that starts being written is honoured by
    * every replica already running this code. The tenant kind is not: the token does not carry its
    * tenant, so it starts to be looked up together with whatever starts writing it.
    */
  def of(token: AccessTokenPayload): List[RevocationKey] =
    Jti(token.id.encoded) ::
      token.familyId.map(Fam(_)).toList :::
      token.sessionId.map(Sid(_)).toList :::
      List(Sub(token.subject), Client(token.clientId))

/** @param expiresAt when the revoked token would have expired on its own, after which the entry
  *                  stops mattering: a token past its `exp` is rejected by signature validation
  *                  anyway. Derived from the client's access token TTL where the real `exp` is not
  *                  at hand, which only ever over-retains.
  * @param issuedBefore only tokens issued before this instant are revoked; `None` revokes every
  *                     token the key names, whenever it was issued. It exists for the keys under
  *                     which tokens the entry must not touch can still be minted while it is live:
  *                     [[RevocationKey.Sub]] (the user logging in again), and the reserved
  *                     tenant and client kinds.
  */
case class Revocation(key: RevocationKey, expiresAt: Instant, issuedBefore: Option[Instant])

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
  *            session or a user, whose tokens are not known.
  */
case class RevocationTerms(cause: RevocationCause, ttl: Duration)

object RevocationTerms:
  /** A refresh family: every token it issued was issued to this client, so its own TTL bounds them. */
  def forFamily(client: OAuthClientRecord, cause: RevocationCause): RevocationTerms =
    RevocationTerms(cause, client.accessTokenTtl)
