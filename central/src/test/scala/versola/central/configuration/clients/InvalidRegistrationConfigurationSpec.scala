package versola.central.configuration.clients

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.RSAKey
import versola.central.configuration.challenges.SecurityProfile
import versola.central.configuration.roles.RoleId
import versola.util.{JsonWebKeySet, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri, TestCertificates, UnitSpecBase}
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

  /** A web client edge fronts with `tls_client_auth` behind PAR: what FAPI 2.0 admits. */
  private val conformant = InvalidRegistrationConfiguration.ProfileSubject(
    authMethod = AuthMethod.tls_client_auth,
    senderConstrained = true,
    requirePushedAuthorizationRequests = true,
    redirectUris = Set(RedirectUri("https://app.example/callback")),
    native = false,
  )

  private def fapi2(subject: InvalidRegistrationConfiguration.ProfileSubject, allowHttpLoopback: Boolean = false) =
    InvalidRegistrationConfiguration.profileViolations(SecurityProfile.fapi2, subject, allowHttpLoopback)

  private val securityProfileSuite = suite("profileViolations")(
    test("admits an edge-fronted tls_client_auth web client behind PAR") {
      assertTrue(fapi2(conformant).isEmpty)
    },
    test("admits every confidential method FAPI 2.0 leaves a client") {
      assertTrue(
        List(AuthMethod.private_key_jwt, AuthMethod.tls_client_auth, AuthMethod.self_signed_tls_client_auth)
          .forall(method => fapi2(conformant.copy(authMethod = method)).isEmpty),
      )
    },
    test("refuses a client_secret client and a public one") {
      assertTrue(
        fapi2(conformant.copy(authMethod = AuthMethod.client_secret)).exists(_.contains("not client_secret")),
        fapi2(conformant.copy(authMethod = AuthMethod.none)).exists(_.contains("not none")),
      )
    },
    test("refuses bearer access tokens") {
      assertTrue(fapi2(conformant.copy(senderConstrained = false)).exists(_.contains("sender-constrained")))
    },
    test("refuses a client with redirect URIs that does not require PAR, but not a service client") {
      assertTrue(
        fapi2(conformant.copy(requirePushedAuthorizationRequests = false)).exists(_.contains("pushed authorization")),
        fapi2(conformant.copy(requirePushedAuthorizationRequests = false, redirectUris = Set.empty)).isEmpty,
      )
    },
    test("refuses an http redirect URI, and a loopback one for a web client") {
      val loopback = RedirectUri("http://127.0.0.1:8123/callback")
      assertTrue(
        fapi2(conformant.copy(redirectUris = Set(RedirectUri("http://app.example/callback")))).exists(_.contains("https")),
        fapi2(conformant.copy(redirectUris = Set(loopback))).exists(_.contains("https")),
        fapi2(conformant.copy(redirectUris = Set(RedirectUri("com.example.app://callback")))).exists(_.contains("https")),
      )
    },
    test("admits a loopback http redirect URI for a native client, or outside production for any") {
      val loopback = Set(RedirectUri("http://127.0.0.1:8123/callback"), RedirectUri("http://localhost:9005/complete"))
      assertTrue(
        fapi2(conformant.copy(redirectUris = loopback, native = true)).isEmpty,
        fapi2(conformant.copy(redirectUris = loopback), allowHttpLoopback = true).isEmpty,
        fapi2(conformant.copy(redirectUris = Set(RedirectUri("http://app.example/cb"))), allowHttpLoopback = true).nonEmpty,
      )
    },
    // #421: a native client fronted by edge authenticates with edge's certificate but has its
    // tokens bound to the device's DPoP key rather than to that certificate.
    test("admits a native client edge authenticates with tls_client_auth whose tokens are DPoP-bound") {
      assertTrue(
        fapi2(conformant.copy(native = true, redirectUris = Set(RedirectUri("https://app.example/app-link")))).isEmpty,
      )
    },
    test("reports every violation, not only the first") {
      val subject = InvalidRegistrationConfiguration.ProfileSubject(
        authMethod = AuthMethod.none,
        senderConstrained = false,
        requirePushedAuthorizationRequests = false,
        redirectUris = Set(RedirectUri("http://app.example/a"), RedirectUri("http://app.example/b")),
        native = false,
      )
      assertTrue(fapi2(subject).size == 5)
    },
    test("admits anything under the standard profile") {
      assertTrue(
        InvalidRegistrationConfiguration.profileViolations(
          SecurityProfile.standard,
          conformant.copy(authMethod = AuthMethod.none, senderConstrained = false, requirePushedAuthorizationRequests = false),
          allowHttpLoopback = false,
        ).isEmpty,
      )
    },
  )

  private def edgeFrontedNative(
      applicationType: ApplicationType = ApplicationType.native,
      authMethod: AuthMethod = AuthMethod.tls_client_auth,
      hasEdgeClientCertificate: Boolean = true,
      requirePushedAuthorizationRequests: Boolean = true,
      dpopBoundAccessTokens: Boolean = true,
      certificateBoundAccessTokens: Boolean = false,
      redirectUris: Set[String] = Set("https://app.example.com/callback"),
  ) = InvalidRegistrationConfiguration.validateEdgeFrontedNative(
    clientId,
    applicationType,
    authMethod,
    hasEdgeClientCertificate,
    requirePushedAuthorizationRequests,
    dpopBoundAccessTokens,
    certificateBoundAccessTokens,
    redirectUris,
  ).map(_.reason)

  private val edgeFrontedNativeSuite = suite("validateEdgeFrontedNative")(
    test("accepts a native tls_client_auth client registered for edge") {
      assertTrue(edgeFrontedNative().isEmpty)
    },
    test("leaves a public native client and any web client alone") {
      assertTrue(
        edgeFrontedNative(authMethod = AuthMethod.none, hasEdgeClientCertificate = false, dpopBoundAccessTokens = false).isEmpty,
        edgeFrontedNative(applicationType = ApplicationType.web, requirePushedAuthorizationRequests = false).isEmpty,
      )
    },
    test("refuses a native client with a credential the app would have to keep") {
      assertTrue(
        edgeFrontedNative(authMethod = AuthMethod.client_secret).exists(_.contains("client_secret")),
        edgeFrontedNative(authMethod = AuthMethod.self_signed_tls_client_auth).exists(_.contains("self_signed_tls_client_auth")),
      )
    },
    test("refuses each missing requirement of the edge-fronted split") {
      assertTrue(
        edgeFrontedNative(hasEdgeClientCertificate = false).exists(_.contains("edgeClientCertificate")),
        edgeFrontedNative(requirePushedAuthorizationRequests = false).exists(_.contains("requirePushedAuthorizationRequests")),
        edgeFrontedNative(dpopBoundAccessTokens = false).exists(_.contains("dpopBoundAccessTokens")),
        edgeFrontedNative(certificateBoundAccessTokens = true).exists(_.contains("certificateBoundAccessTokens")),
        edgeFrontedNative(redirectUris = Set.empty).exists(_.contains("redirect URI")),
      )
    },
    test("refuses a custom-scheme or plain-http redirect URI") {
      assertTrue(
        edgeFrontedNative(redirectUris = Set("https://app.example.com/cb", "com.example.app:/cb"))
          .exists(_.contains("com.example.app:/cb")),
        edgeFrontedNative(redirectUris = Set("http://localhost:3000/cb")).exists(_.contains("http://localhost:3000/cb")),
      )
    },
  )

  private def issued(
      authMethod: AuthMethod = AuthMethod.tls_client_auth,
      mtlsAuth: Option[MutualTlsAuth] = None,
      edgeClientCertificate: Option[PrivateClientCertificate] = None,
      issue: Boolean = true,
  ) = InvalidRegistrationConfiguration.validateIssuedEdgeClientCertificate(
    clientId,
    issue,
    authMethod,
    mtlsAuth,
    edgeClientCertificate,
  ).map(_.reason)

  private val issuedEdgeClientCertificateSuite = suite("validateIssuedEdgeClientCertificate")(
    test("accepts a tls_client_auth client stating neither its certificate nor its subject") {
      assertTrue(issued().isEmpty)
    },
    test("leaves a registration that issues nothing alone") {
      assertTrue(issued(authMethod = AuthMethod.client_secret, issue = false).isEmpty)
    },
    test("refuses every method but tls_client_auth") {
      assertTrue(
        issued(authMethod = AuthMethod.self_signed_tls_client_auth).exists(_.contains("self_signed_tls_client_auth")),
        issued(authMethod = AuthMethod.private_key_jwt).exists(_.contains("private_key_jwt")),
      )
    },
    test("refuses a certificate or a subject supplied beside the issued one") {
      val certificate = TestCertificates.generate(subject = "CN=supplied")
      assertTrue(
        issued(edgeClientCertificate = Some(PrivateClientCertificate(certificate.bundle)))
          .exists(_.contains("cannot be combined with edgeClientCertificate")),
        issued(mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.subject_dn, "CN=supplied")))
          .exists(_.contains("cannot be combined with mtlsAuth")),
      )
    },
  )

  def spec = suite("InvalidRegistrationConfiguration")(
    securityProfileSuite,
    edgeSigningKeySuite,
    edgeFrontedNativeSuite,
    issuedEdgeClientCertificateSuite,
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
