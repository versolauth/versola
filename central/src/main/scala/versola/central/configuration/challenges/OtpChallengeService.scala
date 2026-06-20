package versola.central.configuration.challenges

import versola.central.configuration.locales.LocaleService
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZLayer}

trait OtpChallengeService:
  def getTemplates(tenantId: TenantId): Task[Vector[OtpTemplateRecord]]
  def getAllTemplates: Task[Vector[OtpTemplateRecord]]
  def getSyncTemplates: Task[Vector[OtpTemplateRecord]]
  def upsertTemplate(record: OtpTemplateRecord): Task[Unit]
  def deleteTemplate(id: String, tenantId: TenantId): Task[Unit]
  def sync(event: SyncEvent.OtpTemplatesUpdated): Task[Unit]

object OtpChallengeService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[OtpChallengeRepository & Scope & LocaleService, Throwable, OtpChallengeService] =
    ZLayer(ReloadingCache.make[Vector[OtpTemplateRecord]](schedule))
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
      yield templates.map(t => t.copy(localizations = t.localizations.filter((code, _) => activeLocales.contains(code))))

    override def upsertTemplate(record: OtpTemplateRecord): Task[Unit] =
      repository.upsertTemplate(record)

    override def deleteTemplate(id: String, tenantId: TenantId): Task[Unit] =
      repository.deleteTemplate(id, tenantId)

    override def sync(event: SyncEvent.OtpTemplatesUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.find(event.id, event.tenantId),
      )
