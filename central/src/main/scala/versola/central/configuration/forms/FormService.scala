package versola.central.configuration.forms

import versola.central.CentralConfig
import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

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
    )
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[FormRecord]],
      repository: FormRepository,
      localeService: LocaleService,
  ) extends FormService:

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
        // The cache is not replaced here: the row's trigger notifies, and `sync` applies that one record. A wholesale
        // `set` from a read taken before a concurrent write's notification was applied would drop that write's form.
        _ <- repository.upsertForm(id, style, jsSource, jsCompiled, localizations, properties, activate)
      yield ()

    override def setActiveVersion(id: FormId, version: Int): Task[Unit] =
      repository.setActiveVersion(id, version)

    override def sync(event: SyncEvent.FormsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id, event.version),
      )
