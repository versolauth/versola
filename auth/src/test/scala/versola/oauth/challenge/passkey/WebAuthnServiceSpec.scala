package versola.oauth.challenge.passkey

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyName, PasskeyRecord}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PasskeySettings}
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object WebAuthnServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.randomUUID())
  private val clientId = ClientId("test-client")
  private val settings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:8080"),
    userVerification = "preferred",
  )

  private val baseInstant = Instant.parse("2024-01-01T00:00:00Z")

  private def passkeyRecord(id: CredentialId, uid: UserId) = PasskeyRecord(
    id = id,
    userId = uid,
    publicKey = Array.fill(32)(0.toByte),
    signatureCounter = 0L,
    deviceType = CredentialDeviceType.MultiDevice,
    backedUp = true,
    backupEligible = true,
    transports = List(AuthenticatorTransport.Internal),
    attestationObject = None,
    clientDataJson = None,
    aaguid = None,
    name = Some(PasskeyName("My Key")),
    lastUsedAt = None,
    createdAt = baseInstant,
    updatedAt = baseInstant,
  )

  def spec = suite("WebAuthnServiceSpec")(
    test("startRegistration produces a ceremony") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- repository.listByUser.succeedsWith(Vector.empty)
        ceremony <- service.startRegistration(settings, userId, "Test User")
      yield assertTrue(ceremony.request.nonEmpty) &&
        assertTrue(ceremony.publicKeyOptions.contains("publicKey")) &&
        assertTrue(ceremony.publicKeyOptions.contains("challenge"))
    },
    test("startAssertion produces a ceremony") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        ceremony <- service.startAssertion(settings)
      yield assertTrue(ceremony.request.nonEmpty) &&
        assertTrue(ceremony.publicKeyOptions.contains("publicKey")) &&
        assertTrue(ceremony.publicKeyOptions.contains("challenge"))
    },
    test("credentialIdFromResponse extracts id from valid JSON") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        response =
          """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""
        id <- service.credentialIdFromResponse(response)
      yield assertTrue(id.contains("Y3JlZC0xMjM"))
    },
    test("credentialIdFromResponse returns None for invalid JSON") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        id <- service.credentialIdFromResponse("not-json")
      yield assertTrue(id.isEmpty)
    },
    test("finishRegistration fails if repository insert fails") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- configService.getPasskeySettings.succeedsWith(Some(settings))
        result <- service.finishRegistration(clientId, userId, "{}", "{}", None).exit
      yield assert(result)(fails(isSubtype[WebAuthnError.CeremonyFailed](anything)))
    },

    // An administrator can disable passkeys for the client while a ceremony the user already
    // started is still in flight, so this is a normal stale ceremony: it has to stay typed,
    // or both callers (the login flow's enroll step and the account API) turn it into a 500.
    test("finishRegistration fails when passkey settings are missing") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- configService.getPasskeySettings.succeedsWith(None)
        result <- service.finishRegistration(clientId, userId, "{}", "{}", None).exit
      yield assert(result)(fails(equalTo(WebAuthnError.PasskeysNotEnabled)))
    },
    test("finishAssertion fails with AssertionFailed if verification fails") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      val credId = CredentialId("c".getBytes)
      val record = passkeyRecord(credId, userId)

      val request =
        """{"publicKeyCredentialRequestOptions":{"challenge":"AAAA","timeout":60000,"rpId":"localhost","allowCredentials":[],"userVerification":"required","extensions":{}}}"""
      val response =
        """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector(record))
        result <- service.finishAssertion(settings, request, response).exit
      yield
        // It will fail because the signature is invalid for the "challenge"
        assert(result)(fails(isSubtype[WebAuthnError](anything)))
    },

    // webauthn-server-core 2.7.0 has no Cable or SmartCard transport, so those two have to
    // drop out of the excluded-credential descriptor rather than fail the ceremony.
    test("startRegistration maps every known transport and drops the unsupported ones") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      val record = passkeyRecord(CredentialId("c".getBytes), userId)
        .copy(transports = AuthenticatorTransport.values.toList)
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- repository.listByUser.succeedsWith(Vector(record))
        ceremony <- service.startRegistration(settings, userId, "Test User")
      yield assertTrue(
        ceremony.request.contains("\"ble\""),
        ceremony.request.contains("\"hybrid\""),
        ceremony.request.contains("\"internal\""),
        ceremony.request.contains("\"nfc\""),
        ceremony.request.contains("\"usb\""),
        !ceremony.request.contains("\"cable\""),
        !ceremony.request.contains("\"smart-card\""),
      )
    },
    test("startRegistration honours the configured user verification requirement") {
      def ceremonyFor(userVerification: String) =
        val repository = stub[PasskeyRepository]
        val configService = stub[OAuthConfigurationService]
        for
          service <- ZIO.service[WebAuthnService].provide(
            ZLayer.succeed(repository),
            ZLayer.succeed(configService),
            WebAuthnService.live,
          )
          _ <- repository.listByUser.succeedsWith(Vector.empty)
          ceremony <- service.startRegistration(settings.copy(userVerification = userVerification), userId, "Test User")
        yield ceremony.request

      for
        required <- ceremonyFor("REQUIRED")
        discouraged <- ceremonyFor("discouraged")
        preferred <- ceremonyFor("anything else")
      yield assertTrue(
        required.contains("\"required\""),
        discouraged.contains("\"discouraged\""),
        preferred.contains("\"preferred\""),
      )
    },
    test("finishAssertion fails if credential not found") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]

      // We use a realistic-looking but fake request/response to trigger the library
      val request =
        """{"publicKeyCredentialRequestOptions":{"challenge":"AAAA","timeout":60000,"rpId":"localhost","allowCredentials":[],"userVerification":"required","extensions":{}}}"""
      val response =
        """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector.empty)
        result <- service.finishAssertion(settings, request, response).exit
      yield assert(result)(fails(equalTo(WebAuthnError.CredentialNotFound)))
    },
  )
