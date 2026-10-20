package versola.central.configuration.forms

import versola.central.CentralConfig
import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Semaphore, Task, ZIO, ZLayer}

trait FormService:
  def getAllForms: Task[Vector[FormRecord]]
  def getSyncForms: Task[Vector[FormRecord]]

  /** Stores a new version. Fails with an [[IllegalArgumentException]] when the languages of `localizations` do not
    * share one set of keys and placeholders, see [[FormLocalizations]].
    */
  def updateForm(
      id: FormId,
      style: String,
      jsSource: Option[String],
      jsCompiled: Option[String],
      localizations: Map[String, Map[String, String]],
      properties: Vector[BackendProperty],
      activate: Boolean,
  ): Task[Unit]
  def setActiveVersion(id: FormId, version: Int): Task[Unit]
  def sync(event: SyncEvent.FormsUpdated): Task[Unit]

object FormService:
  def live: ZLayer[FormRepository & Scope & LocaleService & CentralConfig, Throwable, FormService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[FormRecord]](config.configurationCacheRefreshInterval),
      )
    ) ++ ZLayer.fromZIO(Semaphore.make(1))
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[FormRecord]],
      repository: FormRepository,
      localeService: LocaleService,
      writes: Semaphore,
  ) extends FormService:

    /** A write stores, re-reads every form and replaces the cache with what it read. Two of them at once
      * interleave as store A, read, store B, read, set B's read, set A's read: the cache ends without B until
      * the next reload, and a form that was stored successfully is not listed. The three steps therefore run
      * one write at a time.
      */
    private def storeAndReload(store: Task[Unit]): Task[Unit] =
      writes.withPermit(store *> repository.getAll.flatMap(cache.set))

    override def getAllForms: Task[Vector[FormRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def getSyncForms: Task[Vector[FormRecord]] =
      for
        forms <- cache.get.map(_.filter(_.active).sortBy(_.id))
        activeLocales <- localeService.getActive.map(_.map(_.code).toSet)
        formWithActiveLocales = forms.map(f => f.copy(localizations = f.localizations.filter((code, _) => activeLocales.contains(code))))
      yield formWithActiveLocales

    override def updateForm(
        id: FormId,
        style: String,
        jsSource: Option[String],
        jsCompiled: Option[String],
        localizations: Map[String, Map[String, String]],
        properties: Vector[BackendProperty],
        activate: Boolean,
    ): Task[Unit] =
      for
        _ <- ZIO.fromEither(FormLocalizations.validate(localizations)).mapError(IllegalArgumentException(_))
        _ <- storeAndReload(repository.upsertForm(id, style, jsSource, jsCompiled, localizations, properties, activate))
      yield ()

    override def setActiveVersion(id: FormId, version: Int): Task[Unit] =
      storeAndReload(repository.setActiveVersion(id, version))

    override def sync(event: SyncEvent.FormsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id, event.version),
      )
