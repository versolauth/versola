package versola.util

import zio.*

type ReloadingCache[A] = ReloadingCache.Type[A]

object ReloadingCache:
  opaque type Type[A] <: zio.Ref[A] = zio.Ref[A]

  inline def apply[A](ref: zio.Ref[A]): ReloadingCache[A] = ref

  def constant[A](values: Set[A]): ReloadingCache[Set[A]] =
    ReloadingCache(Unsafe.unsafe(Ref.unsafe.make(values)(using _)))

  /** Retries the initial load, which typically fails only because the source isn't up yet.
    *
    * A cache whose source is another service (edge and auth both sync their configuration from
    * central during their own startup) fails its first load whenever it wins the race against that
    * service. Without a retry the failure propagates out of layer construction and takes the whole
    * application down - observed in production, with central becoming ready ~0.4s after auth had
    * already given up (see deploy.md 9.5). Waiting a little is strictly better than dying and
    * relying on the orchestrator to restart us.
    *
    * Bounded, so a genuinely broken source (bad credentials, wrong URL) still fails startup loudly
    * instead of hanging forever.
    */
  private val initialLoadRetry: Schedule[Any, Any, Any] =
    Schedule.exponential(500.millis, 2.0).jittered && Schedule.recurs(6)

  def make[A: Tag](
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZIO[Scope & CacheSource[A], Throwable, ReloadingCache[A]] =
    for
      source <- ZIO.service[CacheSource[A]]
      values <- source.getAll
        .tapErrorCause(err => ZIO.logWarningCause(s"Couldn't initialize cache ${Tag[A].tag}, retrying", err))
        .retry(initialLoadRetry)
        .tapErrorCause(err => ZIO.logErrorCause(s"Couldn't initialize cache ${Tag[A].tag}", err))
      ref <- Ref.make(values)
      refresh = source.getAll
        .foldZIO(
          error => ZIO.logErrorCause(Cause.fail(error)),
          data => ref.set(data),
        )
      _ <- (ZIO.sleep(5.minutes) *> refresh.repeat(schedule)).forkScoped
    yield ref
