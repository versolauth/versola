package versola.util.revocation

import java.time.Instant

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
  * @param issuedBefore only tokens issued before this instant are revoked; `None` revokes every
  *                     token the key names, whenever it was issued. It exists for
  *                     [[RevocationKey.Sub]], where tokens the entry must not touch can still be
  *                     minted while it is live: the user logging in again.
  */
case class Revocation(key: RevocationKey, expiresAt: Instant, issuedBefore: Option[Instant])
