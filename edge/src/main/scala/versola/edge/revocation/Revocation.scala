package versola.edge.revocation

import versola.edge.model.{AccessTokenId, SessionId}

import java.time.Instant

/** What a token has to be checked against to be rejected before its `exp`.
  *
  * Three kinds, each the narrowest key that covers what its caller means to end: a
  * client's `/revoke` replaces one token and leaves the session running, a logout ends
  * one SSO session and leaves the user's other sessions running, and an administrator
  * ending a user's access means all of them at once.
  */
enum RevocationKey:
  /** One access token, named by its `jti`. */
  case Jti(id: AccessTokenId)

  /** Every access token carrying this `sid`, including ones this edge has no
    * `edge_sessions` row for (a bearer token presented straight to the proxy).
    */
  case Sid(id: SessionId)

  /** Every access token issued to this user across every session and every client.
    *
    * Unlike the other two this one outlives what it revokes: the user can log in again
    * and must not be locked out by an entry aimed at the tokens they held before. Which
    * is why an entry under this key carries [[Revocation.issuedBefore]] and the other two
    * do not.
    */
  case Sub(userId: String)

  def encoded: String = this match
    case Jti(id)     => s"${RevocationKey.JtiPrefix}$id"
    case Sid(id)     => s"${RevocationKey.SidPrefix}$id"
    case Sub(userId) => s"${RevocationKey.SubPrefix}$userId"

object RevocationKey:
  private val JtiPrefix = "jti:"
  private val SidPrefix = "sid:"
  private val SubPrefix = "sub:"

  def decode(encoded: String): Option[RevocationKey] =
    if encoded.startsWith(JtiPrefix) then nonEmptyId(encoded, JtiPrefix).map(id => Jti(AccessTokenId(id)))
    else if encoded.startsWith(SidPrefix) then nonEmptyId(encoded, SidPrefix).map(id => Sid(SessionId(id)))
    else if encoded.startsWith(SubPrefix) then nonEmptyId(encoded, SubPrefix).map(Sub(_))
    else None

  private def nonEmptyId(encoded: String, prefix: String): Option[String] =
    Some(encoded.stripPrefix(prefix)).filter(_.nonEmpty)

  /** Everything that could have revoked the token these claims came from, widening as it
    * goes: the token itself, then the session it belongs to, then its user.
    */
  def of(jti: AccessTokenId, sid: Option[SessionId], subject: String): List[RevocationKey] =
    Jti(jti) :: sid.map(Sid(_)).toList ::: List(Sub(subject))

/** @param expiresAt when the revoked token would have expired on its own, after which the
  *                  entry stops mattering: a token past its `exp` is rejected by signature
  *                  validation anyway. Derived from the client's access token TTL where the
  *                  real `exp` isn't at hand, which only ever over-retains.
  * @param issuedBefore only tokens issued before this instant are revoked; `None` revokes
  *                     every token the key names, whenever it was issued. It exists for
  *                     [[RevocationKey.Sub]], where tokens the entry must not touch can
  *                     still be minted while it is live — the user logging in again.
  */
case class Revocation(key: RevocationKey, expiresAt: Instant, issuedBefore: Option[Instant] = None)
