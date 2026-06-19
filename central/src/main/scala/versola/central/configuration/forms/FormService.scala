package versola.central.configuration.forms

import versola.central.CentralConfig
import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.util.ReloadingCache
import zio.json.DecoderOps
import zio.{Schedule, Scope, Task, ZIO, ZLayer}
import scala.io.Source

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
  private val defaultForms: Vector[(String, Vector[BackendProperty])] = Vector(
    "credential" -> Vector(
      StringArrayProperty("primaryCredentials", Vector("email", "phone", "login")),
      BooleanProperty("inlinePassword"),
      BooleanProperty("passkey"),
    ),
    "otp" -> Vector(
      NumberProperty("length", 6, Some(4), Some(6)),
      NumberProperty("resendAfter", 60, None, None),
    ),
    "password" -> Vector.empty,
    "access-denied" -> Vector.empty,
  )

  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[FormRepository & CentralConfig & Scope & LocaleService, Throwable, FormService] =
    ZLayer(ReloadingCache.make[Vector[FormRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _, _, _))
      >>> ZLayer(ZIO.serviceWithZIO[FormService.Impl](service => service.initialize().as(service)))

  class Impl(
      cache: ReloadingCache[Vector[FormRecord]],
      repository: FormRepository,
      config: CentralConfig,
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

    def initialize(): Task[Unit] =
      ZIO.when(config.initialize):
        for
          _ <- ZIO.logInfo("Initializing forms from resources...")
          _ <- ZIO.foreachDiscard(defaultForms) { (formId, properties) =>
            (for
              jsSource <- readResource(s"forms/$formId.tsx")
              jsCompiled <- readResource(s"forms/$formId.js")
              style <- readResource(s"forms/$formId.css")
              i18nJson <- readResource(s"forms/$formId.i18n.json")
              localizations <- ZIO.fromEither(i18nJson.fromJson[Map[String, Map[String, String]]])
                .mapError(message => new RuntimeException(s"Invalid i18n for form $formId: $message"))
              _ <- updateForm(FormId(formId), style, Some(jsSource), Some(jsCompiled), localizations, properties, activate = true)
            yield ()).catchAll(error => ZIO.logError(s"Failed to initialize form $formId: ${error.getMessage}"))
          }

          forms <- repository.getAll
          _ <- cache.set(forms)
        yield ()
      .unit

    private def readResource(path: String): Task[String] =
      ZIO.blocking:
        ZIO.attemptBlocking:
          val source = Source.fromResource(path)
          try source.mkString finally source.close()
