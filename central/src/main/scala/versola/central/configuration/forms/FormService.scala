package versola.central.configuration.forms

import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait FormService:
  def getAllForms: Task[Vector[FormRecord]]
  def getSyncForms: Task[Vector[FormRecord]]
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
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[FormRepository & Scope & LocaleService, Throwable, FormService] =
    ZLayer(ReloadingCache.make[Vector[FormRecord]](schedule))
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
        forms         <- cache.get.map(_.filter(_.active).sortBy(_.id))
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
        _ <- repository.upsertForm(id, style, jsSource, jsCompiled, localizations, properties, activate)
        forms <- repository.getAll
        _ <- cache.set(forms)
      yield ()

    override def setActiveVersion(id: FormId, version: Int): Task[Unit] =
      for
        _ <- repository.setActiveVersion(id, version)
        forms <- repository.getAll
        _ <- cache.set(forms)
      yield ()

    override def sync(event: SyncEvent.FormsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id, event.version),
      )
