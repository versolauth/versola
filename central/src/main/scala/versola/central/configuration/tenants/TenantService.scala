package versola.central.configuration.tenants

import versola.central.CentralConfig
import versola.central.configuration.{CreateTenantRequest, UpdateTenantRequest}
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsService, PasskeySettings, SubmissionLimits}
import versola.central.configuration.edges.EdgeId
import versola.util.ReloadingCache
import zio.{Schedule, Scope, Task, ZIO, ZLayer, durationInt}

trait TenantService:
  def getAllTenants: Task[Vector[TenantRecord]]

  /** Falls back to `SubmissionLimits.recommended` -- rather than failing the request --
    * when `request.submissionLimits` is missing or leaves a category unconfigured, so a
    * tenant is never created with no submission-rate protection at all (see
    * `SubmissionLimits.isConfigured`).
    */
  def createTenant(
      request: CreateTenantRequest,
  ): Task[Unit]

  def updateTenant(
      request: UpdateTenantRequest,
  ): Task[Unit]

  def deleteTenant(
      id: TenantId,
  ): Task[Unit]

  def sync(): Task[Unit]

object TenantService:
  def live: ZLayer[TenantRepository & ChallengeSettingsService & Scope & CentralConfig, Throwable, TenantService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[TenantRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      cache: ReloadingCache[Vector[TenantRecord]],
      tenantRepository: TenantRepository,
      challengeSettingsService: ChallengeSettingsService,
      config: CentralConfig,
  ) extends TenantService:
    export tenantRepository.deleteTenant

    def getAllTenants: Task[Vector[TenantRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def createTenant(
        request: CreateTenantRequest,
    ): Task[Unit] =
      val submissionLimits =
        if SubmissionLimits.isConfigured(request.submissionLimits) then request.submissionLimits
        else SubmissionLimits.recommended
      for
        _ <- tenantRepository.createTenant(request.id, request.description, request.edgeId.map(EdgeId(_)))
        _ <- challengeSettingsService.upsertSettings(defaultChallengeSettings(request.id, submissionLimits))
      yield ()

    override def updateTenant(
        request: UpdateTenantRequest,
    ): Task[Unit] =
      tenantRepository.updateTenant(request.id, request.description, request.edgeId.map(EdgeId(_)))

    override def sync(): Task[Unit] =
      for
        tenants <- tenantRepository.getAll
        _ <- cache.set(tenants)
      yield ()

    /** Everything but the caller-supplied rate limits mirrors `BootstrapService`'s defaults
      * for the seed tenant -- this is that same baseline made available to every tenant
      * created afterward, editable later via the challenge-settings console.
      */
    private def defaultChallengeSettings(
        tenantId: TenantId,
        submissionLimits: SubmissionLimits,
    ): ChallengeSettingsRecord =
      ChallengeSettingsRecord(
        tenantId = tenantId,
        allowedPrefixes = List.empty,
        submissionLimits = submissionLimits,
        otpLength = 6,
        otpResendAfter = 60,
        // rpId/origins are a per-deployment web origin, not a per-tenant one (every tenant
        // is served from the same identity domain) -- reuse the bootstrap-seeded value when
        // present. When absent, leave it empty rather than guessing: the operator fills it
        // in via the challenge-settings console before offering passkey login to this
        // tenant, exactly as they already must review `postLogoutRedirectUris` below.
        passkeySettings = PasskeySettings(
          rpId = config.bootstrap.map(_.passkey.rpId).getOrElse(""),
          rpName = "Versola",
          origins = config.bootstrap.map(_.passkey.origins).getOrElse(Nil),
          userVerification = "preferred",
        ),
        authConversationTtlSeconds = 900,
        sessionTtlSeconds = 86400,
        sessionIdleTtlSeconds = None,
        userAgentTtlSeconds = 15552000,
        ipHeader = "X-Real-IP",
        acrVocabulary = None,
        postLogoutRedirectUris = Nil,
      )
