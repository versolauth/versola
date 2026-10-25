package versola.antifraud

import versola.oauth.client.model.TenantId
import zio.*
import zio.metrics.{Metric, MetricLabel}

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

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
  *  1. The winner becomes a rule: deny `deny_list:<value type>` (forces Block), fake `fake_list:<value type>` (forces Fake), allow
  *     `allow_list:<value type>` (weight `-allow-weight`, which outweighs the score of the other rules, so that the step goes
  *     on unless one of them forces an action: allow never overrides a forcing rule of another source, such as a
  *     blocked destination range).
  *  1. **Allow is for exact subjects only** (a destination hash, a device, an address or a /32 or /128 network):
  *     an allow outweighs the whole score, so one on a network, ASN, prefix, mail domain, fingerprint or user agent
  *     would exempt a population from every score-based rule. Such an entry is refused at creation. It never
  *     cancels a forcing rule, nor the SMS budget, which is decided outside the score.
  *  1. `watch` entries are independent of that: any match fires `watch_list:<value type>` with weight `watch-weight`, which an
  *     allow outweighs along with the rest of the score.
  *
  * ==Safety of an entry==
  *  - An entry that names many clients (anything but an exact subject, a network wider than /24 or /48) must have an
  *    `expiresAt`, and so must one whose `source` is not `manual` (an automated entry).
  *  - `shadow` entries are matched and counted but enforce nothing (rule `shadow_list:<value type>`, no weight):
  *    a dry run to read in the fired rules before the entry is made real.
  *  - [[AntifraudLists.preview]] says how many events of the last 24 hours an entry would have matched (for the
  *    values the event log has a column for); `create` refuses an enforcing entry that matches more than
  *    `max-traffic-share` of them.
  *
  * ==Fake==
  * `fake` is invisible to a real person who lands on it (a recycled number, a shared device): they are told the
  * code was sent and nothing arrives. Use it for confirmed fraud only, and have a support path for "why am I not
  * getting a code". The fake send imitates the latency of a real one (`SendLatency`). Hits are counted by list and
  * kind of value in `antifraud_list_hits_total` (never by value).
  *
  * ==Key rotation==
  * A `phone_hash` is made with the key of `security.antifraud-subject-secret`: rotating the key silently stops every
  * such entry from matching. Re-create them after a rotation.
  */
trait AntifraudLists:
  /** The rules the lists contribute; empty while the lists are off. */
  def ruleSet: Antifraud.RuleSet

  /** Adds an entry, or refreshes the one for the same list, value and tenant. Fails with [[InvalidListEntry]]
    * for a value that is not valid for its type. The snapshot of this instance is refreshed at once; the others
    * pick it up within the refresh interval. */
  def create(entry: NewListEntry, actor: String): Task[AntifraudListEntry]

  /** What the entry would have matched of the last 24 hours of events (see [[ListPreview]]). */
  def preview(entry: NewListEntry): Task[ListPreview]

  def delete(id: UUID, actor: String): Task[Boolean]

  def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]]

  def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]]

/** The new entry is not valid: a value that does not fit its type, or one the safety rules refuse. */
final class InvalidListEntry(message: String) extends RuntimeException(message)

object AntifraudLists:

  private val PurgeBatch = 1000

  /** The most entries or audit records one call returns. */
  val MaxLimit = 500

  /** An unchanged table is reloaded after this long anyway. */
  private val FullReloadSeconds = 60L

  /** The [[AntifraudContext.signals]] key a mail domain is read from (`email_domain` entries). */
  val EmailDomainSignal = "email_domain"

  /** @param enabled          off by default: no entry is read and no rule is contributed
    * @param refreshInterval  how often the snapshot is rebuilt from the table, so how long a change takes to reach another instance
    * @param hitFlushInterval how often the hit counters are written
    * @param watchWeight      what a matching `watch` entry adds to the score
    * @param allowWeight      what a matching `allow` entry takes off it; larger than any score the other rules reach
    * @param maxTrafficShare  an enforcing entry that would have matched more than this share of the last 24 hours of events is refused; 0 disables
    * @param shareMinEvents   the share is judged only over at least this many events
    * @param auditRetention   how long the audit records of changes are kept
    * @param startupTimeout   how long starting waits for the first successful load of the lists
    */
  case class Settings(
      enabled: Boolean,
      refreshInterval: Duration,
      hitFlushInterval: Duration,
      watchWeight: Int,
      allowWeight: Int,
      maxTrafficShare: Double,
      shareMinEvents: Long,
      auditRetention: Duration,
      startupTimeout: Duration,
  )

  object Settings:
    val default: Settings = Settings(
      enabled = false,
      refreshInterval = 5.seconds,
      hitFlushInterval = 30.seconds,
      watchWeight = 20,
      allowWeight = 1000,
      maxTrafficShare = 0.05,
      shareMinEvents = 100,
      auditRetention = 365.days,
      startupTimeout = 30.seconds,
    )

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

    /** The version of `af_list_version` the snapshot was built at, and when it was built. */
    val loadedVersion = AtomicLong(-1L)
    val loadedAt = AtomicReference(Instant.MIN)

    def replace(snapshot: ListSnapshot): Unit = current.set(snapshot)

    private def add(a: ListHit, b: ListHit): ListHit =
      ListHit(a.id, a.count + b.count, if a.lastAt.isAfter(b.lastAt) then a.lastAt else b.lastAt)

    private val kinds = ConcurrentHashMap[(AntifraudListType, AntifraudValueType), java.lang.Long]()

    def hit(entry: ListedValue, at: Instant): Unit =
      hits.merge(entry.id, ListHit(entry.id, 1, at), add)
      kinds.merge((entry.listType, entry.valueType), 1L, _ + _)
      ()

    /** Hits by list and kind of value since the last call, for the metric. */
    def drainKinds(): List[((AntifraudListType, AntifraudValueType), Long)] =
      val taken = List.newBuilder[((AntifraudListType, AntifraudValueType), Long)]
      kinds.keySet.forEach(k => Option(kinds.remove(k)).foreach(n => taken += k -> n.longValue))
      taken.result()

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
        if seen != null && (seen.nn._1.eq(ctx)) then seen.nn._2
        else
          val found = snapshot.lookup(ctx, now)
          last.set((ctx, found))
          found

      def decisive(listType: AntifraudListType, valueType: AntifraudValueType)(ctx: AntifraudContext): Boolean =
        matched(ctx).decisive.filter(e => e.listType == listType && e.valueType == valueType).exists: entry =>
          cache.hit(entry, now)
          true

      def shadowed(valueType: AntifraudValueType)(ctx: AntifraudContext): Boolean =
        matched(ctx).shadow.filter(_.valueType == valueType).exists: entry =>
          cache.hit(entry, now)
          true

      def watched(valueType: AntifraudValueType)(ctx: AntifraudContext): Boolean =
        matched(ctx).watch.filter(_.valueType == valueType).exists: entry =>
          cache.hit(entry, now)
          true

      // One rule per list and kind of value, so that the fired rule names in the decision say what matched
      // (`deny_list:ip`); there are few kinds, and the answer of the lookup is shared.
      AntifraudValueType.values.toList.flatMap: valueType =>
        List(
          AntifraudRule(
            name = s"deny_list:$valueType",
            weight = 0,
            forces = Some(DecisionAction.Block),
            test = decisive(AntifraudListType.deny, valueType),
          ),
          AntifraudRule(
            name = s"fake_list:$valueType",
            weight = 0,
            forces = Some(DecisionAction.Fake),
            test = decisive(AntifraudListType.fake, valueType),
          ),
          AntifraudRule(
            name = s"allow_list:$valueType",
            weight = -settings.allowWeight,
            forces = None,
            test = decisive(AntifraudListType.allow, valueType),
          ),
          AntifraudRule(name = s"shadow_list:$valueType", weight = 0, forces = None, test = shadowed(valueType)),
          AntifraudRule(name = s"watch_list:$valueType", weight = settings.watchWeight, forces = None, test = watched(valueType)),
        )

    /** Checks the value and the safety rules, and returns the canonical value. */
    private def validate(entry: NewListEntry, now: Instant): IO[InvalidListEntry, String] =
      for
        value <- ZIO.fromEither(ListValues.normalize(entry.valueType, entry.value)).mapError(InvalidListEntry(_))
        _ <- ZIO.fail(InvalidListEntry("expiresAt is not in the future")).when(entry.expiresAt.exists(!_.isAfter(now)))
        _ <- ZIO
          .fail(InvalidListEntry("an allow is for an exact subject only: a phone_hash, device_id, ip or a /32 or /128 network"))
          .when(entry.listType == AntifraudListType.allow && !ListValues.allowable(entry.valueType, value))
        _ <- ZIO
          .fail(InvalidListEntry("an entry that names many clients, or an automated one, needs expiresAt"))
          .when(entry.expiresAt.isEmpty && (ListValues.broad(entry.valueType, value) || entry.source != "manual"))
      yield value

    override def preview(entry: NewListEntry): Task[ListPreview] =
      for
        now <- Clock.instant
        value <- validate(entry, now)
        result <- repository.preview(entry.valueType, value, now.minusSeconds(24 * 3600))
      yield result

    override def create(entry: NewListEntry, actor: String): Task[AntifraudListEntry] =
      for
        now <- Clock.instant
        value <- validate(entry, now)
        _ <- ZIO.when(!entry.shadow && settings.maxTrafficShare > 0):
          repository.preview(entry.valueType, value, now.minusSeconds(24 * 3600)).flatMap: p =>
            ZIO
              .fail(
                InvalidListEntry(s"it would have matched ${p.matched.get} of ${p.total} events of the last 24 hours: add it as a shadow entry first"),
              )
              .when(p.total >= settings.shareMinEvents && p.matched.exists(_.toDouble / p.total > settings.maxTrafficShare))
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
            shadow = entry.shadow,
            createdBy = actor,
            createdAt = now,
            hitCount = 0,
            lastHitAt = None,
          ),
          actor,
          now,
        )
        _ <- refresh
      yield stored

    override def delete(id: UUID, actor: String): Task[Boolean] =
      Clock.instant.flatMap(repository.delete(id, actor, _)) <* refresh

    override def list(filter: AntifraudListFilter, limit: Int, offset: Int): Task[List[AntifraudListEntry]] =
      page(limit, offset) *> repository.list(filter, limit, offset)

    override def audit(entryId: Option[UUID], limit: Int): Task[List[AntifraudListAudit]] =
      page(limit, 0) *> repository.audit(entryId, limit)

    private def page(limit: Int, offset: Int): IO[InvalidListEntry, Unit] =
      ZIO.fail(InvalidListEntry(s"limit is 1 to $MaxLimit, offset is 0 or more")).unless(limit >= 1 && limit <= MaxLimit && offset >= 0).unit

    /** Rebuilds the snapshot when the table has changed (one tiny query says so), and in any case once a minute, for a
      * change made by hand in SQL. A failure keeps the snapshot there is. */
    private[antifraud] val refresh: UIO[Boolean] =
      Clock.instant.flatMap: now =>
        (for
          version <- repository.version
          due = version != cache.loadedVersion.get || now.isAfter(cache.loadedAt.get.plusSeconds(FullReloadSeconds))
          _ <- ZIO.when(due):
            repository.loadActive(now).flatMap: values =>
              ZIO.succeed(ListSnapshot.build(values)).flatMap: snapshot =>
                ZIO.succeed:
                  cache.replace(snapshot)
                  cache.loadedVersion.set(version)
                  cache.loadedAt.set(now)
                *> ZIO
                  .logWarning(s"${snapshot.skipped} antifraud list entries have a value that is not valid for their type and are ignored")
                  .when(snapshot.skipped > 0)
        yield true).foldCauseZIO(
          cause =>
            AntifraudListMetrics.refreshFailed *> ZIO.logWarningCause(
              "could not refresh the antifraud lists, keeping the previous ones",
              cause,
            ).as(false),
          ZIO.succeed(_),
        )

    private[antifraud] val flushHits: UIO[Unit] =
      ZIO.succeed(cache.drainKinds()).flatMap(kinds => ZIO.foreachDiscard(kinds)((kind, n) => AntifraudListMetrics.hits(kind._1, kind._2, n))) *>
        ZIO.succeed(cache.drain()).flatMap: hits =>
          ZIO.when(hits.nonEmpty):
            repository.addHits(hits).foldCauseZIO(
              cause =>
                ZIO.succeed(cache.restore(hits)) *> AntifraudListMetrics.flushFailed *>
                  ZIO.logWarningCause("could not write the antifraud list hit counters", cause),
              _ => ZIO.unit,
            )
          .unit

    /** Runs `step` in batches of [[PurgeBatch]] with a rest in between, until one comes back short. */
    private def inBatches(step: Instant => Task[Int]): UIO[Unit] =
      def loop: Task[Unit] = Clock.instant.flatMap(step).flatMap(n => if n >= PurgeBatch then ZIO.sleep(100.millis) *> loop else ZIO.unit)
      loop.catchAllCause(ZIO.logWarningCause("could not clean up the antifraud lists", _))

    private val cleanUp: UIO[Unit] =
      inBatches(repository.purgeExpired(_, PurgeBatch)) *>
        inBatches(now => repository.pruneAudit(now.minusSeconds(settings.auditRetention.toSeconds), PurgeBatch))

    /** Waits for the first successful load, up to `startup-timeout`, so that the lists are enforced from the first
      * request (the layer is built before the server listens). If the table cannot be read in time the service
      * starts anyway, with no lists, says so, and retries every second until it succeeds, not at the steady
      * interval. Then it refreshes, writes the counters and removes expired entries in the background; the last
      * flush happens on the way out. */
    private[antifraud] val start: URIO[Scope, Unit] =
      for
        loaded <- refresh.repeat(Schedule.spaced(1.second) *> Schedule.recurWhile[Boolean](!_)).timeout(settings.startupTimeout)
        _ <- ZIO
          .logWarning("the antifraud lists could not be read in time: no list is enforced until a refresh succeeds")
          .when(!loaded.contains(true))
        _ <- (ZIO.sleep(if loaded.contains(true) then settings.refreshInterval else 1.second) *> refresh).forever.forkScoped
        _ <- (ZIO.sleep(settings.hitFlushInterval) *> flushHits *> cleanUp).forever.forkScoped
        _ <- ZIO.addFinalizer(flushHits.timeout(5.seconds))
      yield ()

object AntifraudListMetrics:
  private val refreshFailedTotal = Metric.counter("antifraud_list_refresh_failed_total")
  private val flushFailedTotal = Metric.counter("antifraud_list_hit_flush_failed_total")

  val refreshFailed: UIO[Unit] = refreshFailedTotal.increment
  val flushFailed: UIO[Unit] = flushFailedTotal.increment

  /** By list and kind of value, never by value. */
  def hits(listType: AntifraudListType, valueType: AntifraudValueType, n: Long): UIO[Unit] =
    Metric.counter("antifraud_list_hits_total").tagged(Set(
      MetricLabel("list_type", listType.toString),
      MetricLabel("value_type", valueType.toString),
    )).incrementBy(n)
