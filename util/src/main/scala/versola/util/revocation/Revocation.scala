package versola.util.revocation

import java.time.Instant
import java.time.temporal.ChronoUnit

/** What a token has to be checked against to be rejected before its `exp`.
  *
  * Four kinds, each the narrowest key that covers what its caller means to end: a client's `/revoke`
  * replaces one token and leaves the session running, a leaked refresh chain takes down everything
  * it ever issued and leaves the session's other grants running, a logout ends one SSO session and
  * leaves the user's other sessions running, and an administrator ending a user's access means all
  * of them at once.
  *
  * The ids are plain strings: the services each name them with a type of their own, all of which are
  * strings, and the service-side factories (`EdgeRevocationKeys`, `AuthRevocationKeys`) are where a
  * claim becomes a key. The encoded form is the contract between the two services' tables and the
  * notifications sent about them, so a change to it is a change to both.
  */
enum RevocationKey:
  /** One access token, named by its `jti`. A string rather than the token itself, which is an array
    * in auth, and an array has no value equality: as a map key it would never match.
    */
  case Jti(id: String)

  /** Every access token this refresh-token family ever issued, named by their shared `fam`.
    *
    * Auth revokes a leaked chain without enumerating what it issued: the tokens are named
    * collectively by the family they came from, so a token minted by a generation auth no longer
    * keeps a record of is still covered.
    */
  case Fam(id: String)

  /** Every access token carrying this `sid`, including ones a service has no session row for (a
    * bearer token presented straight to the edge).
    */
  case Sid(id: String)

  /** Every access token issued to this user across every session and every client.
    *
    * Unlike the other three this one outlives what it revokes: the user can log in again and must
    * not be locked out by an entry aimed at the tokens they held before. Which is why an entry under
    * this key carries [[Revocation.issuedBefore]] and the others do not.
    *
    * What the bound means is easy to get wrong, so it is spelled out: a token's `iat` is whole
    * seconds, and a token issued in the second of the change, or later, is the user's own (the sign-in
    * or the refresh that followed the change), so only a token issued in an EARLIER second is revoked.
    * Readers compare with [[Revocation.revokes]], where the bound itself counts as revoked, so the
    * writer stores the last second it revokes: [[Revocation.userBound]], never the instant of the
    * change. Storing the instant refuses a token minted right after the change whenever it falls in
    * the same wall-clock second, which is most of the time for a sign-in that follows an admin call
    * (a password set, a role removal and then a refresh) and shows up as a 401 at `/userinfo`.
    * The price is a token minted in that second but just before the change, which keeps working until
    * it expires; `iat` cannot tell the two apart.
    *
    * Only the writer needs to know this: the cache, the pull feed and older `edge` versions read the
    * stored value as it is. An entry written from an event time that is itself whole seconds (an OIDC
    * back-channel logout token's `toe`, in `edge`) is not written through [[Revocation.userBound]]: the
    * second of such an event cannot be ordered against a token's `iat` and stays on the revoked side.
    */
  case Sub(subject: String)

  def encoded: String = this match
    case Jti(id) => s"${RevocationKey.JtiPrefix}$id"
    case Fam(id) => s"${RevocationKey.FamPrefix}$id"
    case Sid(id) => s"${RevocationKey.SidPrefix}$id"
    case Sub(subject) => s"${RevocationKey.SubPrefix}$subject"

object RevocationKey:
  private val JtiPrefix = "jti:"
  private val FamPrefix = "fam:"
  private val SidPrefix = "sid:"
  private val SubPrefix = "sub:"

  /** `None` for a prefix this version has no case for, or an empty id: a row written by a newer
    * version is skipped rather than failing the load.
    */
  def decode(encoded: String): Option[RevocationKey] =
    if encoded.startsWith(JtiPrefix) then nonEmptyId(encoded, JtiPrefix).map(Jti(_))
    else if encoded.startsWith(FamPrefix) then nonEmptyId(encoded, FamPrefix).map(Fam(_))
    else if encoded.startsWith(SidPrefix) then nonEmptyId(encoded, SidPrefix).map(Sid(_))
    else if encoded.startsWith(SubPrefix) then nonEmptyId(encoded, SubPrefix).map(Sub(_))
    else None

  private def nonEmptyId(encoded: String, prefix: String): Option[String] =
    Some(encoded.stripPrefix(prefix)).filter(_.nonEmpty)

/** @param expiresAt when the revoked token would have expired on its own, after which the entry
  *                  stops mattering: a token past its `exp` is rejected by signature validation
  *                  anyway. Derived from the client's access token TTL where the real `exp` isn't
  *                  at hand, which only ever over-retains.
  * @param issuedBefore only tokens whose `iat` is not after this instant are revoked, so the bound
  *                     itself counts as revoked; `None` revokes every token the key names, whenever it
  *                     was issued. It exists for [[RevocationKey.Sub]], where tokens the entry must not
  *                     touch can still be minted while it is live: the user logging in again. A
  *                     user-wide entry written for a change at an instant carries
  *                     [[Revocation.userBound]] of it, not the instant, see [[RevocationKey.Sub]].
  */
case class Revocation(key: RevocationKey, expiresAt: Instant, issuedBefore: Option[Instant])

object Revocation:
  /** The `issuedBefore` of a user-wide entry for a change made at `changedAt`: the last second whose tokens
    * the change revokes, which is the second before the one the change falls in.
    *
    * A token's `iat` is whole seconds and [[revokes]] counts the bound as revoked, so a bound of `changedAt`
    * itself, microseconds and all, would revoke every token minted in the second of the change, including one
    * minted a moment after it by the sign-in or refresh the change was made to allow. The second before it is
    * the largest value that revokes everything issued in earlier seconds and nothing issued in the change's own
    * second or later. It is also whole seconds, so the row, the notification (which floors it) and the pull
    * feed's `before` all carry the same value.
    *
    * Taking the maximum of two such bounds (a later change widens, an earlier one never narrows) stays correct,
    * because the mapping is monotonic: a later `changedAt` never gives an earlier bound.
    */
  def userBound(changedAt: Instant): Instant =
    changedAt.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1)

  extension (revocation: Revocation)
    /** Whether the issuance bound of the entry covers a token issued at `issuedAt`, expiry aside: the bound
      * itself counts as revoked. See [[Revocation.userBound]] for what that means for the user-wide entries.
      */
    def revokes(issuedAt: Instant): Boolean =
      revocation.issuedBefore.forall(!issuedAt.isAfter(_))
