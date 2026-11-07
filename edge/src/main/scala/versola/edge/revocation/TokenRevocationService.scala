package versola.edge.revocation

import versola.edge.OAuthClientService
import versola.edge.model.SessionId
import versola.util.revocation.{Revocation, RevocationCache, RevocationKey}
import zio.*

import java.time.Instant

trait TokenRevocationService:
  /** Tokens die and the sessions they belong to do not, which is what keeps a client's
    * `/revoke` from logging every other client of that SSO session out.
    *
    * Each key is the narrowest thing that covers what the caller meant to end: a `jti` for
    * one token, whose real `exp` auth had in hand, or a `fam` for every token a refresh chain
    * issued, bounded by the client's TTL because auth holds none of them to read an `exp`
    * off. They arrived on one event under one `expiresAt`, so they are written once rather
    * than once per key.
    */
  def revokeTokens(keys: NonEmptyChunk[RevocationKey], expiresAt: Instant): Task[Unit]

  /** One SSO session ends: every token bearing this `sid`, including bearer tokens this
    * edge has no session row for and ones superseded by rotation.
    */
  def revokeSession(sid: SessionId): Task[Unit]

  /** Every session this user has here ends, which is how an administrator revokes their
    * access. `revokedAt` is the event's own `iat`, and bounds the entry to the tokens that
    * predate it so the user can log back in immediately.
    */
  def revokeUser(subject: String, revokedAt: Instant): Task[Unit]

  /** Whether any of these keys revokes a token issued at `issuedAt`. Answered from memory,
    * always: see [[RevocationCache.Impl]].
    */
  def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean]

object TokenRevocationService:
  /** Used only when this edge knows of no client at all, which means it has yet to sync one.
    * Long enough to cover a plausible token rather than tuned: getting it wrong only makes an
    * entry outlive its usefulness.
    */
  private[revocation] val FallbackAccessTokenTtl = 1.hour

  def live: ZLayer[EdgeRevocationRepository & RevocationCache & OAuthClientService, Nothing, TokenRevocationService] =
    ZLayer.fromFunction(make)

  private[revocation] def make(
      repository: EdgeRevocationRepository,
      cache: RevocationCache,
      clientService: OAuthClientService,
  ): Impl =
    Impl(repository, cache, clientService)

  /** Writes revocations and asks the list that every request is checked against.
    *
    * The list itself, which holds every revocation that has not expired, in memory, and
    * answers from it alone, is [[RevocationCache]]: this is the part that is edge's own, which
    * is deciding what an event auth sent means and for how long it has to be remembered.
    */
  class Impl(
      repository: EdgeRevocationRepository,
      cache: RevocationCache,
      clientService: OAuthClientService,
  ) extends TokenRevocationService:

    override def revokeTokens(keys: NonEmptyChunk[RevocationKey], expiresAt: Instant): Task[Unit] =
      repository.revokeAll(keys.map(Revocation(_, expiresAt, issuedBefore = None)).toList)

    /** The entry only has to outlive the longest-lived token the session could have been
      * issued. Edge never sees a token's `iat`, but `exp = iat + accessTokenTtl` and
      * `iat <= now`, so `now + accessTokenTtl` is a safe upper bound. Which clients took
      * part in the session is not looked up: it would cost a query on the logout path to
      * narrow a bound whose only cost when too wide is holding one entry for a few extra
      * minutes, so the widest TTL across the clients this edge knows is used instead.
      */
    override def revokeSession(sid: SessionId): Task[Unit] =
      for
        ttl <- widestAccessTokenTtl
        now <- Clock.instant
        _ <- revoke(Revocation(RevocationKey.Sid(sid), now.plusSeconds(ttl.toSeconds), issuedBefore = None))
      yield ()

    /** `issuedBefore` is the event's own `iat`: the OP minted both it and the access tokens
      * it invalidates, so the two timestamps come from the same clock and need no skew
      * allowance. Without it the user could not log back in until the entry expired.
      */
    override def revokeUser(subject: String, revokedAt: Instant): Task[Unit] =
      widestAccessTokenTtl.flatMap: ttl =>
        revoke(Revocation(
          key = RevocationKey.Sub(subject),
          expiresAt = revokedAt.plusSeconds(ttl.toSeconds),
          issuedBefore = Some(revokedAt),
        ))

    private def widestAccessTokenTtl: UIO[Duration] =
      clientService.listClients.map(_.map(_.accessTokenTtl).maxOption.getOrElse(FallbackAccessTokenTtl))

    /** Writes the revocation and returns — it is not also applied to this replica's own map
      * here. The write's own notification comes back over the same feed as anyone else's, so
      * the replica that revoked a token learns about it exactly like every other one, rather
      * than by a special case that only fires for a write it made itself. That keeps every
      * replica's view explainable by one rule (apply what the feed or a catch-up delivers)
      * instead of two, and the asymmetry a local apply would add — this replica correct
      * sooner than the rest, for tokens revoked through it — was never a guarantee callers
      * were told to rely on: the staleness metric (`revocation_cache_staleness_seconds`) already prices this path in.
      */
    private def revoke(revocation: Revocation): Task[Unit] =
      repository.revokeAll(List(revocation))

    override def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean] =
      cache.isRevoked(keys, issuedAt)
