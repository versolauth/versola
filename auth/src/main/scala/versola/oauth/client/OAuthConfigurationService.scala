package versola.oauth.client

import versola.oauth.client.model.{ChallengeSettingsRecord, ClientId, ClientSecret, FormRecord, Locales, OAuthClientRecord, OtpSettings, OtpTemplateRecord, PasskeySettings, PasswordHistorySettings, ScopeRecord, ScopeToken, SubmissionLimits, TenantId, ThemeRecord}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.{CoreConfig, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.Client
import zio.prelude.{EqualOps, NonEmptySet}

trait OAuthConfigurationService:
  def find(id: ClientId): UIO[Option[OAuthClientRecord]]

  def verifySecret(
      id: ClientId,
      providedSecret: Option[Secret],
  ): UIO[Option[OAuthClientRecord]]

  def getScopes: UIO[Vector[ScopeRecord]]

  def getForm(id: String): UIO[Option[FormRecord]]

  def getTheme(id: String): UIO[Option[ThemeRecord]]

  def getClientTemplate(id: ClientId, uiLocales: Option[List[String]]): UIO[OtpTemplate]

  def getLocales: UIO[Locales]

  def getAllowedPhonePrefixes(id: ClientId): UIO[List[String]]

  def getPasswordRegex(id: ClientId): UIO[Option[String]]

  def getSubmissionLimits(id: ClientId): UIO[SubmissionLimits]

  def getIpHeader(id: ClientId): UIO[String]

  def getOtpSettings(id: ClientId): UIO[OtpSettings]

  def getPasskeySettings(id: ClientId): UIO[Option[PasskeySettings]]

  def getPasswordHistorySettings(id: ClientId): UIO[PasswordHistorySettings]

  def getAuthConversationTtl(id: ClientId): UIO[Duration]

  def getSessionTtl(id: ClientId): UIO[Duration]

  def getSessionIdleTtl(id: ClientId): UIO[Option[Duration]]

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
          (ChallengeSettingsSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ChallengeSettingsRecord]](schedule)))))
    syncClients >>> ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _))
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
  ) extends OAuthConfigurationService:

    def find(id: ClientId): UIO[Option[OAuthClientRecord]] =
      clientCache.get.map(_.get(id))

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

    override def getAllowedPhonePrefixes(id: ClientId): UIO[List[String]] =
      find(id).flatMap:
        case None => ZIO.succeed(Nil)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(Nil)(_.allowedPrefixes),
          )

    override def getPasswordRegex(id: ClientId): UIO[Option[String]] =
      find(id).flatMap:
        case None => ZIO.none
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .flatMap(_.passwordRegex),
          )

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

    override def getPasswordHistorySettings(id: ClientId): UIO[PasswordHistorySettings] =
      find(id).flatMap:
        case None => ZIO.succeed(PasswordHistorySettings.default)
        case Some(client) =>
          challengeSettingsCache.get.map(
            _.find(_.tenantId == client.tenantId)
              .fold(PasswordHistorySettings.default)(s => PasswordHistorySettings(s.passwordHistorySize, s.passwordNumDifferent)),
          )

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

    private val IllegalStateTemplate = OtpTemplate("{{code}}")