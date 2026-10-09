package versola.util

import zio.*
import zio.metrics.Metric

import java.time.Instant

type ReloadingCache[A] = ReloadingCache.Type[A]

object ReloadingCache:
  opaque type Type[A] <: zio.Ref[A] = zio.Ref[A]

  inline def apply[A](ref: zio.Ref[A]): ReloadingCache[A] = ref

  def constant[A](values: Set[A]): ReloadingCache[Set[A]] =
    ReloadingCache(Unsafe.unsafe(Ref.unsafe.make(values)(using _)))

  /** How long a source that is merely *not up yet* is waited for.
    *
    * A cache whose source is another service (edge and auth both sync their configuration from
    * central during their own startup) fails its first load whenever it wins the race against that
    * service. Without a retry the failure propagates out of layer construction and takes the whole
    * application down - observed in production, with central becoming ready ~0.4s after auth had
    * already given up (see deploy.md 9.5). Waiting is strictly better than dying and relying on
    * the orchestrator to restart us.
    *
    * Two minutes covers a cold Kubernetes install, where every service is scheduled at once and
    * central still has a JVM, a connection pool and a schema validation ahead of it before it
    * binds a port (#378). Still bounded: a hostname that will never resolve fails startup loudly
    * rather than leaving a pod Running, never Ready, with nothing saying why.
    */
  private val DependencyWait: Duration = 2.minutes

  /** Ceiling on a single backoff step while waiting for a source to come up. Without it the
    * exponential reaches minutes, and a source that becomes reachable early in a long sleep is
    * not noticed until that sleep ends - the whole wait paid even though the waiting was over.
    */
  private val MaxRetryDelay: Duration = 5.seconds

  /** Whether a failed load means "the source is not up yet" rather than "the source is wrong".
    *
    * Only the transport is consulted, never the response: everything here is a failure to reach
    * the source at all, which is what a service that has not bound its port yet, a Service with
    * no ready endpoints, or a database accepting no connections produces. A request that was
    * answered and rejected - bad credentials, a 404 from a mistyped path - is a different class
    * and keeps the short bounded retry below, because waiting two minutes cannot fix it.
    *
    * Walks the cause chain: a connect failure arrives wrapped by netty (its
    * `AnnotatedConnectException`) or by the pool, rarely bare.
    */
  private def notUpYet(error: Throwable): Boolean =
    Iterator
      .iterate(Option(error))(_.flatMap(t => Option(t.getCause).filterNot(_ eq t)))
      .take(16)
      .takeWhile(_.isDefined)
      .flatten
      .exists {
        case _: java.net.ConnectException | _: java.net.UnknownHostException |
            _: java.net.NoRouteToHostException | _: java.net.SocketTimeoutException |
            _: java.nio.channels.ClosedChannelException | _: java.util.concurrent.TimeoutException =>
          true
        // SQL state class 08 is the standard's "connection exception" - Postgres answers 08001
        // for a refused connection - and Hikari raises SQLTransientConnectionException when the
        // pool cannot hand a connection out in time.
        case sql: java.sql.SQLException =>
          sql.isInstanceOf[java.sql.SQLTransientConnectionException] ||
          Option(sql.getSQLState).exists(_.startsWith("08"))
        case _ => false
      }

  /** Retries the initial load, on two schedules at once.
    *
    * The bounded one is the original: a handful of attempts for a failure this cannot classify,
    * so an unrecognised blip is still absorbed while everything else fails startup fast and
    * loudly. The other waits out a source that is provably just not up yet, for as long as
    * [[DependencyWait]].
    *
    * `||` recurs while *either* still wants to, at whichever delay comes first - so an
    * unclassifiable failure behaves exactly as it did before this was split in two, and only an
    * unreachable source gets the long wait.
    *
    * Neither runs for [[ConfigSnapshot.SourceDown]]: another cache has already waited for the
    * source and started from the snapshot, and caches are built one after another, so waiting
    * again would multiply the start time by their number.
    */
  private val initialLoadRetry: Schedule[Any, Throwable, Any] =
    ((Schedule.exponential(500.millis, 2.0).jittered && Schedule.recurs(6)) ||
      Schedule.exponential(500.millis, 2.0).jittered
        .modifyDelay((_, delay) => delay.min(MaxRetryDelay))
        .whileInput[Throwable](notUpYet)
        .upTo(DependencyWait)) &&
      Schedule.recurWhile[Throwable](!_.isInstanceOf[ConfigSnapshot.SourceDown])

  /** How often a source is tried again while the cache is served from the snapshot. */
  private val SnapshotRetry: Schedule[Any, Any, Any] =
    Schedule.exponential(500.millis, 2.0).jittered.modifyDelay((_, delay) => delay.min(MaxRetryDelay))

  private val SnapshotAgeReportInterval: Duration = 15.seconds

  /** Loads once now, then every `interval`.
    *
    * The interval is taken rather than a `Schedule` because it is also how long the first
    * refresh waits: the load above has just happened, and `repeat` would otherwise fire a
    * second one immediately. A fixed delay here instead would override the configured
    * interval for as long as it lasted — a five-minute one used to, which meant a freshly
    * registered client stayed invisible to an edge for five minutes no matter what the
    * configuration said.
    *
    * With `fromSnapshot`, a first load that still fails after its retries is answered from the
    * [[ConfigSnapshot]] the source's requests go through, and the source is tried again in the
    * background until it answers. `config_snapshot_age_seconds` is the age of what is served,
    * zero once the source has answered. Without a snapshot the failure stands as before; a
    * snapshot that fails verification fails the load.
    */
  def make[A: Tag](
      interval: Duration = 5.minutes,
      fromSnapshot: Boolean = false,
  ): ZIO[Scope & CacheSource[A], Throwable, ReloadingCache[A]] =
    for
      source <- ZIO.service[CacheSource[A]]
      failures <- Ref.make(0)
      // The cause is worth a stack trace once. Repeating it for every attempt of a two-minute
      // wait buries the rest of startup under the same exception, once per cache, so the
      // attempts after the first only say that the wait is still going on.
      values <- source.getAll
        .tapErrorCause: err =>
          failures.getAndUpdate(_ + 1).flatMap:
            case 0 => ZIO.logWarningCause(s"Couldn't initialize cache ${Tag[A].tag}, retrying", err)
            case n => ZIO.logInfo(s"Still waiting for the source of cache ${Tag[A].tag} (attempt ${n + 1})")
        .retry(initialLoadRetry)
        .tapErrorCause(err => ZIO.logErrorCause(s"Couldn't initialize cache ${Tag[A].tag}", err))
        .map(_ -> Option.empty[Instant])
        .catchAll(error => if fromSnapshot then loadSnapshot(source, error) else ZIO.fail(error))
      (initial, savedAt) = values
      ref <- Ref.make(initial)
      snapshotAge = Metric.gauge("config_snapshot_age_seconds").tagged("cache", Tag[A].tag.toString)
      refresh = source.getAll
        .foldZIO(
          error => ZIO.logErrorCause(Cause.fail(error)),
          data => ref.set(data),
        )
      untilLive = savedAt match
        case None => ZIO.when(fromSnapshot)(snapshotAge.set(0)).unit
        case Some(savedAt) =>
          val reportAge = Clock.instant
            .flatMap(now => snapshotAge.set(java.time.Duration.between(savedAt, now).toMillis / 1000.0))
            .repeat(Schedule.spaced(SnapshotAgeReportInterval)) *> ZIO.never
          source.getAll.retry(SnapshotRetry).flatMap(ref.set).race(reportAge) *>
            snapshotAge.set(0) *>
            ZIO.logInfo(s"Cache ${Tag[A].tag} is loaded from its source again, no longer from the configuration snapshot")
      _ <- (untilLive *> ZIO.sleep(interval) *> refresh.repeat(Schedule.spaced(interval))).forkScoped
    yield ref

  private def loadSnapshot[A: Tag](source: CacheSource[A], error: Throwable): Task[(A, Option[Instant])] =
    ConfigSnapshot.replay(source.getAll).foldZIO(
      {
        case missing: ConfigSnapshot.Missing =>
          ZIO.logWarning(s"Cache ${Tag[A].tag} cannot start from the configuration snapshot: ${missing.getMessage}") *>
            ZIO.fail(error)
        case other =>
          ZIO.logErrorCause(s"Cache ${Tag[A].tag} cannot start from the configuration snapshot", Cause.fail(other)) *>
            ZIO.fail(other)
      },
      {
        case result @ (_, Some(savedAt)) =>
          ZIO.logWarning(
            s"Serving cache ${Tag[A].tag} from the configuration snapshot saved at $savedAt: its source is unreachable",
          ).as(result)
        case result => ZIO.succeed(result)
      },
    )
