package versola.antifraud

import versola.oauth.client.CentralSyncTokenService
import versola.util.CoreConfig
import zio.*
import zio.http.Request
import zio.json.JsonCodec
import zio.metrics.Metric
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** What central has of the antifraud policy: the settings with their version (none at version 0) and the version of the
  * lists. */
case class PolicyState(settingsVersion: Long, settings: Option[Tuning], listsVersion: Long) derives JsonCodec

/** Central's lists: every entry that has not expired, and the version they were read at. */
case class PolicyLists(version: Long, entries: List[PolicyListEntry]) derives JsonCodec

/** Where the policy is read from: central's API, with the sync token. */
trait AntifraudPolicySource:
  def state: Task[PolicyState]

  def lists: Task[PolicyLists]

object AntifraudPolicySource:
  val live: URLayer[CoreConfig & CentralSyncTokenService, AntifraudPolicySource] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(config: CoreConfig, central: CentralSyncTokenService) extends AntifraudPolicySource:
    private val StateUrl = config.central.url / "antifraud" / "policy" / "sync"
    private val ListsUrl = config.central.url / "antifraud" / "policy" / "lists" / "sync"

    private def read[A: JsonCodec](request: Request): Task[A] =
      ZIO.scoped:
        central.syncRequest(request).flatMap: response =>
          if response.status.isSuccess then response.body.to[A]
          else ZIO.fail(RuntimeException(s"central answered ${response.status.code} to ${request.url.path.encode}"))

    override def state: Task[PolicyState] = read[PolicyState](Request.get(StateUrl))

    override def lists: Task[PolicyLists] = read[PolicyLists](Request.get(ListsUrl))

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
        listed <- lists.version
        _ <- ZIO.when(listed != state.listsVersion):
          // The version the entries are stored under is the one they were read at, which may be past the one that was seen.
          source.lists.flatMap(read => lists.replaceAll(entries = read.entries, version = read.version))
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
