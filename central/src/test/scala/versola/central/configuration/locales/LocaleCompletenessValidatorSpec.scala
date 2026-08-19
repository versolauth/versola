package versola.central.configuration.locales

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.challenges.*
import versola.central.configuration.clients.*
import versola.central.configuration.forms.*
import versola.central.configuration.scopes.*
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import zio.*
import zio.test.*

object LocaleCompletenessValidatorSpec extends ZIOSpecDefault, ZIOStubs:
  private val tenantId = TenantId("tenant-a")
  private val locale = "ru"

  private val client = OAuthClientRecord(
    id = ClientId("web"),
    tenantId = tenantId,
    clientName = Map("en" -> "Web"),
    redirectUris = Set(RedirectUri("https://example.com/callback")),
    scope = Set.empty,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 5.minutes,
    refreshTokenTtl = 30.days,
    permissions = Set.empty,
    theme = "",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
  )
  private val scope = ScopeRecord(
    tenantId,
    ScopeToken("profile"),
    Map("en" -> "Profile"),
    Vector(ClaimRecord(Claim("email"), Map("en" -> "Email"))),
  )
  private val activeForm = FormRecord(FormId("login"), 1, true, "", None, None, Map("en" -> Map("title" -> "Sign in")), Vector.empty)
  private val otp = OtpTemplateRecord("otp", tenantId, Map("en" -> "Code: {{code}}"), OtpTemplatePurpose.otp, OtpTemplateChannel.sms)

  private def missing(
      clients: Vector[OAuthClientRecord] = Vector.empty,
      scopes: Vector[ScopeRecord] = Vector.empty,
      forms: Vector[FormRecord] = Vector.empty,
      otpTemplates: Vector[OtpTemplateRecord] = Vector.empty,
  ): Task[Vector[String]] =
    val clientRepository = stub[OAuthClientRepository]
    val scopeRepository = stub[OAuthScopeRepository]
    val formRepository = stub[FormRepository]
    val otpRepository = stub[OtpChallengeRepository]
    for
      _ <- clientRepository.getAll.succeedsWith(clients)
      _ <- scopeRepository.getAll.succeedsWith(scopes)
      _ <- formRepository.getAll.succeedsWith(forms)
      _ <- otpRepository.getAll.succeedsWith(otpTemplates)
      validator <- ZIO.service[LocaleCompletenessValidator].provide(
        ZLayer.make[LocaleCompletenessValidator](
          LocaleCompletenessValidator.live,
          ZLayer.succeed(clientRepository),
          ZLayer.succeed(scopeRepository),
          ZLayer.succeed(formRepository),
          ZLayer.succeed(otpRepository),
        ),
      )
      result <- validator.missing(locale)
    yield result

  def spec = suite("LocaleCompletenessValidator")(
    test("reports missing localized values from every managed entity") {
      for result <- missing(
          clients = Vector(client),
          scopes = Vector(scope),
          forms = Vector(activeForm),
          otpTemplates = Vector(otp),
        )
      yield assertTrue(
        result == Vector(
          "client 'web' name",
          "scope 'profile' description",
          "scope 'profile' claim 'email' description",
          "form 'login' v1",
          "OTP template 'otp' (tenant-a)",
        ),
      )
    },
    test("treats blank values as missing") {
      for result <- missing(
          clients = Vector(client.copy(clientName = Map(locale -> " "))),
          forms = Vector(activeForm.copy(localizations = Map(locale -> Map("title" -> " ")))),
        )
      yield assertTrue(
        result == Vector("client 'web' name", "form 'login' v1 field 'title'"),
      )
    },
    test("returns no missing values when every localization is present") {
      for result <- missing(
          clients = Vector(client.copy(clientName = Map(locale -> "Веб"))),
          scopes = Vector(scope.copy(
            description = Map(locale -> "Профиль"),
            claims = Vector(scope.claims.head.copy(description = Map(locale -> "Электронная почта"))),
          )),
          forms = Vector(activeForm.copy(localizations = Map(locale -> Map("title" -> "Войти")))),
          otpTemplates = Vector(otp.copy(localizations = Map(locale -> "Код: {{code}}"))),
        )
      yield assertTrue(result.isEmpty)
    },
  )