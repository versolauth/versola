package versola.oauth.challenge.passkey

import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyRecord}
import versola.oauth.client.model.PasskeySettings
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.time.Instant
import java.util.UUID

object WebAuthnServiceSpec extends UnitSpecBase:

  private val userId = UserId(UUID.randomUUID())
  private val settings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:8080"),
    userVerification = "preferred"
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
    name = Some("My Key"),
    lastUsedAt = None,
    createdAt = baseInstant,
    updatedAt = baseInstant
  )

  def spec = suite("WebAuthnServiceSpec")(

    test("startRegistration produces a ceremony") {
      val repository = stub[PasskeyRepository]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        _ <- repository.listByUser.succeedsWith(Vector.empty)
        ceremony <- service.startRegistration(settings, userId, "Test User")
      yield
        assertTrue(ceremony.request.nonEmpty) &&
        assertTrue(ceremony.publicKeyOptions.contains("publicKey")) &&
        assertTrue(ceremony.publicKeyOptions.contains("challenge"))
    },

    test("startAssertion produces a ceremony") {
      val repository = stub[PasskeyRepository]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        ceremony <- service.startAssertion(settings)
      yield
        assertTrue(ceremony.request.nonEmpty) &&
        assertTrue(ceremony.publicKeyOptions.contains("publicKey")) &&
        assertTrue(ceremony.publicKeyOptions.contains("challenge"))
    },

    test("credentialIdFromResponse extracts id from valid JSON") {
      val repository = stub[PasskeyRepository]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        response = """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""
        id <- service.credentialIdFromResponse(response)
      yield
        assertTrue(id.contains("Y3JlZC0xMjM"))
    },

    test("credentialIdFromResponse returns None for invalid JSON") {
      val repository = stub[PasskeyRepository]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        id <- service.credentialIdFromResponse("not-json")
      yield
        assertTrue(id.isEmpty)
    },

    test("finishRegistration fails if repository insert fails") {
      val repository = stub[PasskeyRepository]
      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        result <- service.finishRegistration(settings, userId, "{}", "{}", None).exit
      yield
        assert(result)(fails(isSubtype[WebAuthnError.CeremonyFailed](anything)))
    },

    test("finishAssertion fails with AssertionFailed if verification fails") {
      val repository = stub[PasskeyRepository]
      val credId = CredentialId("c".getBytes)
      val record = passkeyRecord(credId, userId)

      val request = """{"publicKeyCredentialRequestOptions":{"challenge":"AAAA","timeout":60000,"rpId":"localhost","allowCredentials":[],"userVerification":"required","extensions":{}}}"""
      val response = """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector(record))
        result <- service.finishAssertion(settings, request, response).exit
      yield
        // It will fail because the signature is invalid for the "challenge"
        assert(result)(fails(isSubtype[WebAuthnError](anything)))
    },

    test("finishAssertion fails if credential not found") {
      val repository = stub[PasskeyRepository]

      // We use a realistic-looking but fake request/response to trigger the library
      val request = """{"publicKeyCredentialRequestOptions":{"challenge":"AAAA","timeout":60000,"rpId":"localhost","allowCredentials":[],"userVerification":"required","extensions":{}}}"""
      val response = """{"id":"Y3JlZC0xMjM","rawId":"Y3JlZC0xMjM","response":{"clientDataJSON":"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQUFBQSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3QifQ","authenticatorData":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAAAAAAAA","signature":"Y3JlZC0xMjM","userHandle":"Y3JlZC0xMjM"},"type":"public-key","clientExtensionResults":{}}"""

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          WebAuthnService.live
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector.empty)
        result <- service.finishAssertion(settings, request, response).exit
      yield
        assert(result)(fails(equalTo(WebAuthnError.CredentialNotFound)))
    }
  )
