package versola.util

import zio.*

type ReloadingCache[A] = ReloadingCache.Type[A]

object ReloadingCache:
  opaque type Type[A] <: zio.Ref[A] = zio.Ref[A]

  inline def apply[A](ref: zio.Ref[A]): ReloadingCache[A] = ref

  def constant[A](values: Set[A]): ReloadingCache[Set[A]] =
    ReloadingCache(Unsafe.unsafe(Ref.unsafe.make(values)(using _)))

  def make[A: Tag](
      schedule: Schedule[Any, Any, Any] = Schedule.spaced(5.minute),
  ): ZIO[Scope & CacheSource[A], Throwable, ReloadingCache[A]] =
    for
      source <- ZIO.service[CacheSource[A]]
      values <- source.getAll
      ref <- Ref.make(values)
      _ <- source.getAll
        .foldZIO(
          error => ZIO.logErrorCause(Cause.fail(error)),
          data => ref.set(data),
        )
        .repeat(schedule)
        .forkScoped
    yield ref