package versola.edge.revocation

import versola.edge.model.{AccessTokenId, SessionId}
import versola.edge.{EdgeConfig, OAuthClientService}
import zio.*

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    Throwable,
    TokenRevocationService,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[RevocationRepository]
        notifications <- ZIO.service[RevocationNotifications]
        clientService <- ZIO.service[OAuthClientService]
        config <- ZIO.service[EdgeConfig]
        service <- make(repository, clientService, config.revocation)
        // Loaded before the layer completes, and allowed to fail it. A replica that cannot
        // read the list does not have a stale answer, it has no answer: an empty list accepts
        // every revoked token presented to it, silently, for as long as the replica is up.
        // Refusing to start is the only fail-closed option available here — it keeps the
        // instance out of the load balancer instead of putting a hole behind it.
        _ <- service.sync
        // Jittered so replicas started together (a rolling deploy, or a host coming back up)
        // don't stay in lockstep and read the table at the same instant for the rest of their
        // lives.
        _ <- service.backgroundSync.repeat(Schedule.spaced(config.revocation.reloadInterval).jittered).forkScoped
        // On its own schedule rather than folded into the one above: reclaiming what has
        // expired is the only thing bounding what this replica holds, and it must not run at
        // the cadence of something that can be slowed down or stopped by the database.
        _ <- service.purge.repeat(Schedule.spaced(config.revocation.purgeInterval)).forkScoped
        _ <- service.consume(notifications).forkScoped
      yield service

  private[revocation] def make(
      repository: RevocationRepository,
      clientService: OAuthClientService,
      config: EdgeConfig.Revocation = EdgeConfig.Revocation(),
  ): UIO[Impl] =
    for
      now <- Clock.instant
      lastSync <- Ref.make(now)
      cursor <- Ref.make(RevocationCursor.Beginning)
    yield Impl(repository, clientService, config, ConcurrentHashMap(), cursor, lastSync)

  /** Holds every revocation that has not expired yet, in memory, and answers from it alone.
    *
    * The request path never reads the database — not on a miss, not when the list is large,
    * not while the replica is catching up. A proxied request is the wrong place to discover
    * that Postgres is slow, and a check that sometimes costs a query is a check whose cost
    * an attacker can choose by presenting tokens that miss.
    *
    * What makes that affordable is that an entry is kept only until the last token it could
    * cover would have expired anyway — one access token TTL, minutes. The list is therefore
    * the revocations of the last few minutes rather than a history, and its size follows the
    * rate they are written at. That rate is not bounded here: an administrator ending a large
    * number of users' access puts all of it in the window at once, so the list is sized to be
    * held rather than capped.
    *
    * Capping it is what is deliberately not done. Every eviction from a deny list is an
    * authorisation decision: dropping a live entry does not degrade the cache, it accepts a
    * revoked token, silently, and it would happen exactly when the list is longest — during
    * the mass revocation. So the map has no maximum and no eviction policy, entries leave
    * only by expiring, and the size is something [[RevocationMetrics.entries]] reports rather
    * than something this enforces.
    *
    * The cost is that it is eventually consistent. A revocation reaches this replica when
    * its notification arrives (typically within a second), and if the feed was down when it
    * was written, when the next reload runs. Between those, this replica still accepts the
    * token. That window is what [[RevocationMetrics.staleness]] measures.
    */
  class Impl(
      repository: RevocationRepository,
      clientService: OAuthClientService,
      config: EdgeConfig.Revocation,
      // Plain and mutable, because every alternative pays for the read path to buy something
      // this has no use for: an immutable map behind a `Ref` rebuilds the whole thing to drop
      // what expired, and retries that rebuild whenever a revocation lands mid-flight — which
      // is likeliest precisely when the map is largest. Here reads allocate nothing and
      // reclamation happens in place, without blocking them.
      entries: ConcurrentHashMap[RevocationKey, Revocation],
      // How far through the durable list this replica has read. Its only job is to keep a
      // catch-up proportional to what has been written since the last one.
      cursor: Ref[RevocationCursor],
      lastSync: Ref[Instant],
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

    /** Writes the revocation and returns — it is not also applied to this replica's own map
      * here. The write's own notification comes back over the same feed as anyone else's, so
      * the replica that revoked a token learns about it exactly like every other one, rather
      * than by a special case that only fires for a write it made itself. That keeps every
      * replica's view explainable by one rule (apply what the feed or a catch-up delivers)
      * instead of two, and the asymmetry a local apply would add — this replica correct
      * sooner than the rest, for tokens revoked through it — was never a guarantee callers
      * were told to rely on: [[RevocationMetrics.staleness]] already prices this path in.
      */
    private def revoke(revocation: Revocation): Task[Unit] =
      repository.revokeAll(List(revocation))

    /** The keys are read one at a time, and nothing holds the map still between them. Nothing
      * needs to: the entries carry no invariant relating one to another, so an answer assembled
      * across a concurrent write is one the same request would have got had it arrived a
      * moment earlier or later.
      */
    override def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean] =
      Clock.instant.map: now =>
        keys.exists(key => Option(entries.get(key)).exists(applies(_, issuedAt, now)))

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
      ZIO.succeed(revocations.foreach(revocation => entries.put(revocation.key, revocation)))

    private[revocation] def entryCount: UIO[Int] =
      ZIO.succeed(entries.size)

    /** Drops entries whose tokens have expired, and reports what is left.
      *
      * Runs on its own schedule and touches no database, because it is the only thing that
      * bounds what this replica holds: tying it to the catch-up would mean a database this
      * replica cannot reach is also a replica that never reclaims anything. An expired entry
      * left here in the meantime changes no answer — [[applies]] rejects it on the way past —
      * so this is reclamation, not correctness.
      *
      * The scan is in place. It walks the whole map, which is the cost of not keeping a
      * second structure ordered by expiry for the sake of a job that runs once a minute.
      */
    private[revocation] def purge: UIO[Unit] =
      for
        now <- Clock.instant
        _ <- ZIO.succeed(entries.values.removeIf(revocation => !revocation.expiresAt.isAfter(now)))
        _ <- RevocationMetrics.entries(entries.size)
      yield ()

    /** Applies revocations written by this or any other replica as they arrive.
      *
      * Reconnects arrive on the same stream, because a gap in the feed is not a no-op here:
      * revocations published while it was down were never delivered and never will be. What
      * closes the gap is an ordinary catch-up — anything written during it sits past the
      * cursor — so a reconnect costs what was missed rather than a rebuild of the whole list.
      */
    private[revocation] def consume(notifications: RevocationNotifications): UIO[Unit] =
      notifications.notifications
        .runForeach:
          case RevocationEvent.Resubscribed => backgroundSync
          case RevocationEvent.Revoked(revocation) => put(List(revocation))
        .catchAllCause(cause => ZIO.logErrorCause("Revocation notification stream failed", cause))

    /** Catches this replica up with the durable list: on startup, after the notification feed
      * reconnects, and periodically in case a notification was lost some other way.
      *
      * Reads forward from where the last one stopped rather than reading the list again, so
      * what a catch-up costs follows what has been written since it — nothing, most times it
      * runs. Only the first one, on a replica whose cursor is still at the beginning, reads
      * the whole list, and it does that a page at a time.
      */
    private[revocation] def sync: Task[Unit] =
      for
        now <- Clock.instant
        highWater <- cursor.get
        reached <- drain(rewound(highWater), highWater)
        _ <- cursor.set(reached)
        _ <- lastSync.set(now)
        _ <- RevocationMetrics.reloaded(entries.size)
      yield ()

    /** A catch-up whose failure is tolerated, which is every one after the first.
      *
      * The difference from [[sync]] is what a failure means, not what it does. At startup
      * there is nothing to fall back to. Here the replica already holds a list, and that list
      * can only be missing revocations written since it was last read — never wrong about the
      * ones it has — so continuing to serve it is strictly better than dying and taking a
      * working deny list down with it. The cursor is left where it was, so the next attempt
      * asks for the same rows rather than stepping over them.
      */
    private[revocation] def backgroundSync: UIO[Unit] =
      sync.catchAllCause: cause =>
        for
          now <- Clock.instant
          previous <- lastSync.get
          _ <- RevocationMetrics.reloadFailed(now.getEpochSecond - previous.getEpochSecond)
          _ <- ZIO.logWarningCause("Failed to refresh the revocation list; serving from the last one loaded", cause)
        yield ()

    /** Reads pages until one comes back short, merging each into what is already held.
      *
      * Merged rather than swapped in: a revocation that arrived by notification while a page
      * was in flight is in neither that page nor the next, and replacing the map would drop
      * it. Merging also makes a page cheap to apply twice, which is what lets the read start
      * behind where the last one finished.
      */
    private def drain(from: RevocationCursor, highWater: RevocationCursor): Task[RevocationCursor] =
      repository.activeSince(from, config.batchSize).flatMap: page =>
        put(page.revocations) *> (page.last match
          case Some(last) if page.hasMore => drain(last, Ordering[RevocationCursor].max(highWater, last))
          case Some(last)                 => ZIO.succeed(Ordering[RevocationCursor].max(highWater, last))
          // Nothing in range, so nothing has been written since the last catch-up and the
          // cursor stays where it was. Moving it to `from` would walk it backwards.
          case None => ZIO.succeed(highWater))

    /** Starts the read a little behind the last row seen. `revoked_at` is stamped when a row
      * is written but the row only appears when its transaction commits, so one that took
      * longer to commit than its neighbours can land behind a cursor that has already passed
      * it, and reading strictly forward would never see it.
      */
    private def rewound(highWater: RevocationCursor): RevocationCursor =
      if highWater == RevocationCursor.Beginning then RevocationCursor.Beginning
      else RevocationCursor(highWater.revokedAt.minusSeconds(config.overlap.toSeconds), "")
