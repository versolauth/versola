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

import java.time.Instant

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
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
    dpopBoundAccessTokens = false,
    dpopSigningAlgs = Set.empty,
    dpopMinRsaKeySize = None,
    authMethod = AuthMethod.client_secret,
    mtlsAuth = None,
    certificateBoundAccessTokens = false,
    jwks = None,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    edgeSigningKey = None,
    edgeClientCertificate = None,
    template = None,
    createdAt = Instant.EPOCH,
    themeOverrides = None,
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
      formTranslations: Vector[FormTranslationRecord] = Vector.empty,
      otpTemplates: Vector[OtpTemplateRecord] = Vector.empty,
  ): Task[Vector[String]] =
    val clientRepository = stub[OAuthClientRepository]
    val scopeRepository = stub[OAuthScopeRepository]
    val formRepository = stub[FormRepository]
    val translationRepository = stub[FormTranslationRepository]
    val otpRepository = stub[OtpChallengeRepository]
    for
      _ <- clientRepository.getAll.succeedsWith(clients)
      _ <- scopeRepository.getAll.succeedsWith(scopes)
      _ <- formRepository.getAll.succeedsWith(forms)
      _ <- translationRepository.getAll.succeedsWith(formTranslations)
      _ <- otpRepository.getAll.succeedsWith(otpTemplates)
      validator <- ZIO.service[LocaleCompletenessValidator].provide(
        ZLayer.make[LocaleCompletenessValidator](
          LocaleCompletenessValidator.live,
          ZLayer.succeed(clientRepository),
          ZLayer.succeed(scopeRepository),
          ZLayer.succeed(formRepository),
          ZLayer.succeed(translationRepository),
          ZLayer.succeed(otpRepository),
        ),
      )
      result <- validator.missing(locale)
    yield result

  private val twoKeys = activeForm.copy(localizations = Map("en" -> Map("title" -> "Sign in", "hint" -> "Code {code}")))
  private def stored(texts: Map[String, String]) = FormTranslationRecord(formId = FormId("login"), locale = locale, texts = texts)

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
    suite("form texts stored apart from the form")(
      test("count as the language's texts: a form with none of its own is complete once the store has every key") {
        for result <- missing(forms = Vector(twoKeys), formTranslations = Vector(stored(Map("title" -> "Войти", "hint" -> "Код {code}"))))
        yield assertTrue(result.isEmpty)
      },
      test("leave a key out of the store and the form is incomplete, naming the key") {
        for result <- missing(forms = Vector(twoKeys), formTranslations = Vector(stored(Map("title" -> "Войти"))))
        yield assertTrue(result == Vector("form 'login' v1 field 'hint'"))
      },
      test("complete the form's own partial texts") {
        val own = twoKeys.copy(localizations = twoKeys.localizations + (locale -> Map("title" -> "Войти")))
        for result <- missing(forms = Vector(own), formTranslations = Vector(stored(Map("hint" -> "Код {code}"))))
        yield assertTrue(result.isEmpty)
      },
      test("do not count a translation of another form, or into another language") {
        val elsewhere = Vector(
          FormTranslationRecord(formId = FormId("other"), locale = locale, texts = Map("title" -> "x", "hint" -> "y")),
          FormTranslationRecord(formId = FormId("login"), locale = "kk", texts = Map("title" -> "x", "hint" -> "y")),
        )
        for result <- missing(forms = Vector(twoKeys), formTranslations = elsewhere)
        yield assertTrue(result == Vector("form 'login' v1"))
      },
      test("a blank stored text is missing") {
        for result <- missing(forms = Vector(twoKeys), formTranslations = Vector(stored(Map("title" -> "Войти", "hint" -> " "))))
        yield assertTrue(result == Vector("form 'login' v1 field 'hint'"))
      },
    ),
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
