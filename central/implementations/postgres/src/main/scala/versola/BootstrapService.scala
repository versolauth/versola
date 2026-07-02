package versola

import versola.central.CentralConfig
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsRepository, OtpChallengeRepository, OtpTemplateRecord, PasskeySettings, RateLimit, SubmissionLimits}
import versola.central.configuration.clients.{AuthFlow, AuthorizationPreset, AuthorizationPresetRepository, ClientAlreadyExists, OAuthClientService, PresetId, PrimaryAuthFlow, PrimaryCredential, ResponseType}
import versola.central.configuration.edges.{EdgeId, EdgeRepository}
import versola.central.configuration.forms.{BackendProperty, BooleanProperty, FormId, FormRepository, NumberProperty, StringArrayProperty}
import versola.central.configuration.jwks.{JwksRepository, JwksService}
import versola.central.configuration.locales.{LocaleRecord, LocaleRepository}
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.roles.{RoleId, RoleRepository}
import versola.central.configuration.scopes.{Claim, OAuthScopeRepository, ScopeToken}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.themes.{ThemeRecord, ThemeRepository}
import versola.central.configuration.{CreateClaim, CreateClientRequest}
import versola.central.users.{Login, UserConflict, UserId, UserRepository}
import versola.util.RedirectUri
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, ZIO, ZLayer}

import scala.io.Source

trait BootstrapService:
  def bootstrap: Task[Unit]

object BootstrapService:

  private def localized(en: String, ru: String): Map[String, String] =
    Map("en" -> en, "ru" -> ru)

  private def claim(id: String, en: String, ru: String): CreateClaim =
    CreateClaim(Claim(id), localized(en, ru))

  private case class ScopeSeed(
      token: ScopeToken,
      description: Map[String, String],
      claims: List[CreateClaim],
  )

  private val permissionCatalog: List[(Permission, Map[String, String])] = List(
    Permission("oauth:read")       -> localized("View OAuth clients and scopes", "Просмотр OAuth клиентов и скоупов"),
    Permission("oauth:manage")     -> localized("Manage OAuth clients and scopes", "Управление OAuth клиентами и скоупами"),
    Permission("oauth:secrets")    -> localized("View OAuth client secrets", "Просмотр секретов OAuth клиентов"),
    Permission("access:read")      -> localized("View roles and permissions", "Просмотр ролей и прав"),
    Permission("access:manage")    -> localized("Manage roles and permissions", "Управление ролями и правами"),
    Permission("security:read")    -> localized("View security policies and challenges", "Просмотр политик безопасности"),
    Permission("security:manage")  -> localized("Manage security policies and challenges", "Управление политиками безопасности"),
    Permission("users:read")       -> localized("View users", "Просмотр пользователей"),
    Permission("users:manage")     -> localized("Manage users", "Управление пользователями"),
    Permission("resources:read")   -> localized("View protected resources", "Просмотр защищенных ресурсов"),
    Permission("resources:manage") -> localized("Manage protected resources", "Управление защищенными ресурсами"),
  )

  private val allPermissions: List[Permission] = permissionCatalog.map(_._1)

  private val roleCatalog: List[(RoleId, Map[String, String], List[Permission])] =
    val readOnly = allPermissions.filter(_.endsWith(":read"))
    List(
      (
        RoleId("tenant-admin"),
        localized("Tenant Administrator", "Администратор тенанта"),
        allPermissions,
      ),
      (
        RoleId("security"),
        localized("Security Officer", "Офицер безопасности (ИБ)"),
        List("oauth:read", "oauth:manage", "oauth:secrets", "users:read", "users:manage", "access:read", "security:read", "resources:read").map(Permission(_)),
      ),
      (
        RoleId("support"),
        localized("Support Engineer", "Инженер поддержки"),
        List("users:read", "users:manage", "oauth:read", "access:read", "security:read", "resources:read").map(Permission(_)),
      ),
      (
        RoleId("auditor"),
        localized("Auditor", "Аудитор"),
        readOnly,
      ),
      (
        RoleId("viewer"),
        localized("Read-only Viewer (No PII)", "Наблюдатель (без ПДн)"),
        readOnly.filter(_ != "users:read"),
      ),
    )

  /** Standard OpenID Connect scopes and their claims (OpenID Connect Core 1.0, §5.4). */
  private val scopeCatalog: List[ScopeSeed] = List(
    ScopeSeed(
      ScopeToken("openid"),
      localized("OpenID Connect authentication", "Аутентификация OpenID Connect"),
      List(claim("sub", "Unique identifier of the user", "Уникальный идентификатор пользователя")),
    ),
    ScopeSeed(
      ScopeToken("profile"),
      localized("Basic profile information", "Основная информация профиля"),
      List(
        claim("name", "Full name", "Полное имя"),
        claim("family_name", "Family name", "Фамилия"),
        claim("given_name", "Given name", "Имя"),
        claim("middle_name", "Middle name", "Отчество"),
        claim("nickname", "Casual name", "Псевдоним"),
        claim("preferred_username", "Preferred username", "Предпочитаемое имя пользователя"),
        claim("profile", "Profile page URL", "URL страницы профиля"),
        claim("picture", "Profile picture URL", "URL изображения профиля"),
        claim("website", "Web page or blog URL", "URL веб-сайта или блога"),
        claim("gender", "Gender", "Пол"),
        claim("birthdate", "Date of birth", "Дата рождения"),
        claim("zoneinfo", "Time zone", "Часовой пояс"),
        claim("locale", "Locale", "Локаль"),
        claim("updated_at", "Time the profile was last updated", "Время последнего обновления профиля"),
      ),
    ),
    ScopeSeed(
      ScopeToken("email"),
      localized("Email address", "Адрес электронной почты"),
      List(
        claim("email", "Email address", "Адрес электронной почты"),
        claim("email_verified", "Email address verification status", "Статус подтверждения адреса электронной почты"),
      ),
    ),
    ScopeSeed(
      ScopeToken("address"),
      localized("Postal address", "Почтовый адрес"),
      List(claim("address", "Postal address", "Почтовый адрес")),
    ),
    ScopeSeed(
      ScopeToken("phone"),
      localized("Phone number", "Номер телефона"),
      List(
        claim("phone_number", "Phone number", "Номер телефона"),
        claim("phone_number_verified", "Phone number verification status", "Статус подтверждения номера телефона"),
      ),
    ),
    ScopeSeed(
      ScopeToken("offline_access"),
      localized("Offline access via refresh tokens", "Офлайн-доступ через refresh-токены"),
      List.empty,
    ),
  )

  /** Scopes granted to the central admin client. */
  private val clientScopes: Set[ScopeToken] =
    List("openid", "profile", "email", "offline_access").map(ScopeToken(_)).toSet

  /** Default OTP message template referenced by the bootstrapped clients. */
  private val defaultOtpTemplateId = "default"
  private val defaultOtpTemplate: Map[String, String] =
    localized(
      "You are entering Versola. Your verification code is: {{code}}",
      "Вы входите в Versola. Ваш код подтверждения: {{code}}",
    )

  /** Default authentication challenge settings seeded for the default tenant. */
  private def defaultChallengeSettings(tenantId: TenantId): ChallengeSettingsRecord =
    ChallengeSettingsRecord(
      tenantId = tenantId,
      allowedPrefixes = List.empty,
      passwordRegex = Some("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{8,}$"),
      submissionLimits = SubmissionLimits(
        otpRequest = List(RateLimit(2, 60), RateLimit(5, 3600)),
        otpSubmit = List(RateLimit(3, 120), RateLimit(5, 3600)),
        passwordSubmit = List(RateLimit(5, 900), RateLimit(10, 3600)),
        //passkeyAssertion = List(RateLimit(5, 300), RateLimit(10, 3600)),
        banDurationSeconds = 1800,
      ),
      otpLength = 6,
      otpResendAfter = 60,
      passkeySettings = PasskeySettings(
        rpId = "localhost",
        rpName = "Versola",
        origins = List("http://localhost:3000"),
        userVerification = "preferred",
      ),
      passwordHistorySize = 5,
      passwordNumDifferent = 3,
      authConversationTtlSeconds = 900,
      sessionTtlSeconds = 86400,
      sessionIdleTtlSeconds = None,
      ipHeader = "X-Real-IP",
    )

  /** Default theme seeded from the shared CSS resource. */
  private val defaultThemeId = "default"

  /** Default locales seeded for the console. */
  private val defaultLocales: Vector[LocaleRecord] = Vector(
    LocaleRecord("en", "English", isDefault = true, active = true),
    LocaleRecord("ru", "Russian", isDefault = false, active = true),
  )

  /** Default forms loaded from classpath resources, with their backend properties. */
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
    "passkey-enroll" -> Vector.empty,
  )

  private def readResource(path: String): Task[String] =
    ZIO.blocking:
      ZIO.attemptBlocking:
        val source = Source.fromResource(path)
        try source.mkString finally source.close()

  val live: ZLayer[
    TenantRepository & PermissionRepository & OAuthScopeRepository & RoleRepository & OtpChallengeRepository & ChallengeSettingsRepository & ThemeRepository & LocaleRepository & FormRepository & OAuthClientService & AuthorizationPresetRepository & EdgeRepository & JwksRepository & JwksService & UserRepository & CentralConfig,
    Throwable,
    BootstrapService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)) >+>
      ZLayer(ZIO.serviceWithZIO[BootstrapService](_.bootstrap))

  private final class Impl(
      tenantRepo: TenantRepository,
      permissionRepo: PermissionRepository,
      scopeRepo: OAuthScopeRepository,
      roleRepo: RoleRepository,
      otpTemplateRepo: OtpChallengeRepository,
      challengeSettingsRepo: ChallengeSettingsRepository,
      themeRepo: ThemeRepository,
      localeRepo: LocaleRepository,
      formRepo: FormRepository,
      clientService: OAuthClientService,
      presetRepo: AuthorizationPresetRepository,
      edgeRepo: EdgeRepository,
      jwksRepo: JwksRepository,
      jwksService: JwksService,
      userRepo: UserRepository,
      config: CentralConfig,
  ) extends BootstrapService:

    def bootstrap: Task[Unit] =
      ZIO.foreach(config.bootstrap) { config =>
        val tenantId = CentralConfig.defaultTenantId
        for
          tenants <- tenantRepo.getAll
          _ <- ZIO.unless(tenants.exists(_.id == tenantId)):
            tenantRepo.createTenant(tenantId, "Default", None)
          _ <- seedPermissions(tenantId)
          _ <- seedScopes(tenantId)
          _ <- seedRoles(tenantId)
          _ <- seedOtpTemplate(tenantId)
          _ <- seedChallengeSettings(tenantId)
          _ <- seedTheme()
          _ <- seedLocales()
          _ <- seedForms()
          _ <- seedAdminUser(config)
          _ <- seedClient(config)
          _ <- seedPresets(config)
          _ <- seedEdges(config)
          _ <- linkTenantEdge(tenantId, config)
          _ <- seedJwks(config)
          _ <- jwksService.sync()
        yield ()
      }.unit

    private def seedPermissions(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(permissionCatalog): (perm, desc) =>
        permissionRepo.findPermission(Some(tenantId), perm).flatMap:
          case Some(_) => ZIO.unit
          case None    => permissionRepo.createPermission(Some(tenantId), perm, desc, Set.empty)

    private def seedScopes(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(scopeCatalog): scope =>
        scopeRepo.findScope(tenantId, scope.token).flatMap:
          case Some(_) => ZIO.unit
          case None    => scopeRepo.createScope(tenantId, scope.token, scope.description, scope.claims)

    private def seedRoles(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(roleCatalog): (roleId, desc, perms) =>
        roleRepo.findRole(tenantId, roleId).flatMap:
          case Some(_) => ZIO.unit
          case None    => roleRepo.createRole(tenantId, roleId, desc, perms)

    private def seedOtpTemplate(tenantId: TenantId): Task[Unit] =
      otpTemplateRepo.find(defaultOtpTemplateId, tenantId).flatMap:
        case Some(_) => ZIO.unit
        case None    => otpTemplateRepo.upsertTemplate(OtpTemplateRecord(defaultOtpTemplateId, tenantId, defaultOtpTemplate))

    private def seedChallengeSettings(tenantId: TenantId): Task[Unit] =
      challengeSettingsRepo.findByTenant(tenantId).flatMap:
        case Some(_) => ZIO.unit
        case None    => challengeSettingsRepo.upsert(defaultChallengeSettings(tenantId))

    private def seedTheme(): Task[Unit] =
      for
        _      <- ZIO.logInfo("Seeding default theme from resources...")
        themes <- themeRepo.getAll
        _ <- ZIO.unless(themes.exists(_.id == defaultThemeId)):
          readResource("forms/common.css").flatMap: css =>
            themeRepo.create(ThemeRecord(defaultThemeId, css, None))
      yield ()

    private def seedLocales(): Task[Unit] =
      for
        _        <- ZIO.logInfo("Seeding default locales...")
        existing <- localeRepo.getAll.map(_.map(_.code).toSet)
        missing = defaultLocales.filterNot(locale => existing.contains(locale.code))
        _ <- ZIO.unless(missing.isEmpty):
          localeRepo.update(add = missing, delete = Vector.empty)
      yield ()

    private def seedForms(): Task[Unit] =
      for
        _           <- ZIO.logInfo("Seeding default forms from resources...")
        existingIds <- formRepo.getAll.map(_.map(_.id).toSet)
        _ <- ZIO.foreachDiscard(defaultForms.filterNot((formId, _) => existingIds.contains(FormId(formId)))): (formId, properties) =>
          (for
            jsSource   <- readResource(s"forms/$formId.tsx")
            jsCompiled <- readResource(s"forms/$formId.js")
            style      <- readResource(s"forms/$formId.css")
            i18nJson   <- readResource(s"forms/$formId.i18n.json")
            localizations <- ZIO.fromEither(i18nJson.fromJson[Map[String, Map[String, String]]])
              .mapError(message => new RuntimeException(s"Invalid i18n for form $formId: $message"))
            _ <- formRepo.upsertForm(FormId(formId), style, Some(jsSource), Some(jsCompiled), localizations, properties, activate = true)
          yield ()).catchAll(error => ZIO.logError(s"Failed to seed form $formId: ${error.getMessage}"))
      yield ()

    private def seedAdminUser(config: CentralConfig.BootstrapConfig): Task[Unit] =
      val adminUserId = UserId(config.adminUserId)
      userRepo.findById(adminUserId).flatMap:
        case Some(_) => ZIO.logInfo(s"Admin user '${config.login}' already exists in user index, skipping")
        case None =>
          userRepo
            .create(adminUserId, email = None, phone = None, login = Some(Login(config.login)))
            .foldZIO(
              {
                case _: UserConflict => ZIO.logInfo(s"Admin user '${config.login}' already exists in user index (conflict), skipping")
                case t: Throwable    => ZIO.fail(t)
              },
              _ => ZIO.logInfo(s"Seeded admin user '${config.login}' with id $adminUserId in user index"),
            )

    private def seedClient(config: CentralConfig.BootstrapConfig): Task[Unit] =
      val redirectUris = config.redirectUris.map(RedirectUri(_)).toSet
      val authFlow = AuthFlow(
        primary = PrimaryAuthFlow(
          credentials = List(PrimaryCredential.login),
          inlinePassword = true,
          factors = List.empty,
        ),
        passkey = None,
        equivalents = Map.empty,
      )
      val request = CreateClientRequest(
        tenantId       = CentralConfig.defaultTenantId,
        id             = CentralConfig.centralClientId,
        clientName     = "Central Admin",
        redirectUris   = redirectUris,
        allowedScopes  = clientScopes,
        audience       = List.empty,
        permissions    = Set.empty,
        accessTokenTtl = 3600,
        refreshTokenTtl = None,
        theme          = "default",
        authFlow       = Some(authFlow),
        otpTemplateId  = "default",
      )
      clientService.registerClient(request).foldZIO(
        {
          case _: ClientAlreadyExists => ZIO.unit
          case e: Throwable           => ZIO.fail(e)
        },
        _ => ZIO.unit,
      )

    private def seedPresets(config: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(config.presets.getOrElse(Nil)): seed =>
        presetRepo.find(PresetId(seed.id)).flatMap:
          case Some(_) => ZIO.unit
          case None    =>
            val preset = AuthorizationPreset(
              id                   = PresetId(seed.id),
              clientId             = CentralConfig.centralClientId,
              description          = seed.description,
              redirectUri          = RedirectUri(seed.redirectUri),
              postLoginRedirectUri = RedirectUri(seed.postLoginRedirectUri),
              scope                = clientScopes,
              responseType         = ResponseType.Code,
              uiLocales            = None,
              customParameters     = Map.empty,
              cookieDomain         = None,
              cookiePath           = None,
            )
            presetRepo.replace(CentralConfig.centralClientId, Seq(preset))

    private def seedEdges(config: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(config.edges.getOrElse(Nil)): seed =>
        edgeRepo.find(seed.id).flatMap:
          case Some(_) => ZIO.unit
          case None    => edgeRepo.createEdge(seed.id, seed.publicKeyJwk)

    /** Links the default tenant to the first seeded edge so the central-admin
      * client (and its presets) are synced to that edge. Only applied when the
      * tenant has no edge yet, to preserve manual assignments on re-runs.
      */
    private def linkTenantEdge(tenantId: TenantId, config: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(config.edges.getOrElse(Nil).headOption): edge =>
        tenantRepo.getAll.flatMap: tenants =>
          tenants.find(_.id == tenantId) match
            case Some(tenant) if tenant.edgeId.isEmpty =>
              tenantRepo.updateTenant(tenant.id, tenant.description, Some(edge.id))
            case _ => ZIO.unit

    private def seedJwks(config: CentralConfig.BootstrapConfig): Task[Unit] =
      val keys: Vector[Json.Obj] = config.jwks match
        case Some(set) =>
          set.fields
            .collectFirst { case ("keys", Json.Arr(elements)) => elements }
            .getOrElse(zio.Chunk.empty)
            .collect { case obj: Json.Obj => obj }
            .toVector
        case None => Vector.empty
      ZIO.foreachDiscard(keys): jwk =>
        jwk.fields.collectFirst { case ("kid", Json.Str(kid)) => kid } match
          case Some(kid) =>
            jwksRepo.find(kid).flatMap:
              case Some(_) => ZIO.unit
              case None    => jwksRepo.create(kid, jwk)
          case None =>
            ZIO.logWarning("Skipping bootstrap JWK without a 'kid' field")
