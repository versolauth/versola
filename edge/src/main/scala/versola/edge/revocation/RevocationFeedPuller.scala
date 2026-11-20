package versola.edge.revocation

import versola.edge.revocation.RevocationFeedMetrics.Outcome
import versola.edge.{CellTable, EdgeConfig}
import versola.util.revocation.{RevocationConfig, RevocationCursor}
import zio.*
import zio.http.ClientSSLConfig

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
    Scope & EdgeConfig & RevocationCursorRepository & EdgeRevocationRepository & RevocationFeedClient & RevocationFeedClient.Factory & CellTable,
    Throwable,
    Unit,
  ] =
    ZLayer.fromZIO:
      for
        config <- ZIO.service[EdgeConfig]
        _ <-
          if !config.revocation.feed.enabled then ZIO.unit
          else
            for
              cursors <- ZIO.service[RevocationCursorRepository]
              repository <- ZIO.service[EdgeRevocationRepository]
              client <- ZIO.service[RevocationFeedClient]
              factory <- ZIO.service[RevocationFeedClient.Factory]
              cells <- ZIO.service[CellTable]
              // The listener the configured auth's feed is on is named by this block, and its certificate is how
              // auth knows the caller for an edge. Said once, here: the edge carries on with the notifications alone.
              _ <- ZIO.logWarning(
                "revocation.feed is enabled but there is no `native` block naming auth's mutual-TLS listener, which the " +
                  "feed is read from: it is not started, and revocations reach this edge by notification only",
              ).when(config.native.isEmpty)
              suffix <- Random.nextUUID
              feeds <- Feeds.make(
                configured = config.native.map(native => native.authMutualTlsUrl.encode -> client),
                podId = podId(suffix),
                feed = config.revocation.feed,
                cache = config.revocation.cache,
                cursors = cursors,
                factory = factory,
                revocations = repository,
                cells = cells,
              )
              _ <- feeds.run.forkScoped
            yield ()
      yield ()

  /** Every feed this edge reads: the configured auth's and, once cells are registered, each cell's own
    * (versolauth/versola#559). A cell keeps its own list of revocations, so reading only one of them would leave
    * this edge accepting what another cell revoked until the token expired. The set follows the cell table: a cell
    * registered while the edge runs is read from the next poll, from the beginning of its list, and one that was
    * removed is no longer read. Each source has its own cursor row and lease, so the feeds do not wait on each other.
    *
    * A cell without a mutual-TLS listener has no feed to read (the certificate is how auth knows an edge), which is
    * said once for the cell; its revocations then reach this edge by notification only.
    */
  private[revocation] class Feeds(
      configured: Option[(String, RevocationFeedClient)],
      podId: String,
      feed: EdgeConfig.RevocationFeed,
      cache: RevocationConfig,
      cursors: RevocationCursorRepository,
      factory: RevocationFeedClient.Factory,
      revocations: EdgeRevocationRepository,
      cells: CellTable,
      pullers: Ref[Map[Feeds.Key, Impl]],
      warned: Ref[Set[String]],
  ):
    /** Jittered, so pods started together do not tick in step and take the lease in turns by chance of timing. */
    def run: UIO[Nothing] =
      tick.repeat(Schedule.spaced(feed.pollInterval).jittered).forever

    /** One look at every source. Never fails: a source that cannot be seeded is tried again at the next poll. */
    def tick: UIO[Unit] =
      for
        topology <- cells.current
        (served, unserved) = topology.cells.partition(_.mtlsUrl.isDefined)
        _ <- ZIO.foreachDiscard(unserved)(cell => warnOnce(cell.id))
        // The configured auth first: registered as a cell too, it is read once, with the trust of the configuration.
        wanted = (configured.toList.map((source, client) => (Feeds.Key(source, None), () => client)) ++
          served.flatMap(cell => cell.mtlsUrl.map(url => (Feeds.Key(url.encode, cell.trust), () => factory.at(url, cell.trust)))))
          .distinctBy(_._1.source)
        known <- pullers.get
        // Keyed by the pins too, so a cell whose pins were replaced is read with the new ones from the next poll.
        added <- ZIO.foreach(wanted.filterNot((key, _) => known.contains(key))): (key, client) =>
          (cursors.seed(key.source) *> make(key.source, podId, feed, cache, cursors, client(), revocations)).map(key -> _).either
        current = (known ++ added.collect { case Right(entry) => entry }).view.filterKeys(wanted.map(_._1).toSet).toMap
        _ <- pullers.set(current)
        _ <- ZIO.foreachParDiscard(current.values)(_.tick)
        // After the pullers, each of which reported its own: the figure to alert on is the feed furthest behind.
        lags <- ZIO.foreach(current.keys.toList)(key => cursors.lagSeconds(key.source).orElseSucceed(None))
        _ <- ZIO.foreachDiscard(lags.flatten.maxOption)(RevocationFeedMetrics.lagging)
      yield ()

    private def warnOnce(cell: String): UIO[Unit] =
      warned.modify(seen => (!seen.contains(cell), seen + cell)).flatMap: first =>
        ZIO.logWarning(
          s"Cell '$cell' has no mutual-TLS listener, which its revocation feed is read from: its revocations reach this edge by notification only",
        ).when(first).unit

  private[revocation] object Feeds:
    /** A feed and the pins it is read with; `None` for the configured auth, whose pins are its configuration's. */
    final case class Key(source: String, trust: Option[ClientSSLConfig])

    def make(
        configured: Option[(String, RevocationFeedClient)],
        podId: String,
        feed: EdgeConfig.RevocationFeed,
        cache: RevocationConfig,
        cursors: RevocationCursorRepository,
        factory: RevocationFeedClient.Factory,
        revocations: EdgeRevocationRepository,
        cells: CellTable,
    ): UIO[Feeds] =
      for
        pullers <- Ref.make(Map.empty[Key, Impl])
        warned <- Ref.make(Set.empty[String])
      yield Feeds(configured, podId, feed, cache, cursors, factory, revocations, cells, pullers, warned)

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
      lastWarning <- Ref.make(Option.empty[Instant])
    yield Impl(source, podId, feed, cache, cursors, client, revocations, lastFullRead, lastWarning)

  class Impl(
      source: String,
      podId: String,
      feed: EdgeConfig.RevocationFeed,
      cache: RevocationConfig,
      cursors: RevocationCursorRepository,
      client: RevocationFeedClient,
      revocations: EdgeRevocationRepository,
      lastFullRead: Ref[Instant],
      lastWarning: Ref[Option[Instant]],
  ):

    /** One look: take the lease if it is free, run a cycle if it was taken, give it back. Never fails. */
    def tick: UIO[Unit] =
      (cursors.acquire(source, podId, feed.lease).flatMap:
        case None => RevocationFeedMetrics.cycle(Outcome.LeaseNotAcquired)
        case Some(stored) =>
          // Given back however the cycle ends -- including interrupted at shutdown -- so that the next pod does not
          // wait out a lease whose holder is gone. A cycle that read to the end says so, which is what the lag is
          // measured from; any other leaves it where it was.
          ZIO.acquireReleaseExitWith(RevocationFeedMetrics.lease(held = true))((_, exit: Exit[Throwable, Boolean]) =>
            (if exit.exists(identity) then cursors.completed(source, podId) else cursors.release(source, podId)).ignore *>
              RevocationFeedMetrics.lease(held = false),
          )(_ =>
            cycle(stored).tap:
              case true => RevocationFeedMetrics.cycle(Outcome.Ok) *> recovered
              case false => RevocationFeedMetrics.cycle(Outcome.LeaseNotAcquired),
          ).unit
      ).catchAllCause(failed) *> lag

    /** Read from the cursor row, so every pod reports the figure of the pod that reads, not only its own. A failure to
      * read it leaves the gauge as it was: the database being down is reported by everything else. */
    private def lag: UIO[Unit] =
      cursors.lagSeconds(source).flatMap(_.fold(ZIO.unit)(RevocationFeedMetrics.lagging)).ignore

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
        _ <- ZIO.when(completed && full)(lastFullRead.set(started))
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
          // Auth says whether there is another page (an older one does not, and then a page shorter than the limit
          // is the end). One that did not move the position would be read again for ever, so it ends the read too.
          more = page.more.getOrElse(page.revocations.sizeIs >= feed.pageSize) && Ordering[RevocationCursor].gt(next, after)
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
