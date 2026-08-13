package versola.oauth.client

import versola.oauth.client.model.{
  Acr,
  AuthorizationDetailType,
  AuthorizationDetailTypeRecord,
  ChallengeSettingsRecord,
  ClientId,
  ClientSecret,
  FormRecord,
  Locales,
  OAuthClientRecord,
  OtpType,
  OtpSettings,
  OtpTemplateChannel,
  OtpTemplatePurpose,
  OtpTemplateRecord,
  PassedAuthFactor,
  PasskeySettings,
  PasswordHistorySettings,
  ResourceId,
  ResourceRecord,
  ResourceUri,
  ScopeRecord,
  ScopeToken,
  SubmissionLimits,
  SystemSettingsRecord,
  TenantId,
  ThemeRecord,
}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.metadata.{MetadataSyncClient, ServerMetadataRecord}
import versola.util.{CacheSource, CoreConfig, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.{Client, URL}
import zio.json.ast.Json
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

  def getClientTemplate(id: ClientId, otpType: OtpType, uiLocales: Option[List[String]]): UIO[OtpTemplate]

  def getPasswordTemplate(channel: OtpTemplateChannel, uiLocales: Option[List[String]]): Task[OtpTemplate]

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

  def getUserAgentTtl(id: ClientId): UIO[Duration]

  def getPostLogoutRedirectUris(tenantId: TenantId): UIO[List[URL]]

  def getMetadata: UIO[Json.Obj]

  /** Resolves an RFC 9396 `authorization_details` type to its registered schema, scoped to
    * the requesting client's tenant. */
  def findAuthorizationDetailType(
      tenantId: TenantId,
      `type`: AuthorizationDetailType,
  ): UIO[Option[AuthorizationDetailTypeRecord]]

  /** Resolves an RFC 8707 `resource` request parameter value to its registered resource,
    * scoped to the requesting client's tenant. */
  def findResource(tenantId: TenantId, resource: ResourceUri): UIO[Option[ResourceRecord]]

  /** Resolves a `resource://{resourceId}` indicator within the requesting client's tenant. */
  def findResourceById(tenantId: TenantId, resourceId: ResourceId): UIO[Option[ResourceRecord]]

  def getResourcesForClient(tenantId: TenantId, clientId: ClientId): UIO[List[ResourceRecord]]

  def syncConfiguration: Task[Unit]

object OAuthConfigurationService:
  /** Default TTL for a user-agent (device) record: 6 months. */
  val DefaultUserAgentTtl: Duration = Duration.fromSeconds(15552000L)

  def live: ZLayer[
    Client & SecurityService & Scope & CoreConfig,
    Throwable,
    OAuthConfigurationService,
  ] = {
    def cacheLayer[A: Tag]: ZLayer[Scope & CoreConfig & CacheSource[A], Throwable, ReloadingCache[A]] =
      ZLayer.fromZIO:
        ZIO.serviceWithZIO[CoreConfig](config =>
          ReloadingCache.make[A](Schedule.spaced(config.configurationCacheRefreshInterval)),
        )
    val syncClients =
      CentralSyncTokenService.live >+>
        ((OAuthClientSyncClient.live >+> cacheLayer[Map[ClientId, OAuthClientRecord]]) >+>
          (OAuthScopeSyncClient.live >+> cacheLayer[Vector[ScopeRecord]]) >+>
          (FormSyncClient.live >+> cacheLayer[Vector[FormRecord]]) >+>
          (ThemeSyncClient.live >+> cacheLayer[Vector[ThemeRecord]]) >+>
          (LocaleSyncClient.live >+> cacheLayer[Locales]) >+>
          (OtpTemplateSyncClient.live >+> cacheLayer[Vector[OtpTemplateRecord]]) >+>
          (ChallengeSettingsSyncClient.live >+> cacheLayer[Vector[ChallengeSettingsRecord]]) >+>
          (SystemSettingsSyncClient.live >+> cacheLayer[SystemSettingsRecord]) >+>
          (MetadataSyncClient.live >+> cacheLayer[Json.Obj]) >+>
          (ResourceSyncClient.live >+> cacheLayer[Vector[ResourceRecord]]) >+>
          (AuthorizationDetailTypeSyncClient.live >+> cacheLayer[Vector[AuthorizationDetailTypeRecord]]))
    syncClients >>> ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _))
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
      authorizationDetailTypeCache: ReloadingCache[Vector[AuthorizationDetailTypeRecord]],
      authorizationDetailTypeRepository: AuthorizationDetailTypeSyncClient,
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
          case None => false

    override def verifySecret(clientId: ClientId, secret: Option[Secret]): UIO[Option[OAuthClientRecord]] =
      find(clientId).some.foldZIO(
        _ => ZIO.none,
        client =>
          secret match
            case Some(secret) if client.isConfidential =>
              verifyOneSecret(secret, client.secret)
                .flatMap {
                  case false => verifyOneSecret(secret, client.previousSecret)
                  case true => ZIO.succeed(true)
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

    private def getOtpTemplates(
        tenantId: TenantId,
        otpTemplateId: String,
        otpType: OtpType,
    ): UIO[Option[OtpTemplateRecord]] =
      val channel = otpType match
        case OtpType.sms   => OtpTemplateChannel.sms
        case OtpType.email => OtpTemplateChannel.email
      otpTemplateCache.get.map(_.find: template =>
        template.tenantId == tenantId
          && template.id == otpTemplateId
          && template.purpose == OtpTemplatePurpose.otp
          && template.channel == channel
      )

    override def getClientTemplate(
        id: ClientId,
        otpType: OtpType,
        uiLocales: Option[List[String]],
    ): UIO[OtpTemplate] =
      val templateOpt = find(id).flatMap:
        case None => ZIO.none
        case Some(client) =>
          getOtpTemplates(client.tenantId, client.otpTemplateId, otpType)

      for
        template <- templateOpt
        locales <- getLocales
      yield template match
        case None => IllegalStateTemplate
        case Some(t) =>
          val preferredLocales = uiLocales.getOrElse(Nil) :+ locales.default
          val body = preferredLocales
            .collectFirst { case loc if t.localizations.contains(loc) => t.localizations(loc) }
            .orElse(t.localizations.values.headOption)
            .getOrElse(IllegalStateTemplate)
          OtpTemplate(body)

    override def getPasswordTemplate(channel: OtpTemplateChannel, uiLocales: Option[List[String]]): Task[OtpTemplate] =
      for
        templates <- otpTemplateCache.get
        locales <- getLocales
        template <- ZIO
          .fromOption(templates.find(template =>
            template.purpose == OtpTemplatePurpose.password && template.channel == channel,
          ))
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

    override def getUserAgentTtl(id: ClientId): UIO[Duration] =
      find(id).flatMap:
        case None => ZIO.succeed(OAuthConfigurationService.DefaultUserAgentTtl)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(OAuthConfigurationService.DefaultUserAgentTtl)(s => Duration.fromSeconds(s.userAgentTtlSeconds.toLong)),
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

    /** RFC 9396 §11 requires the AS to advertise the authorization detail types it supports;
      * they are derived from the registry rather than stored in the metadata document. */
    override def getMetadata: UIO[Json.Obj] =
      for
        metadata <- metadataCache.get
        types <- authorizationDetailTypeCache.get
      yield
        val supported = types.map(_.`type`).distinct.sorted
        if supported.isEmpty then metadata
        else
          Json.Obj(
            metadata.fields.filterNot(_._1 == "authorization_details_types_supported") :+
              ("authorization_details_types_supported" -> Json.Arr(supported.map(Json.Str(_))*)),
          )

    override def findAuthorizationDetailType(
        tenantId: TenantId,
        `type`: AuthorizationDetailType,
    ): UIO[Option[AuthorizationDetailTypeRecord]] =
      authorizationDetailTypeCache.get.map(_.find(r => r.tenantId == tenantId && r.`type` == `type`))

    override def findResource(tenantId: TenantId, resource: ResourceUri): UIO[Option[ResourceRecord]] =
      resourceCache.get.map(_.find(r => r.tenantId == tenantId && r.resource == resource))

    override def findResourceById(tenantId: TenantId, resourceId: ResourceId): UIO[Option[ResourceRecord]] =
      resourceCache.get.map(_.find(r => r.tenantId == tenantId && r.resourceId == resourceId))

    override def getResourcesForClient(tenantId: TenantId, clientId: ClientId): UIO[List[ResourceRecord]] =
      resourceCache.get.map(_.filter(r => r.tenantId == tenantId && r.audience.contains(clientId)).toList)

    override def syncConfiguration: Task[Unit] =
      for
        clients <- clientRepository.getAll
        _ <- clientCache.set(clients)
        scopes <- scopeRepository.getAll
        _ <- scopeCache.set(scopes)
        forms <- formRepository.getAll
        _ <- formCache.set(forms)
        themes <- themeRepository.getAll
        _ <- themeCache.set(themes)
        locales <- localeRepository.getAll
        _ <- localeCache.set(locales)
        otpTemplates <- otpTemplateRepository.getAll
        _ <- otpTemplateCache.set(otpTemplates)
        challengeSettings <- challengeSettingsRepository.getAll
        _ <- challengeSettingsCache.set(challengeSettings)
        systemSettings <- systemSettingsRepository.getAll
        _ <- systemSettingsCache.set(systemSettings)
        metadata <- metadataRepository.getAll
        _ <- metadataCache.set(metadata)
        resources <- resourceRepository.getAll
        _ <- resourceCache.set(resources)
        authorizationDetailTypes <- authorizationDetailTypeRepository.getAll
        _ <- authorizationDetailTypeCache.set(authorizationDetailTypes)
      yield ()

    private val IllegalStateTemplate = OtpTemplate("{{code}}")
