package versola

import versola.central.CentralConfig
import versola.central.configuration.challenges.{ChallengeSettingsRecord, ChallengeSettingsRepository, OtpChallengeRepository, OtpTemplateChannel, OtpTemplatePurpose, OtpTemplateRecord, PasskeySettings, RateLimit, SubmissionLimits}
import versola.central.configuration.system.{SystemSettingsRecord, SystemSettingsRepository}
import versola.central.configuration.clients.{AuthFactor, AuthFactorType, AuthFlow, AuthorizationPreset, AuthorizationPresetRepository, ClientAlreadyExists, ClientId, InvalidRegistrationConfiguration, OAuthClientService, OtpType, PasskeyAuthFlow, PresetId, PrimaryAuthFlow, PrimaryCredential, RegistrationFlow, ResponseType}
import versola.central.configuration.edges.{EdgeId, EdgeRepository}
import versola.central.configuration.forms.{BackendProperty, BooleanProperty, FormId, FormRepository, NumberProperty, StringArrayProperty}
import versola.central.configuration.jwks.JwksRepository
import versola.central.configuration.locales.{LocaleRecord, LocaleRepository}
import versola.central.configuration.permissions.{Permission, PermissionRepository}
import versola.central.configuration.resources.{ResourceEndpointId, ResourceEndpointRecord, ResourceId, ResourceRepository}
import versola.central.configuration.roles.{RoleId, RoleRepository}
import versola.central.configuration.scopes.{Claim, OAuthScopeRepository, ScopeToken}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.themes.{ThemeRecord, ThemeRepository}
import versola.central.configuration.{CreateClaim, CreateClientRequest, PatchClientRedirectUris, PatchClientScope, PatchPermissions, ResourceUri, UpdateClientRequest}
import versola.central.configuration.metadata.ServerMetadataRepository
import versola.central.users.{Login, UserConflict, UserId, UserRepository}
import versola.util.{EnvName, Patch, Phone, RedirectUri, Secret, SecureRandom, SecurityService}
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.{Task, UIO, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.io.Source

import javax.crypto.spec.SecretKeySpec

trait BootstrapService:
  def bootstrap: Task[Unit]

object BootstrapService:

  private val NonProdAdminPhone = Phone("+12025551234")

  private[versola] def adminPhone(envName: EnvName): Option[Phone] =
    Option.when(envName.isTest)(NonProdAdminPhone)

  private[versola] def adminAuthFactors(envName: EnvName): List[AuthFactor] =
    Option
      .when(envName.isTest)(AuthFactor(`type` = AuthFactorType.otp, required = true))
      .toList :+ AuthFactor(`type` = AuthFactorType.passkeyEnroll, required = true)

  private[versola] def adminAuthFlow(envName: EnvName): AuthFlow =
    AuthFlow(
      primary = PrimaryAuthFlow(
        credentials = List(PrimaryCredential.login),
        inlinePassword = true,
        factors = adminAuthFactors(envName),
      ),
      passkey = Some(PasskeyAuthFlow(factors = Nil)),
      equivalents = Map.empty,
      otpType = OtpType.sms,
    )

  private[versola] def resolveResourceSecret(
      configured: Option[Secret],
      secureRandom: SecureRandom,
  ): UIO[Secret] =
    configured.fold(secureRandom.nextBytes(32).map(Secret(_)))(ZIO.succeed(_))

  private def localized(en: String, ru: String): Map[String, String] =
    Map("en" -> en, "ru" -> ru)

  private def claim(id: String, en: String, ru: String): CreateClaim =
    CreateClaim(Claim(id), localized(en, ru))

  private case class ScopeSeed(
      token: ScopeToken,
      description: Map[String, String],
      claims: List[CreateClaim],
  )

  private def endpointId(method: String, path: String): ResourceEndpointId =
    ResourceEndpointId(UUID.nameUUIDFromBytes(s"$method $path".getBytes(StandardCharsets.UTF_8)))

  private[versola] val resourceManagementEndpointIds: Set[ResourceEndpointId] = Set(
    endpointId("POST", "/configuration/resources"),
    endpointId("PUT", "/configuration/resources"),
    endpointId("DELETE", "/configuration/resources"),
    endpointId("POST", "/configuration/resources/rotate-secret"),
    endpointId("DELETE", "/configuration/resources/previous-secret"),
  )

  private val permissionCatalog: List[(Permission, Map[String, String], Set[ResourceEndpointId])] = List(
    (Permission("oauth:read"), localized("View OAuth clients and scopes", "Просмотр OAuth клиентов и скоупов"), Set(
      endpointId("GET", "/configuration/clients"),
      endpointId("GET", "/configuration/scopes"),
      endpointId("GET", "/configuration/auth-request-presets"),
    )),
    (Permission("oauth:manage"), localized("Manage OAuth clients and scopes", "Управление OAuth клиентами и скоупами"), Set(
      endpointId("POST", "/configuration/clients"),
      endpointId("PUT", "/configuration/clients"),
      endpointId("DELETE", "/configuration/clients"),
      endpointId("POST", "/configuration/scopes"),
      endpointId("PUT", "/configuration/scopes"),
      endpointId("DELETE", "/configuration/scopes"),
      endpointId("POST", "/configuration/auth-request-presets"),
      endpointId("DELETE", "/configuration/auth-request-presets"),
    )),
    (Permission("oauth:secrets"), localized("View OAuth client secrets", "Просмотр секретов OAuth клиентов"), Set(
      endpointId("POST", "/configuration/clients/rotate-secret"),
      endpointId("DELETE", "/configuration/clients/previous-secret"),
    )),
    (Permission("access:read"), localized("View roles and permissions", "Просмотр ролей и прав"), Set(
      endpointId("GET", "/configuration/permissions"),
      endpointId("GET", "/configuration/roles"),
    )),
    (Permission("access:manage"), localized("Manage roles and permissions", "Управление ролями и правами"), Set(
      endpointId("POST", "/configuration/permissions"),
      endpointId("PUT", "/configuration/permissions"),
      endpointId("DELETE", "/configuration/permissions"),
      endpointId("POST", "/configuration/roles"),
      endpointId("PUT", "/configuration/roles"),
      endpointId("DELETE", "/configuration/roles"),
    )),
    (Permission("security:read"), localized("View security policies and challenges", "Просмотр политик безопасности"), Set(
      endpointId("GET", "/configuration/challenges/challenge-settings"),
      endpointId("GET", "/configuration/challenges/otp-templates"),
      endpointId("GET", "/configuration/authorization-detail-types"),
      endpointId("GET", "/configuration/jwks"),
      endpointId("GET", "/configuration/system-settings"),
    )),
    (Permission("security:manage"), localized("Manage security policies and challenges", "Управление политиками безопасности"), Set(
      endpointId("PUT", "/configuration/challenges/challenge-settings"),
      endpointId("PUT", "/configuration/challenges/otp-templates"),
      endpointId("DELETE", "/configuration/challenges/otp-templates"),
      endpointId("POST", "/configuration/authorization-detail-types"),
      endpointId("PUT", "/configuration/authorization-detail-types"),
      endpointId("DELETE", "/configuration/authorization-detail-types"),
      endpointId("POST", "/configuration/jwks"),
      endpointId("PUT", "/configuration/jwks"),
      endpointId("DELETE", "/configuration/jwks"),
      endpointId("PUT", "/configuration/system-settings"),
    )),
    (Permission("users:read"), localized("View users", "Просмотр пользователей"), Set(
      endpointId("GET", "/users"),
      endpointId("GET", "/users/passkeys"),
      endpointId("GET", "/users/roles"),
      endpointId("GET", "/users/sessions"),
    )),
    (Permission("users:manage"), localized("Manage users", "Управление пользователями"), Set(
      endpointId("POST", "/users"),
      endpointId("PATCH", "/users"),
      endpointId("PATCH", "/users/claims"),
      endpointId("PATCH", "/users/passkeys"),
      endpointId("DELETE", "/users/passkeys"),
      endpointId("PATCH", "/users/roles"),
      endpointId("DELETE", "/users/sessions"),
      endpointId("POST", "/users/limits/reset"),
      endpointId("POST", "/users/password/reset"),
    )),
    (Permission("resources:read"), localized("View protected resources", "Просмотр защищенных ресурсов"), Set(
      endpointId("GET", "/configuration/resources"),
    )),
    (Permission("resources:manage"), localized("Manage protected resources", "Управление защищенными ресурсами"), resourceManagementEndpointIds),
    (Permission("forms:read"), localized("View forms", "Просмотр форм"), Set(
      endpointId("GET", "/configuration/forms"),
    )),
    (Permission("forms:manage"), localized("Manage forms", "Управление формами"), Set(
      endpointId("PUT", "/configuration/forms"),
      endpointId("PUT", "/configuration/forms/active"),
    )),
    (Permission("locales:read"), localized("View locales", "Просмотр локалей"), Set(
      endpointId("GET", "/configuration/locales"),
    )),
    (Permission("locales:manage"), localized("Manage locales", "Управление локалями"), Set(
      endpointId("PUT", "/configuration/locales"),
      endpointId("PUT", "/configuration/locales/default"),
    )),
    (Permission("tenants:read"), localized("View tenants", "Просмотр тенантов"), Set(
      endpointId("GET", "/configuration/tenants"),
      endpointId("GET", "/configuration/themes"),
    )),
    (Permission("tenants:manage"), localized("Manage tenants", "Управление тенантами"), Set(
      endpointId("POST", "/configuration/tenants"),
      endpointId("PUT", "/configuration/tenants"),
      endpointId("DELETE", "/configuration/tenants"),
      endpointId("POST", "/configuration/themes"),
      endpointId("PUT", "/configuration/themes"),
      endpointId("DELETE", "/configuration/themes"),
    )),
    (Permission("edges:read"), localized("View edges", "Просмотр эджей"), Set(
      endpointId("GET", "/configuration/edges"),
    )),
    (Permission("edges:manage"), localized("Manage edges", "Управление эджами"), Set(
      endpointId("POST", "/configuration/edges"),
      endpointId("DELETE", "/configuration/edges"),
      endpointId("POST", "/configuration/edges/rotate-key"),
      endpointId("DELETE", "/configuration/edges/old-key"),
    )),
    (Permission("jwks:read"), localized("View JWKS and Server Metadata", "Просмотр JWKS и серверных метаданных"), Set(
      endpointId("GET", "/configuration/jwks"),
      endpointId("GET", "/configuration/server-metadata"),
    )),
    (Permission("jwks:manage"), localized("Manage JWKS and Server Metadata", "Управление JWKS и серверными метаданными"), Set(
      endpointId("POST", "/configuration/jwks"),
      endpointId("PUT", "/configuration/jwks"),
      endpointId("DELETE", "/configuration/jwks"),
      endpointId("POST", "/configuration/server-metadata"),
    )),
  )

  private val allPermissions: List[Permission] = permissionCatalog.map(_._1)

  private val roleCatalog: List[(RoleId, Map[String, String], List[Permission])] =
    val readOnly = allPermissions.filter(_.endsWith(":read"))
    List(
      (
        RoleId("oauth-admin"),
        localized("OAuth Administrator", "Администратор OAuth"),
        allPermissions,
      ),
      (
        RoleId("security"),
        localized("Security Officer", "Сотрудник безопасности (ИБ)"),
        List("oauth:read", "oauth:manage", "users:read", "users:manage", "access:read", "security:read", "resources:read").map(Permission(_)),
      ),
      (
        RoleId("support"),
        localized("Support Engineer", "Инженер поддержки"),
        List("users:read", "users:manage", "oauth:read", "access:read", "security:read", "resources:read").map(Permission(_)),
      ),
      (
        RoleId("frontend-developer"),
        localized("Frontend Developer", "Фронтенд-разработчик"),
        readOnly ++ List(Permission("forms:manage")),
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
    List[String](/*"openid", "profile", "email"*/).map(ScopeToken(_)).toSet

  /** Default OTP message template referenced by the bootstrapped clients. */
  private val defaultOtpTemplateId = "default"
  private val defaultOtpTemplate: Map[String, String] =
    localized(
      "You are entering Versola. Your verification code is: {{code}}",
      "Вы входите в Versola. Ваш код подтверждения: {{code}}",
    )
  private val defaultEmailOtpTemplateId = defaultOtpTemplateId
  private val defaultEmailOtpTemplate: Map[String, String] =
    localized(
      """|<!doctype html>
       |<html>
       |  <body style="margin:0;padding:20px;background:#f6f8fa;font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#15171c">
       |    <table role="presentation" style="width:100%;border-collapse:collapse">
       |      <tr>
       |        <td align="center">
       |          <table role="presentation" style="width:100%;max-width:540px;background:#fff;border:1px solid #e3e6eb;border-radius:24px">
       |            <tr>
       |              <td style="padding:48px 44px;text-align:center">
       |                <div style="font-size:20px;font-weight:700;margin-bottom:28px">Versola</div>
       |                <h1 style="font-size:26px;font-weight:600;margin:0 0 14px">Verify your identity</h1>
       |                <p style="font-size:16px;line-height:1.6;color:#6b7280;margin:0 0 24px">Your verification code is:</p>
       |                <div style="display:inline-block;padding:15px 24px;background:#f6f7f9;border:1px solid #e3e6eb;border-radius:14px;font-size:28px;font-weight:700;letter-spacing:8px;color:#15171c">{{code}}</div>
       |              </td>
       |            </tr>
       |          </table>
       |        </td>
       |      </tr>
       |    </table>
       |  </body>
       |</html>""".stripMargin,
      """|<!doctype html>
       |<html>
       |  <body style="margin:0;padding:20px;background:#f6f8fa;font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#15171c">
       |    <table role="presentation" style="width:100%;border-collapse:collapse">
       |      <tr>
       |        <td align="center">
       |          <table role="presentation" style="width:100%;max-width:540px;background:#fff;border:1px solid #e3e6eb;border-radius:24px">
       |            <tr>
       |              <td style="padding:48px 44px;text-align:center">
       |                <div style="font-size:20px;font-weight:700;margin-bottom:28px">Versola</div>
       |                <h1 style="font-size:26px;font-weight:600;margin:0 0 14px">Подтвердите личность</h1>
       |                <p style="font-size:16px;line-height:1.6;color:#6b7280;margin:0 0 24px">Ваш код подтверждения:</p>
       |                <div style="display:inline-block;padding:15px 24px;background:#f6f7f9;border:1px solid #e3e6eb;border-radius:14px;font-size:28px;font-weight:700;letter-spacing:8px;color:#15171c">{{code}}</div>
       |              </td>
       |            </tr>
       |          </table>
       |        </td>
       |      </tr>
       |    </table>
       |  </body>
       |</html>""".stripMargin,
    )

  /** Default per-tenant template used to deliver an admin-issued temporary password. */
  private val passwordTemplateId = defaultOtpTemplateId
  private val defaultPasswordSmsTemplate: Map[String, String] =
    localized(
      "Your temporary password is {{password}}. It expires in {{expiresHours}} hours.",
      "Ваш временный пароль: {{password}}. Он истекает через {{expiresHours}} часов.",
    )
  private val defaultPasswordTemplate: Map[String, String] =
    localized(
      """|<!doctype html>
       |<html>
       |  <body style="margin:0;padding:20px;background:#f6f8fa;font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#15171c">
       |    <table role="presentation" style="width:100%;border-collapse:collapse">
       |      <tr>
       |        <td align="center">
       |          <table role="presentation" style="width:100%;max-width:540px;background:#fff;border:1px solid #e3e6eb;border-radius:24px">
       |            <tr>
       |              <td style="padding:48px 44px;text-align:center">
       |                <div style="font-size:20px;font-weight:700;margin-bottom:28px">Versola</div>
       |                <h1 style="font-size:26px;font-weight:600;margin:0 0 14px">Your temporary password</h1>
       |                <p style="font-size:16px;line-height:1.6;color:#6b7280;margin:0 0 24px">Use this password to sign in:</p>
       |                <div style="display:inline-block;padding:15px 24px;background:#f6f7f9;border:1px solid #e3e6eb;border-radius:14px;font-size:20px;font-weight:700;color:#15171c">{{password}}</div>
       |                <p style="font-size:14px;line-height:1.6;color:#6b7280;margin:24px 0 0">It expires in {{expiresHours}} hours.</p>
       |              </td>
       |            </tr>
       |          </table>
       |        </td>
       |      </tr>
       |    </table>
       |  </body>
       |</html>""".stripMargin,
      """|<!doctype html>
       |<html>
       |  <body style="margin:0;padding:20px;background:#f6f8fa;font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#15171c">
       |    <table role="presentation" style="width:100%;border-collapse:collapse">
       |      <tr>
       |        <td align="center">
       |          <table role="presentation" style="width:100%;max-width:540px;background:#fff;border:1px solid #e3e6eb;border-radius:24px">
       |            <tr>
       |              <td style="padding:48px 44px;text-align:center">
       |                <div style="font-size:20px;font-weight:700;margin-bottom:28px">Versola</div>
       |                <h1 style="font-size:26px;font-weight:600;margin:0 0 14px">Ваш временный пароль</h1>
       |                <p style="font-size:16px;line-height:1.6;color:#6b7280;margin:0 0 24px">Используйте этот пароль для входа:</p>
       |                <div style="display:inline-block;padding:15px 24px;background:#f6f7f9;border:1px solid #e3e6eb;border-radius:14px;font-size:20px;font-weight:700;color:#15171c">{{password}}</div>
       |                <p style="font-size:14px;line-height:1.6;color:#6b7280;margin:24px 0 0">Он истекает через {{expiresHours}} часов.</p>
       |              </td>
       |            </tr>
       |          </table>
       |        </td>
       |      </tr>
       |    </table>
       |  </body>
       |</html>""".stripMargin,
    )

  /** Default authentication challenge settings seeded for the default tenant. */
  private def defaultChallengeSettings(
      tenantId: TenantId,
      passkeyConfig: CentralConfig.PasskeyConfig,
  ): ChallengeSettingsRecord =
    ChallengeSettingsRecord(
      tenantId = tenantId,
      allowedPrefixes = List.empty,
      submissionLimits = SubmissionLimits(
        otpRequest = List(RateLimit(2, 60), RateLimit(5, 3600)),
        otpSubmit = List(RateLimit(3, 120), RateLimit(5, 3600)),
        passwordSubmit = List(RateLimit(5, 900), RateLimit(10, 3600)),
        passkeyAssertion = List(RateLimit(5, 300), RateLimit(10, 3600)),
        banDurationSeconds = 1800,
      ),
      otpLength = 6,
      otpResendAfter = 60,
        temporaryPasswordTtlSeconds = ChallengeSettingsRecord.DefaultTemporaryPasswordTtlSeconds,
      passkeySettings = PasskeySettings(
        rpId = passkeyConfig.rpId,
        rpName = "Versola",
        origins = passkeyConfig.origins,
        userVerification = "preferred",
      ),
      authConversationTtlSeconds = 900,
      sessionTtlSeconds = 86400,
      sessionIdleTtlSeconds = None,
      userAgentTtlSeconds = 15552000,
      ipHeader = "X-Real-IP",
      acrVocabulary = None,
      postLogoutRedirectUris = List("https://id.versola.kz/central/admin/"),
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
    "set-password" -> Vector.empty,
    "access-denied" -> Vector.empty,
    "conversation-expired" -> Vector.empty,
    "service-unavailable" -> Vector.empty,
    "passkey-enroll" -> Vector.empty,
    "confirm-logout" -> Vector.empty,
    "signed-out" -> Vector.empty,
  )

  /** Hard-coded resourceId for the edge-facing resource that proxies central's admin API. */
  private val centralResourceId = ResourceId("central")
  /** Admin API surface exposed to the console through the edge proxy. Each
    * (method, path) is registered as a resource endpoint; the edge validates the
    * caller's session token and performs the real per-user authorization
    * (deny-by-default, against synced role/permission mappings), then forwards the
    * request to central using HTTP Basic auth with the shared central-admin client
    * secret. The user's access token is never forwarded; central only verifies the
    * shared secret (see authorizeBasic).
    */
  private val centralEndpointCatalog: List[(String, String)] = List(
    "GET"    -> "/configuration/auth-request-presets",
    "POST"   -> "/configuration/auth-request-presets",
    "GET"    -> "/configuration/challenges/challenge-settings",
    "PUT"    -> "/configuration/challenges/challenge-settings",
    "GET"    -> "/configuration/system-settings",
    "PUT"    -> "/configuration/system-settings",
    "GET"    -> "/configuration/challenges/otp-templates",
    "PUT"    -> "/configuration/challenges/otp-templates",
    "DELETE" -> "/configuration/challenges/otp-templates",
    "GET"    -> "/configuration/authorization-detail-types",
    "POST"   -> "/configuration/authorization-detail-types",
    "PUT"    -> "/configuration/authorization-detail-types",
    "DELETE" -> "/configuration/authorization-detail-types",
    "GET"    -> "/configuration/clients",
    "POST"   -> "/configuration/clients",
    "PUT"    -> "/configuration/clients",
    "DELETE" -> "/configuration/clients",
    "POST"   -> "/configuration/clients/rotate-secret",
    "DELETE" -> "/configuration/clients/previous-secret",
    "GET"    -> "/configuration/edges",
    "POST"   -> "/configuration/edges",
    "DELETE" -> "/configuration/edges",
    "POST"   -> "/configuration/edges/rotate-key",
    "DELETE" -> "/configuration/edges/old-key",
    "GET"    -> "/configuration/forms",
    "PUT"    -> "/configuration/forms",
    "PUT"    -> "/configuration/forms/active",
    "GET"    -> "/configuration/jwks",
    "POST"   -> "/configuration/jwks",
    "PUT"    -> "/configuration/jwks",
    "DELETE" -> "/configuration/jwks",
    "GET"    -> "/configuration/server-metadata",
    "POST"   -> "/configuration/server-metadata",
    "GET"    -> "/configuration/locales",
    "PUT"    -> "/configuration/locales",
    "PUT"    -> "/configuration/locales/default",
    "GET"    -> "/configuration/permissions",
    "POST"   -> "/configuration/permissions",
    "PUT"    -> "/configuration/permissions",
    "DELETE" -> "/configuration/permissions",
    "GET"    -> "/configuration/resources",
    "POST"   -> "/configuration/resources",
    "PUT"    -> "/configuration/resources",
    "DELETE" -> "/configuration/resources",
    "POST"   -> "/configuration/resources/rotate-secret",
    "DELETE" -> "/configuration/resources/previous-secret",
    "GET"    -> "/configuration/roles",
    "POST"   -> "/configuration/roles",
    "PUT"    -> "/configuration/roles",
    "DELETE" -> "/configuration/roles",
    "GET"    -> "/configuration/scopes",
    "POST"   -> "/configuration/scopes",
    "PUT"    -> "/configuration/scopes",
    "DELETE" -> "/configuration/scopes",
    "GET"    -> "/configuration/tenants",
    "POST"   -> "/configuration/tenants",
    "PUT"    -> "/configuration/tenants",
    "DELETE" -> "/configuration/tenants",
    "GET"    -> "/configuration/themes",
    "POST"   -> "/configuration/themes",
    "PUT"    -> "/configuration/themes",
    "DELETE" -> "/configuration/themes",
    "GET"    -> "/users",
    "POST"   -> "/users",
    "PATCH"  -> "/users",
    "PATCH"  -> "/users/claims",
    "GET"    -> "/users/passkeys",
    "PATCH"  -> "/users/passkeys",
    "DELETE" -> "/users/passkeys",
    "GET"    -> "/users/roles",
    "PATCH"  -> "/users/roles",
    "GET"    -> "/users/sessions",
    "DELETE" -> "/users/sessions",
    "POST"   -> "/users/limits/reset",
    "POST"   -> "/users/password/reset",
  )

  private def readResource(path: String): Task[String] =
    ZIO.blocking:
      ZIO.attemptBlocking:
        val source = Source.fromResource(path)
        try source.mkString finally source.close()

  val live: ZLayer[
    TenantRepository & PermissionRepository & OAuthScopeRepository & RoleRepository & OtpChallengeRepository & ChallengeSettingsRepository & SystemSettingsRepository & ThemeRepository & LocaleRepository & FormRepository & OAuthClientService & AuthorizationPresetRepository & EdgeRepository & ResourceRepository & JwksRepository & ServerMetadataRepository & UserRepository & CentralConfig & SecurityService & SecureRandom & EnvName,
    Throwable,
    BootstrapService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)) >+>
      ZLayer(ZIO.serviceWithZIO[BootstrapService](_.bootstrap))

  private final class Impl(
      tenantRepo: TenantRepository,
      permissionRepo: PermissionRepository,
      scopeRepo: OAuthScopeRepository,
      roleRepo: RoleRepository,
      otpTemplateRepo: OtpChallengeRepository,
      challengeSettingsRepo: ChallengeSettingsRepository,
      systemSettingsRepo: SystemSettingsRepository,
      themeRepo: ThemeRepository,
      localeRepo: LocaleRepository,
      formRepo: FormRepository,
      clientService: OAuthClientService,
      presetRepo: AuthorizationPresetRepository,
      edgeRepo: EdgeRepository,
      resourceRepo: ResourceRepository,
      jwksRepo: JwksRepository,
      metadataRepo: ServerMetadataRepository,
      userRepo: UserRepository,
      config: CentralConfig,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      envName: EnvName,
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
          _ <- seedRegistrationRole(tenantId)
          _ <- seedOtpTemplates(tenantId)
          _ <- seedPasswordTemplate(tenantId)
          _ <- seedChallengeSettings(tenantId, config.passkey)
          _ <- seedSystemSettings()
          _ <- seedTheme()
          _ <- seedLocales()
          _ <- seedForms()
          _ <- seedAdminUser(config)
          _ <- seedClient(config)
          _ <- seedPresets(config)
          _ <- seedEdges(config)
          _ <- linkTenantEdge(tenantId, config)
          _ <- seedCentralResource(config)
          _ <- seedJwks(config)
          _ <- seedMetadata(config)
        yield ()
      }.unit

    private def seedPermissions(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(permissionCatalog): (perm, desc, endpoints) =>
        permissionRepo.upsertPermission(tenantId, perm, desc, endpoints)

    private def seedScopes(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(scopeCatalog): scope =>
        scopeRepo.findScope(tenantId, scope.token).flatMap:
          case Some(_) => ZIO.unit
          case None    => scopeRepo.createScope(tenantId, scope.token, scope.description, scope.claims)

    private def seedRoles(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(roleCatalog): (roleId, desc, perms) =>
        roleRepo.upsertRole(tenantId, roleId, desc, perms)

    /** The role granted to self-registered users. Seeded without permissions and never
      * overwritten, so permissions an administrator grants it later survive a restart.
      */
    private def seedRegistrationRole(tenantId: TenantId): Task[Unit] =
      roleRepo.findRole(tenantId, RegistrationFlow.defaultRoleId).flatMap:
        case Some(_) => ZIO.unit
        case None =>
          roleRepo.upsertRole(
            tenantId,
            RegistrationFlow.defaultRoleId,
            localized("User", "Пользователь"),
            List.empty,
          )

    private def seedOtpTemplates(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(
        List(
          (defaultOtpTemplateId, defaultOtpTemplate, OtpTemplateChannel.sms),
          (defaultEmailOtpTemplateId, defaultEmailOtpTemplate, OtpTemplateChannel.email),
        ),
      ): (id, localizations, channel) =>
        otpTemplateRepo.find(id, tenantId, OtpTemplatePurpose.otp, channel).flatMap:
          case Some(_) => ZIO.unit
          case None    => otpTemplateRepo.upsertTemplate(OtpTemplateRecord(id, tenantId, localizations, purpose = OtpTemplatePurpose.otp, channel = channel))

    private def seedPasswordTemplate(tenantId: TenantId): Task[Unit] =
      ZIO.foreachDiscard(
        List(
          (defaultPasswordSmsTemplate, OtpTemplateChannel.sms),
          (defaultPasswordTemplate, OtpTemplateChannel.email),
        ),
      ): (localizations, channel) =>
        otpTemplateRepo.find(passwordTemplateId, tenantId, OtpTemplatePurpose.password, channel).flatMap:
          case Some(_) => ZIO.unit
          case None =>
            otpTemplateRepo.upsertTemplate(
              OtpTemplateRecord(passwordTemplateId, tenantId, localizations, purpose = OtpTemplatePurpose.password, channel = channel),
            )

    private def seedChallengeSettings(tenantId: TenantId, passkeyConfig: CentralConfig.PasskeyConfig): Task[Unit] =
      challengeSettingsRepo.findByTenant(tenantId).flatMap:
        case Some(_) => ZIO.unit
        case None    => challengeSettingsRepo.upsert(defaultChallengeSettings(tenantId, passkeyConfig))

    private def seedSystemSettings(): Task[Unit] =
      systemSettingsRepo.getAll.unit.catchAll: _ =>
        systemSettingsRepo.upsert(SystemSettingsRecord.default)

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
        _      <- ZIO.logInfo("Seeding default forms from resources...")
        all    <- formRepo.getAll
        active = all.filter(_.active).map(form => form.id -> form).toMap
        _ <- ZIO.foreachDiscard(defaultForms): (formId, properties) =>
          val current = active.get(FormId(formId))
          (for
            jsSource   <- readResource(s"forms/$formId.tsx")
            jsCompiled <- readResource(s"forms/$formId.js")
            style      <- readResource(s"forms/$formId.css")
            i18nJson   <- readResource(s"forms/$formId.i18n.json")
            localizations <- ZIO.fromEither(i18nJson.fromJson[Map[String, Map[String, String]]])
              .mapError(message => new RuntimeException(s"Invalid i18n for form $formId: $message"))
            unchanged = current.exists(form =>
              form.style == style && form.jsSource.contains(jsSource) && form.localizations == localizations && form.properties == properties,
            )
            _ <- ZIO.unless(unchanged):
              formRepo.upsertForm(FormId(formId), style, Some(jsSource), Some(jsCompiled), localizations, properties, activate = true)
          yield ()).catchAll(error => ZIO.logError(s"Failed to seed form $formId: ${error.getMessage}"))
      yield ()

    private def seedAdminUser(config: CentralConfig.BootstrapConfig): Task[Unit] =
      val adminUserId = UserId(config.adminUserId)
      userRepo.findById(adminUserId).flatMap:
        case Some(_) => ZIO.logInfo(s"Admin user '${config.login}' already exists in user index, skipping")
        case None =>
          userRepo
            .create(adminUserId, email = None, phone = BootstrapService.adminPhone(envName), login = Some(Login(config.login)))
            .foldZIO(
              {
                case _: UserConflict => ZIO.logInfo(s"Admin user '${config.login}' already exists in user index (conflict), skipping")
                case t: Throwable    => ZIO.fail(t)
              },
              _ => ZIO.logInfo(s"Seeded admin user '${config.login}' with id $adminUserId in user index"),
            )

    private def seedClient(config: CentralConfig.BootstrapConfig): Task[Unit] =
      val redirectUris = config.redirectUris.map(RedirectUri(_)).toSet
      val authFlow = BootstrapService.adminAuthFlow(envName)
      val request = CreateClientRequest(
        tenantId       = CentralConfig.defaultTenantId,
        id             = CentralConfig.centralClientId,
        clientName     = "Central Admin",
        redirectUris   = redirectUris,
        allowedScopes  = clientScopes,
        permissions    = Set.empty,
        accessTokenTtl = 3600,
        refreshTokenTtl = None,
        theme          = "default",
        authFlow       = Some(authFlow),
        registrationFlow = None,
        otpTemplateId  = "default",
        frontChannelLogoutUri = config.frontChannelLogoutUri,
        frontChannelLogoutSessionRequired = true,
        backChannelLogoutUri = None,
      )
      clientService.registerClient(request).foldZIO(
        {
          case _: ClientAlreadyExists =>
            clientService.updateClient(
              UpdateClientRequest(
                clientId = CentralConfig.centralClientId,
                clientName = None,
                redirectUris = PatchClientRedirectUris(Set.empty, Set.empty),
                scope = PatchClientScope(Set.empty, Set.empty),
                permissions = PatchPermissions(Set.empty, Set.empty),
                accessTokenTtl = None,
                refreshTokenTtl = None,
                theme = None,
                authFlow = Some(Patch.Modified(authFlow)),
                registrationFlow = None,
                otpTemplateId = None,
                frontChannelLogoutUri = None,
                frontChannelLogoutSessionRequired = None,
                backChannelLogoutUri = None,
              ),
            ).mapError(registrationConfigurationError)
          case e: InvalidRegistrationConfiguration => ZIO.fail(registrationConfigurationError(e))
          case e: Throwable           => ZIO.fail(e)
        },
        _ => ZIO.unit,
      )

    private def registrationConfigurationError(error: InvalidRegistrationConfiguration | Throwable): Throwable =
      error match
        case e: InvalidRegistrationConfiguration =>
          new RuntimeException(s"Invalid registration configuration for central client: ${e.reason}")
        case e: Throwable => e

    private def seedPresets(config: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(config.presets.getOrElse(Nil)): seed =>
        for
          _ <- presetRepo.find(PresetId(seed.id)).flatMap:
            case Some(_) => ZIO.unit
            case None    =>
              val preset = AuthorizationPreset(
                id                   = PresetId(seed.id),
                clientId             = CentralConfig.centralClientId,
                description          = seed.description,
                redirectUri          = RedirectUri(seed.redirectUri),
                postLoginRedirectUri = RedirectUri(seed.postLoginRedirectUri),
                postLogoutRedirectUri = seed.postLogoutRedirectUri.map(RedirectUri(_)).orElse(Some(RedirectUri(seed.postLoginRedirectUri))),
                scope                = clientScopes,
                responseType         = ResponseType.Code,
                uiLocales            = None,
                customParameters     = Map.empty,
                cookieDomain         = seed.cookieDomain,
                cookiePath           = seed.cookiePath,
              )
              presetRepo.replace(CentralConfig.centralClientId, Seq(preset))
          _ <- ZIO.foreachDiscard(seed.postLogoutRedirectUri)(registerPostLogoutRedirectUri(CentralConfig.defaultTenantId, _))
        yield ()

    /** Ensures the given `postLogoutRedirectUri` is present in the tenant's
      * `ChallengeSettingsRecord.postLogoutRedirectUris` allow-list, so the auth server accepts it
      * during RP-initiated logout. Runs on every bootstrap (not just first-time preset creation)
      * so config changes converge even when the preset/settings already exist.
      */
    private def registerPostLogoutRedirectUri(tenantId: TenantId, uri: String): Task[Unit] =
      challengeSettingsRepo.findByTenant(tenantId).flatMap:
        case Some(settings) =>
          val merged = (settings.postLogoutRedirectUris :+ uri).distinct
          ZIO.when(merged != settings.postLogoutRedirectUris)(
            challengeSettingsRepo.upsert(settings.copy(postLogoutRedirectUris = merged)),
          ).unit
        case None => ZIO.unit

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

    /** Seeds the edge-facing resource that proxies the admin API back to central.
      * Created for the default tenant (linked to the bootstrap edge) so it syncs
      * to the edge. Skipped if a resource with the same resourceId already exists.
      *
      * Its configured secret is encrypted at rest with the shared
      * `clientSecretsSecret` AES key before it is persisted. Edge authenticates
      * to central with this resource secret instead of forwarding the caller's
      * access token.
      */
    private def seedCentralResource(bootstrapConfig: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(bootstrapConfig.centralUrl): url =>
        val tenantId = CentralConfig.defaultTenantId
        val allEndpoints = centralEndpointCatalog.map: (method, path) =>
          ResourceEndpointRecord(
            id = endpointId(method, path),
            path = path,
            method = method,
            fetchUserInfo = false,
            allowExpression = None,
            inject = Vector.empty,
            stepUpCondition = None,
            stepUpAcr = None,
            maxAge = None,
          )
        for
          resources <- resourceRepo.getAll
          _ <- resources.find(r => r.tenantId == tenantId && r.resourceId == centralResourceId) match
            case None =>
              for
                resourceSecret <- BootstrapService.resolveResourceSecret(bootstrapConfig.resourceSecret, secureRandom)
                encryptedSecret <- securityService.encryptAes256(resourceSecret, SecretKeySpec(this.config.clientSecretsSecret, "AES"))
                _ <- resourceRepo.createResource(
                  tenantId,
                  centralResourceId,
                  ResourceUri(url),
                  List(CentralConfig.centralClientId),
                  allEndpoints.toVector,
                  Some(encryptedSecret),
                )
              yield ()
            case Some(existing) =>
              val existingIds = existing.endpoints.map(_.id).toSet
              val missing = allEndpoints.filterNot(e => existingIds.contains(e.id))
              if missing.isEmpty then
                ZIO.logInfo(s"Central resource '$centralResourceId' endpoints are up to date, skipping")
              else
                ZIO.logInfo(s"Adding ${missing.size} missing endpoint(s) to central resource '$centralResourceId'") *>
                  resourceRepo.updateResource(centralResourceId, None, None, missing.toVector, Set.empty)

        yield ()

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

    private def seedMetadata(config: CentralConfig.BootstrapConfig): Task[Unit] =
      ZIO.foreachDiscard(config.metadata): metadata =>
        ZIO.logInfo("Seeding server metadata from bootstrap config...") *>
          metadataRepo.upsert(metadata)
