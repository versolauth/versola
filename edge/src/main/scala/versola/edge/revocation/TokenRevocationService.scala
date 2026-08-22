package versola.edge.revocation

import com.github.benmanes.caffeine.cache.{Caffeine, Cache as CaffeineCache, RemovalCause}
import versola.edge.{EdgeConfig, EdgeSettingsSyncClient}
import zio.*

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.jdk.CollectionConverters.SetHasAsScala

trait TokenRevocationService:
  /** Records revocations so that every replica of this edge starts rejecting the tokens
    * they name, and keeps rejecting them until they would have expired anyway.
    */
  def revoke(revocations: List[Revocation]): Task[Unit]

  /** Whether any of these keys revokes a token issued at `issuedAt`. Answered from memory
    * in the common case; see [[TokenRevocationService.Impl]] for when and why it falls back
    * to the database.
    */
  def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean]

object TokenRevocationService:
  /** Used until central answers with the size it holds for this edge. Central's column
    * carries the same default, so the two agree for an edge nobody has tuned.
    */
  private val DefaultCacheSize = 10000

  def live: ZLayer[
    RevocationRepository & RevocationNotifications & EdgeSettingsSyncClient & EdgeConfig & Scope,
    Nothing,
    TokenRevocationService,
  ] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[RevocationRepository]
        notifications <- ZIO.service[RevocationNotifications]
        settingsClient <- ZIO.service[EdgeSettingsSyncClient]
        config <- ZIO.service[EdgeConfig]
        // A cache size is a tuning knob, so an unreachable central delays applying it
        // rather than keeping this edge from starting. The poll below picks it up.
        cacheSize <- settingsClient.get.map(_.revocationCacheSize).catchAllCause: cause =>
          ZIO.logWarningCause(s"Couldn't read this edge's settings; using a cache of $DefaultCacheSize until central answers", cause)
            .as(DefaultCacheSize)
        service = Impl(repository, cacheSize)
        // Loaded before the layer completes, so the first request is served from a warm
        // cache instead of falling back to the database for every miss.
        _ <- service.reload
        // Jittered so replicas started together (a rolling deploy, or a host coming back up)
        // don't stay in lockstep and scan the table at the same instant for the rest of their
        // lives.
        _ <- service.reload.repeat(Schedule.spaced(config.revocation.reloadInterval).jittered).forkScoped
        _ <- service.consume(notifications).forkScoped
        _ <- service.applySettings(settingsClient)
          .repeat(Schedule.spaced(config.configurationCacheRefreshInterval))
          .forkScoped
      yield service

  /** Keeps the revocation list in a size-bounded in-memory cache, so the check on the hot
    * path costs a hash lookup and no I/O.
    *
    * A bounded cache can drop an entry that still matters, which would silently let a
    * revoked token through — the one failure mode this must not have. Hence `complete`:
    * it is true only while the cache is known to hold every active revocation, and any
    * eviction clears it. While it is false, a miss is re-checked against the database
    * instead of being trusted, and a periodic reload restores it once enough entries have
    * expired for the whole list to fit again.
    */
  class Impl(repository: RevocationRepository, initialMaxSize: Int) extends TokenRevocationService:
    private val complete = AtomicBoolean(false)
    private val maxSize = AtomicInteger(initialMaxSize)

    private val cache: CaffeineCache[RevocationKey, Revocation] =
      Caffeine
        .newBuilder()
        .maximumSize(initialMaxSize.toLong)
        // Caffeine's maintenance (including eviction) otherwise runs on a pool thread, which
        // would leave a window where the cache has overflowed but still claims to be complete.
        // On the calling thread it is done by the time the write that caused it returns.
        .executor((task: Runnable) => task.run())
        // Eviction listeners (unlike removal listeners) run synchronously, as part of the
        // eviction itself: `complete` is already false by the time the entry is gone, so
        // there is no window in which a lookup trusts a cache that has lost an entry.
        .evictionListener((_: RevocationKey, _: Revocation, _: RemovalCause) => complete.set(false))
        .build[RevocationKey, Revocation]()

    override def revoke(revocations: List[Revocation]): Task[Unit] =
      repository.revokeAll(revocations) *>
        // The notification this write triggers comes back to this replica too, but not
        // before the caller is answered: the client that asked for the revocation would
        // otherwise be able to use the token again on the very next request.
        ZIO.succeed(revocations.foreach(revocation => cache.put(revocation.key, revocation)))

    override def isRevoked(keys: List[RevocationKey], issuedAt: Instant): UIO[Boolean] =
      Clock.instant.flatMap(now => ZIO.exists(keys)(isRevoked(_, issuedAt, now)))

    private def isRevoked(key: RevocationKey, issuedAt: Instant, now: Instant): UIO[Boolean] =
      Option(cache.getIfPresent(key)) match
        case Some(revocation) =>
          ZIO.succeed(applies(revocation, issuedAt, now))
        case None if complete.get() =>
          ZIO.succeed(false)
        case None =>
          // The cache is known to be missing entries, so a miss proves nothing.
          // A database that cannot answer leaves us unable to tell a revoked token from a
          // live one; rejecting is the only safe reading of that.
          repository
            .find(key)
            .map(_.exists(applies(_, issuedAt, now)))
            .catchAllCause: cause =>
              ZIO.logWarningCause("Revocation lookup failed while the cache is incomplete; rejecting the token", cause)
                .as(true)

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

    /** Applies revocations written by this or any other replica as they arrive.
      *
      * Reconnects arrive on the same stream, because a gap in the feed is not a no-op here:
      * revocations published while it was down were never delivered and never will be, so the
      * cache is rebuilt from the table before it is trusted again.
      */
    private[revocation] def consume(notifications: RevocationNotifications): UIO[Unit] =
      notifications.notifications
        .runForeach:
          case RevocationEvent.Resubscribed =>
            reload
          case RevocationEvent.Revoked(revocation) =>
            ZIO.succeed(cache.put(revocation.key, revocation))
        .catchAllCause(cause => ZIO.logErrorCause("Revocation notification stream failed", cause))

    /** Applies the size central holds for this edge. Resizing evicts on its own when the
      * new bound is smaller; growing needs the reload to refill what an earlier, tighter
      * bound had dropped, which is also what makes the cache complete again.
      */
    private[revocation] def applySettings(settingsClient: EdgeSettingsSyncClient): UIO[Unit] =
      settingsClient.get
        .flatMap: settings =>
          ZIO.unless(settings.revocationCacheSize == maxSize.get()):
            ZIO.succeed:
              maxSize.set(settings.revocationCacheSize)
              cache.policy().eviction().get().setMaximum(settings.revocationCacheSize.toLong)
            *> reload
        .unit
        .catchAllCause(cause => ZIO.logWarningCause("Couldn't read this edge's settings; keeping the current cache size", cause))

    /** Rebuilds the cache from the database, dropping entries that have expired and
      * restoring `complete` when everything still active fits.
      */
    private[revocation] def reload: UIO[Unit] =
      (for
        now <- Clock.instant
        limit = maxSize.get()
        // One more than fits, so a full cache can be told apart from an overflowing one.
        active <- repository.listActive(limit + 1)
        _ <- ZIO.succeed:
          // Only expired entries are dropped, never live ones: a notification racing this
          // reload must not be lost, which invalidating the whole cache would allow.
          cache.asMap().entrySet().asScala.filterInPlace(entry => entry.getValue.expiresAt.isAfter(now))
          active.take(limit).foreach(revocation => cache.put(revocation.key, revocation))
        _ <- ZIO.succeed(complete.set(active.sizeIs <= limit))
        _ <- RevocationMetrics.cacheState(complete = active.sizeIs <= limit, entries = cache.estimatedSize())
      yield ()).catchAllCause: cause =>
        ZIO.succeed(complete.set(false)) *>
          RevocationMetrics.cacheState(complete = false, entries = cache.estimatedSize()) *>
          ZIO.logWarningCause("Failed to reload the revocation list; falling back to per-miss lookups", cause)
