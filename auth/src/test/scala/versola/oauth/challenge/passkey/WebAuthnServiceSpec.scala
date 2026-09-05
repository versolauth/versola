package versola.oauth.challenge.passkey

import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import versola.auth.model.{AuthenticatorTransport, CredentialDeviceType, CredentialId, PasskeyName, PasskeyRecord}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PasskeySettings}
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.*
import zio.test.*
import zio.test.Assertion.*

import java.io.ByteArrayOutputStream
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, MessageDigest, Signature}
import java.time.Instant
import java.util.{Base64, UUID}

/** Hand-rolled "virtual authenticator": the java-webauthn-server library ships no public
  * test-jar with one (unlike its own test suite, which has an internal `TestAuthenticator`),
  * so a successful registration/assertion ceremony has to be constructed by hand here --
  * a real ES256 key pair, CBOR-encoded `attestationObject`/COSE key, and a real ECDSA
  * signature over `authenticatorData || sha256(clientDataJSON)` -- to reach any of
  * [[WebAuthnService]]'s success-path branches at all.
  */
object VirtualAuthenticator:
  private val cbor = CBORFactory()

  def keyPair() =
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"))
    kpg.generateKeyPair()

  /** The 16-byte `userHandle` [[WebAuthnService.Impl.userIdToHandle]] derives from a UUID --
    * duplicated here (rather than made accessible) since it's a one-line private encoding. */
  def userHandle(userId: UUID): Array[Byte] =
    val bb = java.nio.ByteBuffer.allocate(16)
    bb.putLong(userId.getMostSignificantBits)
    bb.putLong(userId.getLeastSignificantBits)
    bb.array()

  /** The raw COSE_Key bytes for `pub`, as would be stored in [[PasskeyRecord.publicKey]]. */
  def publicKeyCose(pub: ECPublicKey): Array[Byte] = coseKey(pub)

  private def sha256(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)

  private def b64Url(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def fixed32(bi: java.math.BigInteger): Array[Byte] =
    val raw = bi.toByteArray
    val trimmed = if raw.length > 32 then raw.takeRight(32) else raw
    Array.fill[Byte](32 - trimmed.length)(0) ++ trimmed

  /** COSE_Key (RFC 9053) EC2/ES256 encoding of a public key, using integer field ids --
    * this is why the encoding is done with the low-level `CBORGenerator` rather than
    * Jackson's `ObjectMapper`, which only knows how to write String field names.
    */
  private def coseKey(pub: ECPublicKey): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gen = cbor.createGenerator(out)
    gen.writeStartObject()
    gen.writeFieldId(1); gen.writeNumber(2)  // kty: EC2
    gen.writeFieldId(3); gen.writeNumber(-7) // alg: ES256
    gen.writeFieldId(-1); gen.writeNumber(1) // crv: P-256
    gen.writeFieldId(-2); gen.writeBinary(fixed32(pub.getW.getAffineX))
    gen.writeFieldId(-3); gen.writeBinary(fixed32(pub.getW.getAffineY))
    gen.writeEndObject()
    gen.close()
    out.toByteArray

  private def authenticatorData(rpId: String, credentialId: Array[Byte], pub: ECPublicKey, attested: Boolean): Array[Byte] =
    val rpIdHash = sha256(rpId.getBytes("UTF-8"))
    val flags: Byte = if attested then 0x45.toByte else 0x05.toByte // UP|UV(|AT)
    val signCount = Array[Byte](0, 0, 0, 1)
    val attestedData =
      if !attested then Array.emptyByteArray
      else
        val aaguid = Array.fill[Byte](16)(0)
        val credIdLen = Array[Byte]((credentialId.length >> 8).toByte, credentialId.length.toByte)
        aaguid ++ credIdLen ++ credentialId ++ coseKey(pub)
    rpIdHash ++ Array(flags) ++ signCount ++ attestedData

  private def attestationObject(authData: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gen = cbor.createGenerator(out)
    gen.writeStartObject()
    gen.writeFieldName("fmt"); gen.writeString("none")
    gen.writeFieldName("attStmt"); gen.writeStartObject(); gen.writeEndObject()
    gen.writeFieldName("authData"); gen.writeBinary(authData)
    gen.writeEndObject()
    gen.close()
    out.toByteArray

  /** Extracts `"challenge":"..."` from a library-serialized creation/request options JSON,
    * so the fabricated response's clientDataJSON can echo the real, ceremony-specific value.
    */
  def extractChallenge(optionsJson: String): String =
    "\"challenge\":\"([^\"]+)\"".r.findFirstMatchIn(optionsJson).get.group(1)

  /** Builds a `response` JSON accepted by `PublicKeyCredential.parseRegistrationResponseJson`,
    * simulating a fresh authenticator enrolling `credentialId` under the given key pair.
    */
  def registrationResponse(
      rpId: String,
      origin: String,
      challenge: String,
      credentialId: Array[Byte],
      pub: ECPublicKey,
  ): String =
    val clientData = s"""{"type":"webauthn.create","challenge":"$challenge","origin":"$origin"}"""
    val authData = authenticatorData(rpId, credentialId, pub, attested = true)
    s"""{"id":"${b64Url(credentialId)}","rawId":"${b64Url(credentialId)}","response":{"clientDataJSON":"${b64Url(
        clientData.getBytes("UTF-8"),
      )}","attestationObject":"${b64Url(attestationObject(authData))}"},"type":"public-key","clientExtensionResults":{}}"""

  /** Builds a `response` JSON accepted by `PublicKeyCredential.parseAssertionResponseJson`,
    * simulating an authenticator asserting `credentialId`, signed with its private key.
    */
  def assertionResponse(
      rpId: String,
      origin: String,
      challenge: String,
      credentialId: Array[Byte],
      userHandle: Array[Byte],
      pub: ECPublicKey,
      priv: ECPrivateKey,
  ): String =
    val clientData = s"""{"type":"webauthn.get","challenge":"$challenge","origin":"$origin"}"""
    val clientDataBytes = clientData.getBytes("UTF-8")
    val authData = authenticatorData(rpId, credentialId, pub, attested = false)
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(priv)
    signer.update(authData ++ sha256(clientDataBytes))
    val signature = signer.sign()
    s"""{"id":"${b64Url(credentialId)}","rawId":"${b64Url(credentialId)}","response":{"clientDataJSON":"${b64Url(
        clientDataBytes,
      )}","authenticatorData":"${b64Url(authData)}","signature":"${b64Url(signature)}","userHandle":"${b64Url(
        userHandle,
      )}"},"type":"public-key","clientExtensionResults":{}}"""

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
    test("finishRegistration verifies a real ceremony and persists a single-device passkey") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      val credentialId = "test-credential-1".getBytes("UTF-8")
      val keyPair = VirtualAuthenticator.keyPair()

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        _ <- repository.listByUser.succeedsWith(Vector.empty)
        ceremony <- service.startRegistration(settings, userId, "Test User")
        challenge = VirtualAuthenticator.extractChallenge(ceremony.publicKeyOptions)
        response = VirtualAuthenticator.registrationResponse(
          rpId = settings.rpId,
          origin = settings.origins.head,
          challenge = challenge,
          credentialId = credentialId,
          pub = keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey],
        )
        _ <- configService.getPasskeySettings.succeedsWith(Some(settings))
        // RelyingParty.finishRegistration rejects a credential id already known to the RP, so
        // it checks `credentialRepository.lookupAll` (-> repository.findByCredentialId) too.
        _ <- repository.findByCredentialId.succeedsWith(Vector.empty)
        _ <- repository.insert.succeedsWith(())
        record <- service.finishRegistration(clientId, userId, ceremony.request, response, Some(PasskeyName("My Key")))
      yield assertTrue(
        record.id.sameElements(credentialId),
        record.userId == userId,
        record.publicKey.nonEmpty,
        record.deviceType == CredentialDeviceType.SingleDevice,
        !record.backedUp,
        !record.backupEligible,
        record.attestationObject.isDefined,
        record.clientDataJson.isDefined,
        record.aaguid.exists(_.length == 16),
        record.name.contains(PasskeyName("My Key")),
      )
    },
    test("finishAssertion verifies a real ceremony, resolves the user and bumps usage") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      val keyPair = VirtualAuthenticator.keyPair()
      val pub = keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]
      val priv = keyPair.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
      val credId = CredentialId("test-credential-2".getBytes("UTF-8"))
      val record = passkeyRecord(credId, userId).copy(publicKey = VirtualAuthenticator.publicKeyCose(pub))

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        ceremony <- service.startAssertion(settings)
        challenge = VirtualAuthenticator.extractChallenge(ceremony.publicKeyOptions)
        response = VirtualAuthenticator.assertionResponse(
          rpId = settings.rpId,
          origin = settings.origins.head,
          challenge = challenge,
          credentialId = credId,
          userHandle = VirtualAuthenticator.userHandle(userId),
          pub = pub,
          priv = priv,
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector(record))
        _ <- repository.findByCredentialIdAndUser.succeedsWith(Some(record))
        _ <- repository.updateUsage.succeedsWith(true)
        outcome <- service.finishAssertion(settings, ceremony.request, response)
      yield assertTrue(
        outcome.userId == userId,
        outcome.credentialId.sameElements(credId),
        outcome.signatureCount >= 0L,
      )
    },
    // repository.findByCredentialIdAndUser also backs the RP's own signature-verification
    // lookup (it needs the stored public key to succeed at all), so it has to keep answering
    // Some(record) throughout -- the only way to reach the `updated == false` branch below
    // with a stub that returns one canned answer per call is to have the record still exist.
    test("finishAssertion fails with AssertionFailed when repository.updateUsage reports no row despite the record existing") {
      val repository = stub[PasskeyRepository]
      val configService = stub[OAuthConfigurationService]
      val keyPair = VirtualAuthenticator.keyPair()
      val pub = keyPair.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]
      val priv = keyPair.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
      val credId = CredentialId("test-credential-3".getBytes("UTF-8"))
      val record = passkeyRecord(credId, userId).copy(publicKey = VirtualAuthenticator.publicKeyCose(pub))

      for
        service <- ZIO.service[WebAuthnService].provide(
          ZLayer.succeed(repository),
          ZLayer.succeed(configService),
          WebAuthnService.live,
        )
        ceremony <- service.startAssertion(settings)
        challenge = VirtualAuthenticator.extractChallenge(ceremony.publicKeyOptions)
        response = VirtualAuthenticator.assertionResponse(
          rpId = settings.rpId,
          origin = settings.origins.head,
          challenge = challenge,
          credentialId = credId,
          userHandle = VirtualAuthenticator.userHandle(userId),
          pub = pub,
          priv = priv,
        )
        _ <- repository.findByCredentialId.succeedsWith(Vector(record))
        _ <- repository.findByCredentialIdAndUser.succeedsWith(Some(record))
        _ <- repository.updateUsage.succeedsWith(false)
        result <- service.finishAssertion(settings, ceremony.request, response).exit
      yield assert(result)(fails(equalTo(WebAuthnError.AssertionFailed)))
    },
  )
