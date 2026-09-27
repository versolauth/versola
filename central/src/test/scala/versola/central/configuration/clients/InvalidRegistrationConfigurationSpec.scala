package versola.central.configuration.clients

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey
import versola.central.configuration.roles.RoleId
import versola.util.{JsonWebKeySet, PrivateClientCertificate, PrivateJsonWebKey, TestCertificates, UnitSpecBase}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.interfaces.RSAPublicKey

object InvalidRegistrationConfigurationSpec extends UnitSpecBase:

  private val clientId = ClientId("registration-client")

  private val signingKeyPair =
    val generator = java.security.KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private def jwkDocument(kid: String, withPrivate: Boolean): Json.Obj =
    val builder = RSAKey.Builder(signingKeyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
      .keyID(kid)
      .algorithm(JWSAlgorithm.PS256)
    if withPrivate then builder.privateKey(signingKeyPair.getPrivate.nn)
    builder.build().nn.toJSONString.nn.fromJson[Json.Obj].toOption.get

  private val edgeSigningKey = PrivateJsonWebKey(jwkDocument("edge-key", withPrivate = true))
  private val publishedJwks = JsonWebKeySet(
    Json.Obj("keys" -> Json.Arr(jwkDocument("edge-key", withPrivate = false))),
  )

  private val edgeSigningKeySuite = suite("validateEdgeSigningKey")(
    test("accepts a signing key whose public half the client's jwks publishes") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), None, Some(publishedJwks))
          .isEmpty,
      )
    },
    test("leaves a client that registers no signing key alone") {
      assertTrue(
        InvalidRegistrationConfiguration.validateEdgeSigningKey(clientId, None, None, None).isEmpty,
      )
    },
    test("rejects a signing key with no jwks to verify what it signs") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), None, None)
          .exists(_.reason.contains("needs jwks")),
      )
    },
    // A `self_signed_tls_client_auth` client is the one that passes every other rule here: it
    // is required to publish jwks, and the edge key can be published in it. Auth still refuses
    // the assertion, because for an mTLS client those keys answer a certificate.
    test("rejects a signing key for a client that authenticates by certificate") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(
            clientId,
            Some(edgeSigningKey),
            Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
            Some(publishedJwks),
          )
          .exists(_.reason.contains("cannot be combined with mtlsAuth")),
      )
    },
    test("rejects a signing key the client's jwks does not publish") {
      val other = JsonWebKeySet(
        Json.Obj("keys" -> Json.Arr(jwkDocument("some-other-key", withPrivate = false))),
      )
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(clientId, Some(edgeSigningKey), None, Some(other))
          .exists(_.reason.contains("does not publish")),
      )
    },
    test("rejects a public key registered as a signing key") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeSigningKey(
            clientId,
            Some(PrivateJsonWebKey(jwkDocument("edge-key", withPrivate = false))),
            None,
            Some(publishedJwks),
          )
          .exists(_.reason.contains("private")),
      )
    },
  )

  private val clientCertificate =
    TestCertificates.generate(subject = "CN=web-app,O=Versola,C=KZ", dnsName = Some("web-app.versola.test"))

  private val edgeClientCertificate = PrivateClientCertificate(clientCertificate.bundle)

  private val edgeClientCertificateSuite = suite("validateEdgeClientCertificate")(
    test("accepts a certificate carrying the subject value the client is recognised by") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "web-app.versola.test")),
            None,
            requireSignedRequestObject = false,
          )
          .isEmpty,
      )
    },
    test("accepts a self-signed registration whose jwks publishes the certificate's key") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
            Some(clientCertificate.jwks),
            requireSignedRequestObject = false,
          )
          .isEmpty,
      )
    },
    test("leaves a client that registers no certificate alone") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(clientId, None, None, None, requireSignedRequestObject = false)
          .isEmpty,
      )
    },
    test("rejects a certificate for a client that registered no mtlsAuth") {
      // Nothing would ever read it: auth looks for a forwarded certificate only where the
      // registration says one authenticates.
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            None,
            None,
            requireSignedRequestObject = false,
          )
          .exists(_.reason.contains("needs mtlsAuth")),
      )
    },
    test("rejects a certificate carrying a different subject value than the one registered") {
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_dns, "someone-else.versola.test")),
            None,
            requireSignedRequestObject = false,
          )
          .exists(_.reason.contains("carries no san_dns")),
      )
    },
    test("rejects a self-signed registration whose jwks publishes some other key") {
      val other = TestCertificates.generate(subject = "CN=other,O=Versola,C=KZ")
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
            Some(other.jwks),
            requireSignedRequestObject = false,
          )
          .exists(_.reason.contains("does not publish")),
      )
    },
    test("rejects a certificate registered beside a signed request object requirement") {
      // The edge would hold a certificate and no key to sign the object with, and would fail
      // at the first authorization request rather than here.
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(edgeClientCertificate),
            Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
            Some(clientCertificate.jwks),
            requireSignedRequestObject = true,
          )
          .exists(_.reason.contains("requireSignedRequestObject")),
      )
    },
    test("rejects a certificate whose key does not belong to it") {
      val mismatched = PrivateClientCertificate(
        s"${clientCertificate.certificatePem}\n${TestCertificates.generate().privateKeyPem}",
      )
      assertTrue(
        InvalidRegistrationConfiguration
          .validateEdgeClientCertificate(
            clientId,
            Some(mismatched),
            Some(MutualTlsAuth.SelfSignedTlsClientAuth()),
            Some(clientCertificate.jwks),
            requireSignedRequestObject = false,
          )
          .exists(_.reason.contains("does not belong to its certificate")),
      )
    },
  )

  def spec = suite("InvalidRegistrationConfiguration")(
    edgeSigningKeySuite,
    edgeClientCertificateSuite,
    test("accepts multiple assigned roles") {
      val flow = RegistrationFlow.default.copy(
        roleIds = Set(RoleId("user"), RoleId("member")),
      )

      assertTrue(
        InvalidRegistrationConfiguration.validate(clientId, Some(AuthFlow.default), Some(flow)).isEmpty,
      )
    },
    test("rejects a registration flow without an assigned role") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(roleIds = Set.empty)),
      )

      assertTrue(result.exists(_.reason == "registration requires at least one assigned role"))
    },
    test("rejects password setup without credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.SetPassword()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
    test("rejects account setup before credential verification") {
      val result = InvalidRegistrationConfiguration.validate(
        clientId,
        Some(AuthFlow.default),
        Some(RegistrationFlow.default.copy(steps = List(RegistrationStep.PasskeyEnroll(), RegistrationStep.Otp()))),
      )

      assertTrue(result.exists(_.reason == "registration must start with OTP verification"))
    },
  )