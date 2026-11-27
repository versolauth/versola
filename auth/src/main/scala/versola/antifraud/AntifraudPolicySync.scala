package versola.antifraud

import versola.oauth.client.CentralSyncTokenService
import versola.util.CoreConfig
import zio.*
import zio.http.{Request, Response}
import zio.json.JsonCodec
import zio.metrics.Metric
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What central has of the antifraud policy: the settings with their version (none at version 0) and the version of the
  * lists. */
case class PolicyState(settingsVersion: Long, settings: Option[Tuning], listsVersion: Long) derives JsonCodec

/** Central's lists at `version`: with `full`, every entry that has not expired; otherwise what changed since the version that
  * was asked for, the entries that are stored and not expired as they are now and the ids of those that are not stored any
  * more. */
case class PolicyLists(version: Long, full: Boolean, entries: List[PolicyListEntry], removed: List[UUID]) derives JsonCodec

/** Where the policy is read from: central's API, with the sync token. */
trait AntifraudPolicySource:
  def state: Task[PolicyState]

  /** The lists: what changed since `since`, the version of the copy, or the whole of them for `None` (and wherever central
    * cannot say what changed, which it says by answering `full`). */
  def lists(since: Option[Long]): Task[PolicyLists]

object AntifraudPolicySource:
  val live: URLayer[CoreConfig & CentralSyncTokenService, AntifraudPolicySource] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(config: CoreConfig, central: CentralSyncTokenService) extends AntifraudPolicySource:
    private val StateUrl = config.central.url / "antifraud" / "policy" / "sync"
    private val ListsUrl = config.central.url / "antifraud" / "policy" / "lists" / "sync"

    private def read[A: JsonCodec](request: Request, send: Request => ZIO[Scope, Throwable, Response]): Task[A] =
      ZIO.scoped:
        send(request).flatMap: response =>
          if response.status.isSuccess then response.body.to[A]
          else ZIO.fail(RuntimeException(s"central answered ${response.status.code} to ${request.url.path.encode}"))

    override def state: Task[PolicyState] = read[PolicyState](Request.get(StateUrl), central.syncRequest)

    // Not through the configuration snapshot: a delta is an answer to a question about the copy's version, and a recorded one
    // replayed to a cell that is at another version would be applied to the wrong copy. What a cell starts from when central
    // is unreachable is its own `af_list_entry`.
    override def lists(since: Option[Long]): Task[PolicyLists] =
      val url = since.fold(ListsUrl)(version => ListsUrl.addQueryParam("since", version.toString))
      read[PolicyLists](Request.get(url), central.liveRequest)

/** Keeps this cell's copy of the antifraud policy what central has (versolauth/versola#547).
  *
  * The settings and the lists are central's, one policy for every cell. Each instance asks central for the two versions
  * every `antifraud.tuning.refresh-interval` on a fiber of its own, never on a request, and where one is not the version of
  * the copy in this cell's database it writes the copy: the settings travel with the versions, the lists are read only
  * then. Several instances that see the same change write it once ([[AntifraudListRepository.replaceAll]]); the others
  * pick it up from the database, as they pick up everything else in it.
  *
  * ==When central cannot be reached==
  * Nothing on a request reads central. A cell that does not reach it goes on with its copy: what is enforced is what was
  * last copied, an entry still expires on this cell's clock, and an instance that starts reads the copy from its own
  * database. What it cannot do is learn of a change, which shows as the age of the last copy
  * (`antifraud_policy_synced_timestamp_seconds`) and as `antifraud_policy_sync_failed_total`.
  */
trait AntifraudPolicySync:
  /** One round: asks central and writes what changed. A failure is counted and logged, and leaves the copy as it is. */
  def sync: UIO[Unit]

object AntifraudPolicySync:

  private val StartupTimeout = 10.seconds

  /** How often the lists are copied whole, once for a cell and not once for each of its instances, in place of the changes
    * since the version of the copy (versolauth/versola#1052). */
  val FullCopyInterval: Duration = 6.hours

  /** No version central can have. */
  private val UnreadableCopy = -1L

  /** Syncs once before the lists and the tuning are first read (so that an instance of a new cell starts with the policy,
    * not with everything off), then on a fiber of its own, which stops with the scope. A start without central goes on with
    * the copy there is. */
  val live: ZLayer[
    AntifraudPolicySource & AntifraudSettingsRepository & AntifraudListRepository & AntifraudTuningStore.Settings & Scope,
    Nothing,
    AntifraudPolicySync,
  ] =
    ZLayer.fromZIO:
      for
        source <- ZIO.service[AntifraudPolicySource]
        settings <- ZIO.service[AntifraudSettingsRepository]
        lists <- ZIO.service[AntifraudListRepository]
        interval <- ZIO.serviceWith[AntifraudTuningStore.Settings](_.refreshInterval)
        sync = Impl(source, settings, lists)
        // Bounded: a central that does not answer must not hold the start, the copy in the database is there for that.
        _ <- sync.sync.timeout(StartupTimeout)
        _ <- (ZIO.sleep(interval) *> sync.sync).forever.forkScoped
      yield sync

  class Impl(source: AntifraudPolicySource, settings: AntifraudSettingsRepository, lists: AntifraudListRepository) extends AntifraudPolicySync:
    /** Whether the last round failed: a central that is down is said once, not every few seconds. */
    private val failing = AtomicBoolean(false)

    override val sync: UIO[Unit] =
      (for
        state <- source.state
        now <- Clock.instant
        // A copy that cannot be read (a row with a key this build does not know) is at no version, so it is written again
        // from what central has; if the database itself is down, the write below fails and the round with it.
        copied <- settings.find.map(_.fold(0L)(_.version)).orElseSucceed(UnreadableCopy)
        _ <- settings.replace(tuning = state.settings, version = state.settingsVersion, at = now).when(copied != state.settingsVersion)
        copy <- lists.copied
        // A whole copy is taken now and then whatever central has changed, so that a copy that drifted for any reason is
        // repaired; one that was never taken (a new cell, or the first round after this was added) is due.
        wholeDue = copy.fullAt.forall(at => !at.plusSeconds(FullCopyInterval.toSeconds).isAfter(now))
        _ <- ZIO.when(copy.version != state.listsVersion || wholeDue):
          // Central answers a delta for a version it still has the changes after, and the whole list for any other.
          source.lists(Option.unless(wholeDue)(copy.version)).flatMap: read =>
            if read.full then
              // The version the entries are stored under is the one they were read at, which may be past the one that was seen.
              lists.replaceAll(
                entries = read.entries,
                version = read.version,
                at = now,
                notBefore = now.minusSeconds(FullCopyInterval.toSeconds),
              )
            else lists.applyChanges(entries = read.entries, removed = read.removed, from = copy.version, to = read.version)
        _ <- synced(now)
        _ <- ZIO.logInfo("the antifraud policy is copied from central again").when(failing.getAndSet(false))
      yield ()).catchAllCause: cause =>
        syncFailedTotal.increment *>
          ZIO
            .logWarningCause("could not copy the antifraud policy from central; this cell goes on with the copy it has", cause)
            .unless(failing.getAndSet(true))
            .unit

  private val syncFailedTotal = Metric.counter("antifraud_policy_sync_failed_total")
  private val syncedGauge = Metric.gauge("antifraud_policy_synced_timestamp_seconds")

  /** Central answered and the copy is what it has, at `at`: a gauge of a time, so that a cell that has lost central, or a
    * round that hangs, shows as the age of the last success. */
  private def synced(at: Instant): UIO[Unit] = syncedGauge.set(at.getEpochSecond.toDouble)
