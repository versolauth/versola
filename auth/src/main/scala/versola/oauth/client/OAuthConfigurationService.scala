package versola.oauth.client

import versola.oauth.client.model.{Acr, ChallengeSettingsRecord, ClientId, ClientSecret, FormRecord, Locales, OAuthClientRecord, OtpSettings, OtpTemplateRecord, PassedAuthFactor, PasskeySettings, PasswordHistorySettings, ResourceId, ResourceRecord, ResourceUri, ScopeRecord, ScopeToken, SubmissionLimits, SystemSettingsRecord, TenantId, ThemeRecord}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.metadata.{MetadataSyncClient, ServerMetadataRecord}
import versola.util.{CoreConfig, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.json.ast.Json
import zio.http.{Client, URL}
import zio.prelude.{EqualOps, NonEmptyList, NonEmptySet}

trait OAuthConfigurationService:
  def find(id: ClientId): UIO[Option[OAuthClientRecord]]

  def findByTenant(tenantId: TenantId): UIO[Vector[OAuthClientRecord]]

  def verifySecret(
      id: ClientId,
      providedSecret: Option[Secret],
  ): UIO[Option[OAuthClientRecord]]

  def getScopes: UIO[Vector[ScopeRecord]]

  def getForm(id: String): UIO[Option[FormRecord]]

  def getTheme(id: String): UIO[Option[ThemeRecord]]

  def getClientTemplate(id: ClientId, uiLocales: Option[List[String]]): UIO[OtpTemplate]

  def getPasswordTemplate(uiLocales: Option[List[String]]): Task[OtpTemplate]

  def getLocales: UIO[Locales]

  def getAllowedPhonePrefixes(id: ClientId): UIO[List[String]]

  def getPasswordRegex: UIO[String]

  def getSubmissionLimits(id: ClientId): UIO[SubmissionLimits]

  def getIpHeader(id: ClientId): UIO[String]

  def getOtpSettings(id: ClientId): UIO[OtpSettings]

  def getPasskeySettings(id: ClientId): UIO[Option[PasskeySettings]]

  def getPasswordHistorySettings: UIO[PasswordHistorySettings]

  def getAuthConversationTtl(id: ClientId): UIO[Duration]

  def getSessionTtl(id: ClientId): UIO[Duration]

  def getAcrVocabulary(id: ClientId): UIO[Map[Acr, NonEmptyList[PassedAuthFactor]]]

  def getSessionIdleTtl(id: ClientId): UIO[Option[Duration]]

  def getPostLogoutRedirectUris(tenantId: TenantId): UIO[List[URL]]

  def getMetadata: UIO[Json.Obj]

  /** Resolves an RFC 8707 `resource` request parameter value to its registered resource,
    * scoped to the requesting client's tenant. */
  def findResource(tenantId: TenantId, resource: ResourceUri): UIO[Option[ResourceRecord]]

  def syncConfiguration: Task[Unit]

object OAuthConfigurationService:
  def live(schedule: Schedule[Any, Any, Any]): ZLayer[
    Client & SecurityService & Scope & CoreConfig,
    Throwable,
    OAuthConfigurationService,
  ] = {
    val syncClients =
      CentralSyncTokenService.live >+>
        ((OAuthClientSyncClient.live >+> ZLayer(ReloadingCache.make[Map[ClientId, OAuthClientRecord]](schedule)) >+>
          (OAuthScopeSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ScopeRecord]](schedule))) >+>
          (FormSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[FormRecord]](schedule))) >+>
          (ThemeSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ThemeRecord]](schedule))) >+>
          (LocaleSyncClient.live >+> ZLayer(ReloadingCache.make[Locales](schedule))) >+>
          (OtpTemplateSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[OtpTemplateRecord]](schedule))) >+>
          (ChallengeSettingsSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ChallengeSettingsRecord]](schedule))) >+>
          (SystemSettingsSyncClient.live >+> ZLayer(ReloadingCache.make[SystemSettingsRecord](schedule))) >+>
          (MetadataSyncClient.live >+> ZLayer(ReloadingCache.make[Json.Obj](schedule))) >+>
          (ResourceSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ResourceRecord]](schedule)))))
    syncClients >>> ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _))
  }

  case class Impl(
      clientCache: ReloadingCache[Map[ClientId, OAuthClientRecord]],
      clientRepository: OAuthClientSyncClient,
      scopeCache: ReloadingCache[Vector[ScopeRecord]],
      scopeRepository: OAuthScopeSyncClient,
      formCache: ReloadingCache[Vector[FormRecord]],
      formRepository: FormSyncClient,
      themeCache: ReloadingCache[Vector[ThemeRecord]],
      themeRepository: ThemeSyncClient,
      localeCache: ReloadingCache[Locales],
      localeRepository: LocaleSyncClient,
      otpTemplateCache: ReloadingCache[Vector[OtpTemplateRecord]],
      otpTemplateRepository: OtpTemplateSyncClient,
      challengeSettingsCache: ReloadingCache[Vector[ChallengeSettingsRecord]],
      challengeSettingsRepository: ChallengeSettingsSyncClient,
      systemSettingsCache: ReloadingCache[SystemSettingsRecord],
      systemSettingsRepository: SystemSettingsSyncClient,
      metadataCache: ReloadingCache[Json.Obj],
      metadataRepository: MetadataSyncClient,
      resourceCache: ReloadingCache[Vector[ResourceRecord]],
      resourceRepository: ResourceSyncClient,
  ) extends OAuthConfigurationService:

    def find(id: ClientId): UIO[Option[OAuthClientRecord]] =
      clientCache.get.map(_.get(id))

    override def findByTenant(tenantId: TenantId): UIO[Vector[OAuthClientRecord]] =
      clientCache.get.map(_.values.filter(_.tenantId == tenantId).toVector)

    private def verifyOneSecret(
        secret: Secret,
        stored: Option[Secret],
    ): UIO[Boolean] =
      ZIO.succeed:
        stored match
          case Some(stored) => java.security.MessageDigest.isEqual(secret, stored)
          case None         => false

    override def verifySecret(clientId: ClientId, secret: Option[Secret]): UIO[Option[OAuthClientRecord]] =
      find(clientId).some.foldZIO(
        _ => ZIO.none,
        client =>
          secret match
            case Some(secret) if client.isConfidential =>
              verifyOneSecret(secret, client.secret)
                .flatMap {
                  case false => verifyOneSecret(secret, client.previousSecret)
                  case true  => ZIO.succeed(true)
                }
                .map(Option.when(_)(client))

            case None if client.isPublic =>
              ZIO.some(client)

            case _ =>
              ZIO.none,
      )

    override def getScopes: UIO[Vector[ScopeRecord]] =
      scopeCache.get

    override def getForm(id: String): UIO[Option[FormRecord]] =
      formCache.get.map(_.find(_.id == id))

    override def getTheme(id: String): UIO[Option[ThemeRecord]] =
      themeCache.get.map(_.find(_.id == id))

    override def getLocales: UIO[Locales] =
      localeCache.get

    private def getOtpTemplates(tenantId: TenantId, otpTemplateId: String): UIO[Option[OtpTemplateRecord]] =
      otpTemplateCache.get.map(_.find(it => it.tenantId == tenantId && it.id == otpTemplateId))

    override def getClientTemplate(id: ClientId, uiLocales: Option[List[String]]): UIO[OtpTemplate] =
      val templateOpt = find(id).flatMap:
        case None => ZIO.none
        case Some(client) =>
          getOtpTemplates(client.tenantId, client.otpTemplateId)

      for
        template <- templateOpt
        locales  <- getLocales
      yield template match
        case None => IllegalStateTemplate
        case Some(t) =>
          val preferredLocales = uiLocales.getOrElse(Nil) :+ locales.default
          val body = preferredLocales
            .collectFirst { case loc if t.localizations.contains(loc) => t.localizations(loc) }
            .orElse(t.localizations.values.headOption)
            .getOrElse(IllegalStateTemplate)
          OtpTemplate(body)

    override def getPasswordTemplate(uiLocales: Option[List[String]]): Task[OtpTemplate] =
      for
        templates <- otpTemplateCache.get
        locales <- getLocales
        template <- ZIO
          .fromOption(templates.find(_.purpose == "password"))
          .orElseFail(RuntimeException("No global password template configured"))
        preferredLocales = uiLocales.getOrElse(Nil) :+ locales.default
        body <- ZIO
          .fromOption(
            preferredLocales
              .collectFirst { case loc if template.localizations.contains(loc) => template.localizations(loc) }
              .orElse(template.localizations.values.headOption),
          )
          .orElseFail(RuntimeException("Password template has no localizations"))
      yield OtpTemplate(body)

    override def getAllowedPhonePrefixes(id: ClientId): UIO[List[String]] =
      find(id).flatMap:
        case None => ZIO.succeed(Nil)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(Nil)(_.allowedPrefixes),
          )

    override def getPasswordRegex: UIO[String] =
      systemSettingsCache.get.map(_.passwordRegex)

    override def getSubmissionLimits(id: ClientId): UIO[SubmissionLimits] =
      find(id).flatMap:
        case None => ZIO.succeed(SubmissionLimits.empty)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(SubmissionLimits.empty)(_.submissionLimits),
          )

    override def getIpHeader(id: ClientId): UIO[String] =
      find(id).flatMap:
        case None => ZIO.succeed("X-Real-IP")
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold("X-Real-IP")(_.ipHeader),
          )

    override def getOtpSettings(id: ClientId): UIO[OtpSettings] =
      find(id).flatMap:
        case None => ZIO.succeed(OtpSettings.default)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(OtpSettings.default)(s => OtpSettings(length = s.otpLength, resendAfter = s.otpResendAfter)),
          )

    override def getPasskeySettings(id: ClientId): UIO[Option[PasskeySettings]] =
      find(id).flatMap:
        case None => ZIO.none
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .map(_.passkeySettings),
          )

    override def getPasswordHistorySettings: UIO[PasswordHistorySettings] =
      systemSettingsCache.get.map(s => PasswordHistorySettings(s.passwordHistorySize, s.passwordNumDifferent))

    override def getAuthConversationTtl(id: ClientId): UIO[Duration] =
      find(id).flatMap:
        case None => ZIO.succeed(Duration.fromSeconds(900))
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(Duration.fromSeconds(900))(s => Duration.fromSeconds(s.authConversationTtlSeconds.toLong)),
          )

    override def getSessionTtl(id: ClientId): UIO[Duration] =
      find(id).flatMap:
        case None => ZIO.succeed(Duration.fromSeconds(86400))
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(Duration.fromSeconds(86400))(s => Duration.fromSeconds(s.sessionTtlSeconds.toLong)),
          )

    override def getSessionIdleTtl(id: ClientId): UIO[Option[Duration]] =
      find(id).flatMap:
        case None => ZIO.none
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .flatMap(_.sessionIdleTtlSeconds)
              .map(s => Duration.fromSeconds(s.toLong)),
          )

    override def getAcrVocabulary(id: ClientId): UIO[Map[Acr, NonEmptyList[PassedAuthFactor]]] =
      find(id).flatMap:
        case None => ZIO.succeed(Map.empty)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .flatMap(_.acrVocabulary)
              .getOrElse(Map.empty)
              .flatMap { case (k, vs) => NonEmptyList.fromIterableOption(vs).map(Acr(k) -> _) },
          )

    override def getPostLogoutRedirectUris(tenantId: TenantId): UIO[List[URL]] =
      challengeSettingsCache.get.map(
        _.find(_.tenantId == tenantId)
          .fold(List.empty[URL])(_.postLogoutRedirectUris.flatMap(URL.decode(_).toOption)),
      )

    override def getMetadata: UIO[Json.Obj] =
      metadataCache.get

    override def findResource(tenantId: TenantId, resource: ResourceUri): UIO[Option[ResourceRecord]] =
      resourceCache.get.map(_.find(r => r.tenantId == tenantId && r.resource == resource))

    override def syncConfiguration: Task[Unit] =
      for
        clients           <- clientRepository.getAll
        _                 <- clientCache.set(clients)
        scopes            <- scopeRepository.getAll
        _                 <- scopeCache.set(scopes)
        forms             <- formRepository.getAll
        _                 <- formCache.set(forms)
        themes            <- themeRepository.getAll
        _                 <- themeCache.set(themes)
        locales           <- localeRepository.getAll
        _                 <- localeCache.set(locales)
        otpTemplates      <- otpTemplateRepository.getAll
        _                 <- otpTemplateCache.set(otpTemplates)
        challengeSettings <- challengeSettingsRepository.getAll
        _                 <- challengeSettingsCache.set(challengeSettings)
        systemSettings    <- systemSettingsRepository.getAll
        _                 <- systemSettingsCache.set(systemSettings)
        metadata          <- metadataRepository.getAll
        _                 <- metadataCache.set(metadata)
        resources         <- resourceRepository.getAll
        _                 <- resourceCache.set(resources)
      yield ()

    private val IllegalStateTemplate = OtpTemplate("{{code}}")