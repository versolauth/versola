package versola.edge.revocation

import versola.edge.model.{AccessTokenId, SessionId}
import versola.edge.{EdgeConfig, OAuthClientService}
import zio.*
import zio.stm.{TMap, ZSTM}

import java.time.Instant

trait TokenRevocationService:
  /** One token dies and the session it belongs to does not, which is what keeps a client's
    * `/revoke` from logging every other client of that SSO session out. The token's real
    * `exp` is known to the caller, since auth had it in hand.
    */
  def revokeToken(jti: AccessTokenId, expiresAt: Instant): Task[Unit]

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
    * always: see [[TokenRevocationService.Impl]].
    */
  def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean]

object TokenRevocationService:
  /** Used only when this edge knows of no client at all, which means it has yet to sync one.
    * Long enough to cover a plausible token rather than tuned: getting it wrong only makes an
    * entry outlive its usefulness.
    */
  private[revocation] val FallbackAccessTokenTtl = 1.hour

  def live: ZLayer[
    RevocationRepository & RevocationNotifications & OAuthClientService & EdgeConfig & Scope,
    Nothing,
    TokenRevocationService,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[RevocationRepository]
        notifications <- ZIO.service[RevocationNotifications]
        clientService <- ZIO.service[OAuthClientService]
        config <- ZIO.service[EdgeConfig]
        service <- make(repository, clientService)
        // Loaded before the layer completes: a replica that started while a token was
        // revoked would otherwise serve requests with an empty list and accept it.
        _ <- service.reload
        // Jittered so replicas started together (a rolling deploy, or a host coming back up)
        // don't stay in lockstep and scan the table at the same instant for the rest of their
        // lives.
        _ <- service.reload.repeat(Schedule.spaced(config.revocation.reloadInterval).jittered).forkScoped
        _ <- service.consume(notifications).forkScoped
      yield service

  private[revocation] def make(repository: RevocationRepository, clientService: OAuthClientService): UIO[Impl] =
    for
      entries <- TMap.empty[RevocationKey, Revocation].commit
      now <- Clock.instant
      lastReload <- Ref.make(now)
    yield Impl(repository, clientService, entries, lastReload)

  /** Holds every revocation that has not expired yet, in memory, and answers from it alone.
    *
    * The request path never reads the database — not on a miss, not when the list is large,
    * not while the replica is catching up. A proxied request is the wrong place to discover
    * that Postgres is slow, and a check that sometimes costs a query is a check whose cost
    * an attacker can choose by presenting tokens that miss.
    *
    * What makes that safe is that the list is small and stays small on its own. An entry is
    * kept only until the last token it could cover would have expired anyway, which is one
    * access token TTL — minutes. So the cache holds the revocations of the last few minutes,
    * not a history, and there is no capacity to run out of and no eviction to reason about.
    *
    * The cost is that it is eventually consistent. A revocation reaches this replica when
    * its notification arrives (typically within a second), and if the feed was down when it
    * was written, when the next reload runs. Between those, this replica still accepts the
    * token. That window is what [[RevocationMetrics.staleness]] measures.
    */
  class Impl(
      repository: RevocationRepository,
      clientService: OAuthClientService,
      entries: TMap[RevocationKey, Revocation],
      lastReload: Ref[Instant],
  ) extends TokenRevocationService:

    override def revokeToken(jti: AccessTokenId, expiresAt: Instant): Task[Unit] =
      revoke(Revocation(RevocationKey.Jti(jti), expiresAt, issuedBefore = None))

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

    private def revoke(revocation: Revocation): Task[Unit] =
      repository.revokeAll(List(revocation)) *>
        // The notification this write triggers comes back to this replica too, but not
        // before the caller is answered: the client that asked for the revocation would
        // otherwise be able to use the token again on the very next request.
        put(List(revocation))

    override def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean] =
      Clock.instant.flatMap: now =>
        // One transaction for all of a token's keys, so the answer describes a single state
        // of the list rather than one that changed underneath the check.
        ZSTM.exists(keys)(key => entries.get(key).map(_.exists(applies(_, issuedAt, now)))).commit

    /** A token issued after `issuedBefore` postdates what the entry was aimed at — the user
      * logged in again after an administrator ended their sessions — and is left alone.
      *
      * The boundary second belongs to the revocation. `iat` is whole seconds, so a token
      * minted in the same second as the revocation cannot be ordered against it, and the
      * only safe reading of a tie is that the token is one of the revoked ones.
      */
    private def applies(revocation: Revocation, issuedAt: Instant, now: Instant): Boolean =
      revocation.expiresAt.isAfter(now) &&
        revocation.issuedBefore.forall(!issuedAt.isAfter(_))

    private def put(revocations: List[Revocation]): UIO[Unit] =
      ZSTM.foreachDiscard(revocations)(revocation => entries.put(revocation.key, revocation)).commit

    private[revocation] def entryCount: UIO[Int] =
      entries.size.commit

    /** Applies revocations written by this or any other replica as they arrive.
      *
      * Reconnects arrive on the same stream, because a gap in the feed is not a no-op here:
      * revocations published while it was down were never delivered and never will be, so the
      * list is rebuilt from the table before it is trusted again.
      */
    private[revocation] def consume(notifications: RevocationNotifications): UIO[Unit] =
      notifications.notifications
        .runForeach:
          case RevocationEvent.Resubscribed => reload
          case RevocationEvent.Revoked(revocation) => put(List(revocation))
        .catchAllCause(cause => ZIO.logErrorCause("Revocation notification stream failed", cause))

    /** Catches this replica up with the durable list: on startup, after the notification feed
      * reconnects, and periodically in case a notification was lost some other way.
      */
    private[revocation] def reload: UIO[Unit] =
      (for
        now <- Clock.instant
        // Dropping what has expired needs no database and happens before anything that
        // could fail: it is the only thing bounding what this replica holds, so it must
        // still run when the database is unreachable.
        _ <- entries.retainIf((_, revocation) => revocation.expiresAt.isAfter(now)).commit
        active <- repository.listActive
        // Merged into what is already held rather than replacing it: a revocation that
        // arrived by notification while the query was in flight is not in `active`, and
        // replacing would drop it.
        count <- ZSTM.foreachDiscard(active)(revocation => entries.put(revocation.key, revocation))
          .zipRight(entries.size)
          .commit
        _ <- lastReload.set(now)
        _ <- RevocationMetrics.reloaded(count)
      yield ()).catchAllCause: cause =>
        // Keeping what it already holds is the safe half of the failure: the list can only
        // be missing revocations written since it was last read, never wrong about the ones
        // it has.
        for
          now <- Clock.instant
          previous <- lastReload.get
          _ <- RevocationMetrics.reloadFailed(now.getEpochSecond - previous.getEpochSecond)
          _ <- ZIO.logWarningCause("Failed to refresh the revocation list; serving from the last one loaded", cause)
        yield ()
