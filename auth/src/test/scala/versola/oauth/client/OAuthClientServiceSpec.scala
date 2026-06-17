package versola.oauth.client

import org.apache.commons.codec.digest.Blake3
import versola.oauth.client.model.{Claim, ClaimRecord, ClientId, ClientsWithPepper, FormRecord, Locales, OAuthClientRecord, OtpTemplateRecord, PhoneSettingsRecord, ScopeRecord, ScopeToken, TenantId, ThemeRecord}
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.util.*
import zio.*
import zio.durationInt
import zio.prelude.{EqualOps, NonEmptySet}
import zio.test.*

object OAuthClientServiceSpec extends UnitSpecBase:
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val publicClientId = ClientId("public-client-1")
  val pepperBytes = Array.fill(16)(10.toByte)
  val testPepper = Secret(pepperBytes)
  val testSecret = Secret(Array.fill(32)(5.toByte))
  val previousClientSecret = Secret(Array.fill(32)(6.toByte))
  val wrongClientSecret = Secret(Array.fill(32)(99.toByte))
  val salt1 = Array.fill(16)(1.toByte)
  val salt2 = Array.fill(16)(2.toByte)

  private def stored(secret: Secret, salt: Array[Byte]) =
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(salt ++ pepperBytes).update(secret).doFinalize(mac)
    Secret(MAC(mac) ++ salt)

  val privateClient1 = OAuthClientRecord(
    id = clientId1,
    tenantId = TenantId("default"),
    clientName = "Private 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read"), ScopeToken("write")),
    externalAudience = Nil,
    secret = Some(stored(secret = testSecret, salt = salt1)),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default-otp",
  )
  val privateClient2 = OAuthClientRecord(
    id = clientId2,
    tenantId = TenantId("default"),
    clientName = "Private 2",
    redirectUris = NonEmptySet("https://example2.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = Some(stored(secret = testSecret, salt = salt2)),
    previousSecret = Some(stored(secret = previousClientSecret, salt = salt1)),
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default-otp",
  )
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    tenantId = TenantId("default"),
    clientName = "Public",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    otpTemplateId = "default-otp",
  )
  val testClients = Map(clientId1 -> privateClient1, clientId2 -> privateClient2, publicClientId -> publicClient)
  val testScopes = Vector(
    ScopeRecord(
      scope = ScopeToken("read"),
      claims = Vector(ClaimRecord(claim = Claim("sub")), ClaimRecord(claim = Claim("name"))),
    ),
    ScopeRecord(
      scope = ScopeToken("write"),
      claims = Vector(ClaimRecord(claim = Claim("email"))),
    ),
  )

  final class Env(
      clientCache: ReloadingCache[ClientsWithPepper],
      scopeCache: ReloadingCache[Vector[ScopeRecord]],
      formCache: ReloadingCache[Vector[FormRecord]],
      themeCache: ReloadingCache[Vector[ThemeRecord]],
      localeCache: ReloadingCache[Locales],
      otpTemplateCache: ReloadingCache[Vector[OtpTemplateRecord]],
      phoneSettingsCache: ReloadingCache[Vector[PhoneSettingsRecord]],
  ):
    val clientSync = stub[OAuthClientSyncClient]
    val scopeSync = stub[OAuthScopeSyncClient]
    val formSync = stub[FormSyncClient]
    val themeSync = stub[ThemeSyncClient]
    val localeSync = stub[LocaleSyncClient]
    val otpTemplateSync = stub[OtpTemplateSyncClient]
    val phoneSettingsSync = stub[PhoneSettingsSyncClient]
    val security = stub[SecurityService]
    security.mac.returns { (secret, key) =>
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key).update(secret).doFinalize(mac)
        MAC(mac)
    }
    val service: OAuthConfigurationService =
      OAuthConfigurationService.Impl(
        clientCache,
        clientSync,
        scopeCache,
        scopeSync,
        formCache,
        formSync,
        themeCache,
        themeSync,
        localeCache,
        localeSync,
        otpTemplateCache,
        otpTemplateSync,
        phoneSettingsCache,
        phoneSettingsSync,
        security,
      )

  private def makeEnv(
      clients: Map[ClientId, OAuthClientRecord] = testClients,
      scopes: Vector[ScopeRecord] = testScopes,
      forms: Vector[FormRecord] = Vector.empty,
      themes: Vector[ThemeRecord] = Vector.empty,
      locales: Locales = Locales(Vector.empty, "en"),
      otpTemplates: Vector[OtpTemplateRecord] = Vector.empty,
      phoneSettings: Vector[PhoneSettingsRecord] = Vector.empty,
  ) =
    for
      clientRef <- Ref.make(ClientsWithPepper(clients = clients, pepper = testPepper))
      scopeRef <- Ref.make(scopes)
      formRef <- Ref.make(forms)
      themeRef <- Ref.make(themes)
      localeRef <- Ref.make(locales)
      otpTemplateRef <- Ref.make(otpTemplates)
      phoneSettingsRef <- Ref.make(phoneSettings)
    yield Env(
      clientCache = ReloadingCache(clientRef),
      scopeCache = ReloadingCache(scopeRef),
      formCache = ReloadingCache(formRef),
      themeCache = ReloadingCache(themeRef),
      localeCache = ReloadingCache(localeRef),
      otpTemplateCache = ReloadingCache(otpTemplateRef),
      phoneSettingsCache = ReloadingCache(phoneSettingsRef),
    )

  val spec = suite("OAuthConfigurationService")(
    test("find returns existing client") {
      for
        env <- makeEnv()
        result <- env.service.find(clientId1)
      yield assertTrue(result === Some(privateClient1))
    },
    test("find returns None for missing client") {
      for
        env <- makeEnv()
        result <- env.service.find(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("verifySecret accepts public client only without secret") {
      for
        env <- makeEnv()
        ok <- env.service.verifySecret(publicClientId, None)
        bad <- env.service.verifySecret(publicClientId, Some(testSecret))
      yield assertTrue(ok === Some(publicClient), bad.isEmpty)
    },
    test("verifySecret accepts current and previous private secrets") {
      for
        env <- makeEnv()
        current <- env.service.verifySecret(clientId1, Some(testSecret))
        previous <- env.service.verifySecret(clientId2, Some(previousClientSecret))
      yield assertTrue(current === Some(privateClient1), previous === Some(privateClient2))
    },
    test("verifySecret rejects wrong or missing private secret") {
      for
        env <- makeEnv()
        wrong <- env.service.verifySecret(clientId1, Some(wrongClientSecret))
        missing <- env.service.verifySecret(clientId1, None)
      yield assertTrue(wrong.isEmpty, missing.isEmpty)
    },
    test("getScopes returns cached scope records") {
      for
        env <- makeEnv()
        result <- env.service.getScopes
      yield assertTrue(result == testScopes)
    },
    suite("getClientTemplate")(
      test("returns template body for preferred locale") {
        val template = OtpTemplateRecord(
          "default-otp",
          TenantId("default"),
          Map("en" -> "Your code is {{code}}", "ru" -> "Ваш код {{code}}"),
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, Some(List("ru")))
        yield assertTrue(result == OtpTemplate("Ваш код {{code}}"))
      },
      test("falls back to default locale when preferred locale is not in template") {
        val template = OtpTemplateRecord(
          "default-otp",
          TenantId("default"),
          Map("en" -> "Your code is {{code}}"),
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, Some(List("ru")))
        yield assertTrue(result == OtpTemplate("Your code is {{code}}"))
      },
      test("falls back to first available locale when no preferred or default matches") {
        val template = OtpTemplateRecord(
          "default-otp",
          TenantId("default"),
          Map("fr" -> "Votre code {{code}}"),
        )
        for
          env <- makeEnv(
            otpTemplates = Vector(template),
            locales = Locales(Vector.empty, "en"),
          )
          result <- env.service.getClientTemplate(clientId1, None)
        yield assertTrue(result == OtpTemplate("Votre code {{code}}"))
      },
      test("returns illegal state template when client is not found") {
        for
          env <- makeEnv(clients = Map.empty)
          result <- env.service.getClientTemplate(ClientId("missing"), None)
        yield assertTrue(result == OtpTemplate("{{code}}"))
      },
      test("returns illegal state template when no template found for client") {
        for
          env <- makeEnv(otpTemplates = Vector.empty)
          result <- env.service.getClientTemplate(clientId1, None)
        yield assertTrue(result == OtpTemplate("{{code}}"))
      },
    ),
  )
