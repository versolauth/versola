package versola.central.configuration.challenges

import versola.central.CentralConfig
import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import versola.util.http.{BadRequest, Observability}
import zio.{Schedule, Scope, Task, ZIO, ZLayer}

trait OtpChallengeService:
  def getTemplates(tenantId: TenantId): Task[Vector[OtpTemplateRecord]]
  def getAllTemplates: Task[Vector[OtpTemplateRecord]]
  def getSyncTemplates: Task[Vector[OtpTemplateRecord]]

  /** `keepStoredSubjects`: the request named no subjects (an older client), so the template keeps the ones it has. A request
    * that names them, an empty map included, replaces them.
    */
  def upsertTemplate(record: OtpTemplateRecord, keepStoredSubjects: Boolean): Task[Unit]
  def deleteTemplate(id: String, tenantId: TenantId, purpose: OtpTemplatePurpose, channel: OtpTemplateChannel): Task[Unit]
  def sync(event: SyncEvent.OtpTemplatesUpdated): Task[Unit]

object OtpChallengeService:
  /** Well above any subject a mail client shows in full, and a bound on what one request can store per locale. */
  val MaxSubjectLength = 200

  def live: ZLayer[OtpChallengeRepository & Scope & LocaleService & CentralConfig, Throwable, OtpChallengeService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[OtpTemplateRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      cache: ReloadingCache[Vector[OtpTemplateRecord]],
      repository: OtpChallengeRepository,
      localeService: LocaleService,
  ) extends OtpChallengeService:

    override def getTemplates(tenantId: TenantId): Task[Vector[OtpTemplateRecord]] =
      cache.get.map(_.filter(_.tenantId == tenantId))

    override def getAllTemplates: Task[Vector[OtpTemplateRecord]] =
      cache.get

    override def getSyncTemplates: Task[Vector[OtpTemplateRecord]] =
      for
        templates <- cache.get
        activeLocales <- localeService.getActive.map(_.map(_.code).toSet)
      yield templates.map(t =>
        t.copy(
          localizations = t.localizations.filter((code, _) => activeLocales.contains(code)),
          subjects = t.subjects.filter((code, _) => activeLocales.contains(code)),
        ),
      )

    override def upsertTemplate(record: OtpTemplateRecord, keepStoredSubjects: Boolean): Task[Unit] =
      for
        stored <-
          if keepStoredSubjects then repository.find(record.id, record.tenantId, record.purpose, record.channel).map(_.map(_.subjects))
          else ZIO.none
        merged = stored.fold(record)(subjects => record.copy(subjects = subjects))
        _ <- validate(merged) match
          // BadRequest is what the server middleware answers with a 400; any other exception would be a 500.
          case Left(error) => Observability.setError("invalid_otp_template", Some(error)) *> ZIO.fail(BadRequest(error))
          case Right(_) => repository.upsertTemplate(merged)
      yield ()

    override def deleteTemplate(id: String, tenantId: TenantId, purpose: OtpTemplatePurpose, channel: OtpTemplateChannel): Task[Unit] =
      repository.deleteTemplate(id, tenantId, purpose, channel)

    override def sync(event: SyncEvent.OtpTemplatesUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id, event.tenantId, event.purpose, event.channel),
      )

    private def validate(record: OtpTemplateRecord): Either[String, Unit] =
      validateSubjects(record).flatMap(_ => validateBodies(record))

    /** A subject is a mail header, so one line; and it is for an email, where an SMS has no such thing. */
    private def validateSubjects(record: OtpTemplateRecord): Either[String, Unit] =
      if record.subjects.nonEmpty && record.channel != OtpTemplateChannel.email then Left("Only email templates have a subject")
      else
        record.subjects.toList.collectFirst {
          case (locale, subject) if subject.trim.isEmpty => s"Subject $locale must not be empty"
          case (locale, subject) if subject.length > OtpChallengeService.MaxSubjectLength =>
            s"Subject $locale must not be longer than ${OtpChallengeService.MaxSubjectLength} characters"
          case (locale, subject) if subject.exists(_.isControl) => s"Subject $locale must be one line"
        }.toLeft(())

    private def validateBodies(record: OtpTemplateRecord): Either[String, Unit] =
      val placeholders = record.purpose match
        case OtpTemplatePurpose.password => List("{{password}}", "{{expiresHours}}")
        case OtpTemplatePurpose.otp => List("{{code}}")
      record.localizations.toList.collectFirst {
        case (locale, template) if placeholders.exists(placeholder => !template.contains(placeholder)) =>
          s"Localization $locale is missing a required template placeholder"
      } match
        case Some(error) => Left(error)
        case None if record.channel == OtpTemplateChannel.email && record.localizations.values.exists(template => !containsHtml(template)) =>
          Left("Email OTP templates must contain HTML")
        case None => Right(())

    private def containsHtml(template: String): Boolean =
      template.matches("(?s).*<[A-Za-z][^>]*>.*")
