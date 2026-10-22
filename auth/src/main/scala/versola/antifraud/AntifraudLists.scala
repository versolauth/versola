package versola.antifraud

import zio.*
import zio.metrics.Metric

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The lists (`af_list_entry`) as the rules see them, and the internal API that changes them.
  *
  * The entries are read in bulk every [[AntifraudLists.Settings.refreshInterval]] into an immutable [[ListSnapshot]]
  * that the rules look values up in: no database is read on a request. A refresh that fails keeps the snapshot
  * there is (fail-open: lists that cannot be read stop being applied, they never stop a sign-in), and the counters of
  * how often an entry matched are kept in memory and written in batches by a background fiber.
  *
  * ==Precedence==
  * Per request, over the entries that apply (global ones, and those of the request's tenant) and have not expired
  * at the time of the request:
  *  1. `deny`, `fake` and `allow` entries compete; the most specific value decides (see [[ListSnapshot.Indexed]]
  *     for the order: an exact address, destination or device beats a network, a network beats a number prefix, and
  *     so on, a longer network or prefix beats a shorter one). On a tie `deny` beats `fake` beats `allow`: an allow
  *     is never stronger than a deny of the same specificity. So an allowed address inside a denied network is
  *     allowed, and a denied address inside an allowed network is denied.
  *  1. The winner becomes a rule: deny `list_deny` (forces Block), fake `list_fake` (forces Fake), allow
  *     `list_allow` (weight `-allow-weight`, which outweighs the score of the other rules, so that the step goes
  *     on unless one of them forces an action: allow never overrides a forcing rule of another source, such as a
  *     blocked destination range).
  *  1. `watch` entries are independent of that: any match fires `list_watch` with weight `watch-weight`, which an
  *     allow outweighs along with the rest of the score.
  */
trait AntifraudLists:
  /** The rules the lists contribute; empty while the lists are off. */
  def ruleSet: Antifraud.RuleSet

  /** Adds an entry, or refreshes the one for the same list, value and tenant. Fails with [[InvalidListEntry]]
    * for a value that is not valid for its type. The snapshot of this instance is refreshed at once; the others
    * pick it up within the refresh interval. */
  def create(entry: NewListEntry, actor: String): Task[AntifraudListEntry]

  def delete(id: UUID, actor: String): Task[Boolean]

  def list(filter: AntifraudListFilter = AntifraudListFilter(), limit: Int = 100, offset: Int = 0): Task[List[AntifraudListEntry]]

  def audit(entryId: Option[UUID] = None, limit: Int = 100): Task[List[AntifraudListAudit]]

/** The value of a new entry is not valid for its type. */
final class InvalidListEntry(message: String) extends RuntimeException(message)

object AntifraudLists:

  /** The [[AntifraudContext.signals]] key a mail domain is read from (`email_domain` entries). */
  val EmailDomainSignal = "email_domain"

  /** @param enabled          off by default: no entry is read and no rule is contributed
    * @param refreshInterval  how often the snapshot is rebuilt from the table, so how long a change takes to reach another instance
    * @param hitFlushInterval how often the hit counters are written
    * @param watchWeight      what a matching `watch` entry adds to the score
    * @param allowWeight      what a matching `allow` entry takes off it; larger than any score the other rules reach
    */
  case class Settings(
      enabled: Boolean = false,
      refreshInterval: Duration = 5.seconds,
      hitFlushInterval: Duration = 30.seconds,
      watchWeight: Int = 20,
      allowWeight: Int = 1000,
  )

  object Settings:
    val default: Settings = Settings()

  val live: ZLayer[AntifraudListRepository & Settings & Scope, Nothing, AntifraudLists] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AntifraudListRepository]
        settings <- ZIO.service[Settings]
        cache = ListCache()
        lists = Impl(repository, settings, cache)
        _ <- ZIO.when(settings.enabled)(lists.start)
      yield lists

  /** The snapshot, and the hits not yet written. */
  final class ListCache:
    private val current = AtomicReference(ListSnapshot.empty)
    private val hits = ConcurrentHashMap[UUID, ListHit]()

    def snapshot: ListSnapshot = current.get

    def replace(snapshot: ListSnapshot): Unit = current.set(snapshot)

    private def add(a: ListHit, b: ListHit): ListHit =
      ListHit(a.id, a.count + b.count, if a.lastAt.isAfter(b.lastAt) then a.lastAt else b.lastAt)

    def hit(id: UUID, at: Instant): Unit =
      hits.merge(id, ListHit(id, 1, at), add)
      ()

    /** Takes the hits counted so far. Nothing that is counted while this runs is lost: it is taken by the next one. */
    def drain(): List[ListHit] =
      val taken = List.newBuilder[ListHit]
      hits.keySet.forEach(id => Option(hits.remove(id)).foreach(taken += _))
      taken.result()

    /** Gives back hits that could not be written. */
    def restore(failed: List[ListHit]): Unit =
      failed.foreach(h => hits.merge(h.id, h, add))

  class Impl(repository: AntifraudListRepository, settings: Settings, cache: ListCache) extends AntifraudLists:

    override val ruleSet: Antifraud.RuleSet = new Antifraud.RuleSet:
      override def rules: UIO[List[AntifraudRule]] =
        if !settings.enabled then ZIO.succeed(Nil)
        else
          Clock.instant.map: now =>
            val snapshot = cache.snapshot
            if snapshot.isEmpty then Nil
            else listRules(snapshot, now)

    /** The four rules share one lookup per request: the rules of one evaluation are tested one after the other
      * with the same context, so the last answer is reused. The hit counters are the only thing a test touches. */
    private def listRules(snapshot: ListSnapshot, now: Instant): List[AntifraudRule] =
      val last = AtomicReference[(AntifraudContext, ListMatch) | Null](null)

      def matched(ctx: AntifraudContext): ListMatch =
        val seen = last.get
        if seen != null && (seen.nn._1 eq ctx) then seen.nn._2
        else
          val found = snapshot.lookup(ctx, now)
          last.set((ctx, found))
          found

      def decisive(listType: AntifraudListType)(ctx: AntifraudContext): Boolean =
        matched(ctx).decisive.filter(_.listType == listType).exists: entry =>
          cache.hit(entry.id, now)
          true

      List(
        AntifraudRule("list_deny", 0, Some(DecisionAction.Block), decisive(AntifraudListType.deny)),
        AntifraudRule("list_fake", 0, Some(DecisionAction.Fake), decisive(AntifraudListType.fake)),
        AntifraudRule("list_allow", -settings.allowWeight, None, decisive(AntifraudListType.allow)),
        AntifraudRule("list_watch", settings.watchWeight, None, ctx =>
          matched(ctx).watch.exists: entry =>
            cache.hit(entry.id, now)
            true,
        ),
      )

    override def create(entry: NewListEntry, actor: String): Task[AntifraudListEntry] =
      for
        value <- ZIO.fromEither(ListValues.normalize(entry.valueType, entry.value)).mapError(InvalidListEntry(_))
        now <- Clock.instant
        _ <- ZIO.fail(InvalidListEntry("expiresAt is not in the future")).when(entry.expiresAt.exists(!_.isAfter(now)))
        stored <- repository.upsert(
          AntifraudListEntry(
            id = UUID.randomUUID(),
            listType = entry.listType,
            valueType = entry.valueType,
            value = value,
            tenantId = entry.tenantId,
            expiresAt = entry.expiresAt,
            reason = entry.reason,
            source = entry.source,
            createdBy = actor,
            createdAt = now,
          ),
          actor,
          now,
        )
        _ <- refresh
      yield stored

    override def delete(id: UUID, actor: String): Task[Boolean] =
      Clock.instant.flatMap(repository.delete(id, actor, _)) <* refresh

    override def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]] =
      repository.list(filter, limit, offset)

    override def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]] =
      repository.audit(entryId, limit)

    /** Rebuilds the snapshot. A failure keeps the one there is. */
    private[antifraud] val refresh: UIO[Unit] =
      Clock.instant.flatMap: now =>
        repository.loadActive(now).foldCauseZIO(
          cause => AntifraudListMetrics.refreshFailed *> ZIO.logWarningCause("could not refresh the antifraud lists, keeping the previous ones", cause),
          values =>
            ZIO.succeed(ListSnapshot.build(values)).flatMap: snapshot =>
              ZIO.succeed(cache.replace(snapshot)) *>
                ZIO.logWarning(s"${snapshot.skipped} antifraud list entries have a value that is not valid for their type and are ignored")
                  .when(snapshot.skipped > 0).unit
        )

    private[antifraud] val flushHits: UIO[Unit] =
      ZIO.succeed(cache.drain()).flatMap: hits =>
        ZIO.when(hits.nonEmpty):
          repository.addHits(hits).foldCauseZIO(
            cause =>
              ZIO.succeed(cache.restore(hits)) *> AntifraudListMetrics.flushFailed *>
                ZIO.logWarningCause("could not write the antifraud list hit counters", cause),
            _ => ZIO.unit,
          )
        .unit

    private val purgeExpired: UIO[Unit] =
      Clock.instant.flatMap(repository.purgeExpired).unit.catchAllCause(ZIO.logWarningCause("could not remove expired antifraud list entries", _))

    /** Loads once so that the lists apply from the first request, then refreshes, writes the counters and removes
      * expired entries in the background; the last flush happens on the way out. */
    private[antifraud] val start: URIO[Scope, Unit] =
      for
        _ <- refresh
        _ <- (ZIO.sleep(settings.refreshInterval) *> refresh).forever.forkScoped
        _ <- (ZIO.sleep(settings.hitFlushInterval) *> flushHits *> purgeExpired).forever.forkScoped
        _ <- ZIO.addFinalizer(flushHits.timeout(5.seconds))
      yield ()

object AntifraudListMetrics:
  private val refreshFailedTotal = Metric.counter("antifraud_list_refresh_failed_total")
  private val flushFailedTotal = Metric.counter("antifraud_list_hit_flush_failed_total")

  val refreshFailed: UIO[Unit] = refreshFailedTotal.increment
  val flushFailed: UIO[Unit] = flushFailedTotal.increment
