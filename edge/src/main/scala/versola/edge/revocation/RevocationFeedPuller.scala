package versola.edge.revocation

import versola.edge.EdgeConfig
import versola.edge.revocation.RevocationFeedMetrics.Outcome
import versola.util.revocation.{RevocationConfig, RevocationCursor}
import zio.*

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Reads auth's revocations on a short poll, so that a revocation reaches this edge even when the notification sent
  * to `back_channel_logout_uri` was lost, was never sent (a client with no such address), or arrived while this edge
  * was down (#560).
  *
  * Every pod of the edge ticks, and the one that finds the lease on the source's cursor row free runs one cycle
  * (see [[RevocationCursorRepository]]): there is no leader to elect and no lock held across the call to auth. A pod
  * that dies hands the right on when its lease runs out; one that hangs keeps it for one lease.
  *
  * A cycle reads pages from auth, outside any database transaction, until one comes back short; each page is
  * written to this edge's `revocations` table, whose trigger notifies every pod, so the others learn without
  * reading anything. The stored cursor moves only after the page it stands for is written, so a failed cycle is
  * simply read again from where the last one ended.
  *
  * What bounds the time a revocation can be missed:
  *  - A cycle starts a little behind the stored cursor (`revocation.overlap`): a row is stamped when written and
  *    visible when its transaction commits, so one that committed late sits behind the cursor.
  *  - A row that committed later than that is found by the full read every `revocation.reload-interval`, from the
  *    beginning of the list, which costs minutes instead of the token's lifetime. Rereading is harmless: the write
  *    widens an entry and never narrows one.
  *
  * Nothing here can make a request fail: the list is answered from memory, and a feed that cannot be reached leaves
  * this edge on the notifications it has, as it was before the feed existed.
  */
object RevocationFeedPuller:

  /** How often a repeated failure is logged: the first, then at most one in this long, so a feed that stays down
    * is a line every few minutes and not one a second. */
  private[revocation] val WarnEvery: Duration = 5.minutes

  val live: ZLayer[
    Scope & EdgeConfig & RevocationCursorRepository & EdgeRevocationRepository & RevocationFeedClient,
    Throwable,
    Unit,
  ] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[EdgeConfig]
        _ <-
          if !config.revocation.feed.enabled then ZIO.unit
          else
            config.native match
              // The listener the feed is on is named by this block, and its certificate is how auth knows the
              // caller for an edge. Said once, here: the edge carries on with the notifications alone.
              case None =>
                ZIO.logWarning(
                  "revocation.feed is enabled but there is no `native` block naming auth's mutual-TLS listener, which the " +
                    "feed is read from: it is not started, and revocations reach this edge by notification only",
                )
              case Some(native) =>
                for
                  cursors <- ZIO.service[RevocationCursorRepository]
                  repository <- ZIO.service[EdgeRevocationRepository]
                  client <- ZIO.service[RevocationFeedClient]
                  source = native.authMutualTlsUrl.encode
                  _ <- cursors.seed(source)
                  suffix <- Random.nextUUID
                  puller <- make(source, podId(suffix), config.revocation.feed, config.revocation.cache, cursors, client, repository)
                  _ <- puller.run.forkScoped
                yield ()
      yield ()

  private def podId(suffix: UUID): String =
    s"${Try(InetAddress.getLocalHost.getHostName).getOrElse("edge")}-${suffix.toString.take(8)}"

  private[revocation] def make(
      source: String,
      podId: String,
      feed: EdgeConfig.RevocationFeed,
      cache: RevocationConfig,
      cursors: RevocationCursorRepository,
      client: RevocationFeedClient,
      revocations: EdgeRevocationRepository,
  ): UIO[Impl] =
    for
      now <- Clock.instant
      // A pod starts with its list as the database holds it, loaded by RevocationCache, so its first full read
      // is one `reload-interval` away; a brand new edge reads the whole list anyway, from the empty cursor.
      lastFullRead <- Ref.make(now)
      lastSuccess <- Ref.make(now)
      lastWarning <- Ref.make(Option.empty[Instant])
    yield Impl(source, podId, feed, cache, cursors, client, revocations, lastFullRead, lastSuccess, lastWarning)

  class Impl(
      source: String,
      podId: String,
      feed: EdgeConfig.RevocationFeed,
      cache: RevocationConfig,
      cursors: RevocationCursorRepository,
      client: RevocationFeedClient,
      revocations: EdgeRevocationRepository,
      lastFullRead: Ref[Instant],
      lastSuccess: Ref[Instant],
      lastWarning: Ref[Option[Instant]],
  ):

    /** Jittered, so pods started together do not tick in step and take the lease in turns by chance of timing. */
    def run: UIO[Nothing] =
      tick.repeat(Schedule.spaced(feed.pollInterval).jittered).forever

    /** One look: take the lease if it is free, run a cycle if it was taken, give it back. Never fails. */
    def tick: UIO[Unit] =
      (cursors.acquire(source, podId, feed.lease).flatMap:
        case None => RevocationFeedMetrics.cycle(Outcome.LeaseNotAcquired)
        case Some(stored) =>
          // Released however the cycle ends -- including interrupted at shutdown -- so that the next pod does not
          // wait out a lease whose holder is gone.
          ZIO.acquireReleaseWith(RevocationFeedMetrics.lease(held = true))(_ =>
            cursors.release(source, podId).ignore *> RevocationFeedMetrics.lease(held = false),
          )(_ =>
            cycle(stored).flatMap:
              case true => RevocationFeedMetrics.cycle(Outcome.Ok) *> recovered
              case false => RevocationFeedMetrics.cycle(Outcome.LeaseNotAcquired),
          )
      ).catchAllCause(failed) *> staleness

    private def staleness: UIO[Unit] =
      for
        now <- Clock.instant
        last <- lastSuccess.get
        _ <- RevocationFeedMetrics.lagging(math.max(0L, now.getEpochSecond - last.getEpochSecond))
      yield ()

    /** `false` when the lease was lost half-way (it ran out and another pod took it): this pod stops, having
      * written what it read, and leaves the cursor to the pod that holds the lease now. */
    private def cycle(stored: String): Task[Boolean] =
      for
        started <- Clock.instant
        lastFull <- lastFullRead.get
        full = !started.isBefore(lastFull.plus(cache.reloadInterval))
        // A cursor this version cannot read (a row written by some other format) is read from the beginning:
        // the worst case is a longer first read, never a gap.
        known = RevocationCursor.decode(stored).getOrElse(RevocationCursor.Beginning)
        from = if full then RevocationCursor.Beginning else known.rewound(cache.overlap)
        completed <- drain(from, highWater = known)
        _ <- ZIO.when(completed)(lastSuccess.set(started) *> ZIO.when(full)(lastFullRead.set(started)))
      yield completed

    /** @param highWater how far the stored cursor already is. A page moves it only when it ends beyond it: a read
      *                  that starts behind it (the overlap, or the full read) passes rows already stored, and
      *                  moving the cursor back to them would only make the next cycle read them again. */
    private def drain(after: RevocationCursor, highWater: RevocationCursor): Task[Boolean] =
      client.fetch(after.encoded, feed.pageSize).flatMap: page =>
        for
          _ <- revocations.revokeAll(page.revocations)
          _ <- RevocationFeedMetrics.page(page.revocations.size)
          next <- ZIO.fromOption(RevocationCursor.decode(page.cursor))
            .orElseFail(IllegalStateException("auth sent a cursor this edge cannot read"))
          // After the write above, never before: a cursor ahead of what is stored would step over rows.
          holding <-
            if Ordering[RevocationCursor].gt(next, highWater) then cursors.advance(source, podId, page.cursor, feed.lease)
            else cursors.renew(source, podId, feed.lease)
          // A short page is the end of what there is now. A full one that did not move the position would be read
          // again for ever, so it ends the read too.
          more = page.revocations.sizeIs >= feed.pageSize && Ordering[RevocationCursor].gt(next, after)
          result <-
            if !holding then ZIO.succeed(false)
            else if more then drain(next, Ordering[RevocationCursor].max(highWater, next))
            else ZIO.succeed(true)
        yield result

    /** The first failure is logged with its cause and then at most one in [[WarnEvery]]; the feed down for an hour
      * is a dozen lines, not three thousand. The metric counts every one. */
    private def failed(cause: Cause[Throwable]): UIO[Unit] =
      for
        _ <- RevocationFeedMetrics.cycle(Outcome.Error)
        now <- Clock.instant
        warn <- lastWarning.modify: last =>
          val due = last.forall(at => !now.isBefore(at.plus(WarnEvery)))
          (due, if due then Some(now) else last)
        _ <- ZIO.logWarningCause(
          "Could not pull revocations from auth; this edge keeps the list it has and still takes notifications, and tries again",
          cause,
        ).when(warn)
      yield ()

    /** One line when a feed that was failing works again, so the warning above has an end. */
    private def recovered: UIO[Unit] =
      lastWarning.getAndSet(None).flatMap(previous => ZIO.logInfo("Pulling revocations from auth works again").when(previous.isDefined).unit)
