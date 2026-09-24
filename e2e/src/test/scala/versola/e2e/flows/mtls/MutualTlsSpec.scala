package versola.e2e.flows.mtls

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** RFC 8705 mutual-TLS client authentication and certificate-bound tokens, end to end.
  *
  * There is no TLS handshake anywhere in here, and there would not be one in production
  * either: `auth` sits behind the proxy that terminated mTLS and learns about the client
  * certificate only from the header §6.5 declines to standardise. Presenting that header is
  * therefore the whole of what "the client presented a certificate" means, and the tenant
  * setting that names it — configured for the default tenant in `Flows.layer` — is as much
  * part of the feature as the matching itself.
  *
  * The unit suites already prove the matching rules against a stubbed configuration service.
  * What only this level can show is that a certificate survives the trip through Central's
  * registration API, auth's configuration cache and zio-http's header handling, and still
  * decides the same way at the other end.
  */
object MutualTlsSpec extends E2ESpec:

  private val fixedOtp = "123456"
  private val redirectUri = "http://localhost:3000"

  private val certificate = Fixtures.ClientCertificates.client
  private val impostor = Fixtures.ClientCertificates.impostor

  /** The header value as the bootstrap's `urlEncodedPem` tenant expects it. */
  private val header = certificate.urlEncodedPem
  private val impostorHeader = impostor.urlEncodedPem

  private def uid: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  /** A client that authenticates by certificate and holds no secret it would ever use.
    *
    * Central still issues one — every `web` client gets a secret — but a client with
    * `mtlsAuth` never authenticates with it (RFC 8705 §2.1), which several tests below rely
    * on: they present the secret and it makes no difference either way.
    */
  private def mtlsClient(
      auth: OAuthClient,
      subjectType: String,
      subjectValue: String,
      scopes: Set[String] = Set("openid", "email"),
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"mtls-client-$s")
      result <- auth.registerClient(
        id,
        "Mutual TLS Test Client",
        Set(redirectUri),
        allowedScopes = scopes,
        authMethod = "tls_client_auth",
        mtlsAuth = Some(Fixtures.mutualTlsAuth(subjectType, subjectValue)),
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  /** A client that authenticates by RFC 8705 §2.2: it registers the public key inside the
    * certificate it will present, and no subject value at all. The same `jwks` column RFC
    * 7523 `private_key_jwt` reads, which is why §2.2 waited for it.
    */
  private def selfSignedClient(
      auth: OAuthClient,
      jwks: Json = Fixtures.ClientCertificates.client.jwks,
      scopes: Set[String] = Set("openid", "email"),
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"self-signed-client-$s")
      result <- auth.registerClient(
        id,
        "Self-Signed Mutual TLS Test Client",
        Set(redirectUri),
        allowedScopes = scopes,
        authMethod = "self_signed_tls_client_auth",
        mtlsAuth = Some(Fixtures.selfSignedTlsClientAuth),
        jwks = Some(jwks),
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  /** A client that authenticates by secret as usual but has asked for its tokens to be bound
    * to the certificate they were issued over — RFC 8705 §3 without §2.
    */
  private def boundClient(
      auth: OAuthClient,
      scopes: Set[String] = Set("openid", "email"),
      authFlow: Option[Json] = None,
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"bound-client-$s")
      result <- auth.registerClient(
        id,
        "Certificate Bound Test Client",
        Set(redirectUri),
        allowedScopes = scopes,
        authFlow = authFlow,
        certificateBoundAccessTokens = true,
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  /** A client with no RFC 8705 registration of any kind: it authenticates by secret and asked
    * for no binding, so its tokens carry no `cnf`. The control the certificate tests are read
    * against -- a tenant that forwards a certificate must not start constraining it.
    */
  private def plainClient(
      auth: OAuthClient,
      scopes: Set[String] = Set("openid", "email"),
      authFlow: Option[Json] = None,
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"plain-client-$s")
      result <- auth.registerClient(
        id,
        "Unbound Test Client",
        Set(redirectUri),
        allowedScopes = scopes,
        authFlow = authFlow,
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  /** The `cnf` claim of an access token, or `None` when the token carries no binding. */
  private def confirmation(accessToken: String): Task[Option[Json.Obj]] =
    for
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(1)), "UTF-8"))
        .mapError(error => RuntimeException(s"Access token is not a JWT [$error]: $accessToken"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj])
        .mapError(error => RuntimeException(s"Access token payload is not a JSON object [$error]: $payload"))
    yield json.get("cnf").collect { case obj: Json.Obj => obj }

  private def thumbprintOf(accessToken: String): Task[Option[String]] =
    confirmation(accessToken).map(_.flatMap(_.get("x5t#S256")).collect { case Json.Str(value) => value })

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  private def rejection(result: TokenResult): Task[(Status, String)] =
    result match
      case s: TokenResult.Success =>
        ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(response, body) =>
        ZIO.fromEither(body.fromJson[OAuthError])
          .mapBoth(
            error => RuntimeException(s"Unparsable /token error body [$error]: $body"),
            parsed => response.status -> parsed.error,
          )

  def spec = suite("Mutual TLS (RFC 8705)")(

    // ── §2.1 Client authentication ────────────────────────────────────────

    test("a client with no secret authenticates at /token with its certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        // `useBasicAuth = false` sends `client_id` and nothing else: the client presents no
        // secret at all, which is the whole point of registering an mTLS subject.
        token <- auth.clientCredentials(
          clientId,
          "",
          useBasicAuth = false,
          certificate = Some(header),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("RFC 8705 §2.1: the registered subject is the credential, so no secret is needed")
    },

    test("a registered dNSName authenticates as readily as a subject DN") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "san_dns", certificate.dnsName)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)).success
      yield assertTrue(token.accessToken.nonEmpty)
    },

    test("a client that registered a certificate is refused without one") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        // The secret Central issued is presented deliberately: RFC 8705 §2.1 does not let it
        // stand in for the certificate, so this must fail exactly as an empty request would.
        result <- auth.clientCredentials(clientId, clientSecret)
        (status, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
        .label("a secret must not authenticate a client that registered a certificate") &&
        assertTrue(status == Status.Unauthorized)
    },

    test("another client's certificate does not authenticate this one") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        result <- auth.clientCredentials(
          clientId,
          "",
          useBasicAuth = false,
          certificate = Some(impostorHeader),
        )
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
        .label(s"${impostor.subjectDn} must not pass for ${certificate.subjectDn}")
    },

    test("a certificate header the proxy mangled fails the request") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        // Truncated halfway: something did arrive for a client whose authentication depends
        // on it, so treating it as an absent certificate and falling back to the secret would
        // quietly downgrade the credential this client registered for.
        result <- auth.clientCredentials(
          clientId,
          clientSecret,
          certificate = Some(header.take(header.length / 2)),
        )
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
        .label("an unreadable certificate must fail rather than fall back to the secret")
    },

    test("a `+` left unescaped by the proxy is still read as base64") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        // Read as `application/x-www-form-urlencoded` every `+` would become a space and the
        // base64 would no longer decode. The fixture certificate contains several.
        token <- auth.clientCredentials(
          clientId,
          "",
          useBasicAuth = false,
          certificate = Some(certificate.urlEncodedPemWithLiteralPlus),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
    },

    test("/introspect authenticates the caller by certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        resource = s"https://$clientId.example.test"
        _ <- auth.registerResource(s"res-$clientId", resource, audience = Set(clientId))
        _ <- auth.syncConfiguration()
        token <- auth.clientCredentials(
          clientId,
          "",
          useBasicAuth = false,
          resources = Some(List(resource)),
          certificate = Some(header),
        ).success
        // Basic carries the id with an empty secret: /introspect refuses a caller that
        // presents nothing but a `client_id`, and a certificate is what satisfies it here.
        introspection <- auth.introspect(
          token.accessToken,
          Some(clientId),
          Some(""),
          certificate = Some(header),
        ).success
      yield assertTrue(introspection.active)
    },

    test("/revoke authenticates the caller by certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)).success
        revoked <- auth.revoke(token.accessToken, clientId, "", certificate = Some(header))
        withoutCertificate <- auth.revoke(token.accessToken, clientId, "")
      yield assertTrue(revoked.status == Status.Ok) &&
        assertTrue(withoutCertificate.status == Status.Unauthorized)
          .label("the same call without the certificate must not be accepted")
    },

    test("/par authenticates the pushing client by certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        pushed <- auth.pushAuthorizationRequest(
          clientId,
          "",
          redirectUri,
          certificate = Some(header),
        ).success
        rejected <- auth.pushAuthorizationRequest(clientId, "", redirectUri)
      yield assertTrue(pushed.requestUri.startsWith("urn:ietf:params:oauth:request_uri:")) &&
        assertTrue(rejected match { case f: PushedAuthorizationResult.Failure => f.error.contains("invalid_client"); case _ => false })
          .label("the same push without the certificate must be refused")
    },

    // ── §2.2 Self-signed certificate authentication ──────────────────

    test("a self-signed client authenticates with the key inside its certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- selfSignedClient(auth)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("RFC 8705 §2.2: the registered key is the credential -- no CA, no subject comparison")
    },

    test("a self-signed client is refused a certificate carrying a key it never registered") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- selfSignedClient(auth)
        // The impostor's certificate parses and is as valid as the client's own; what it does
        // not carry is a key this client registered, which is the whole of §2.2's check.
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(impostorHeader))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
    },

    test("a self-signed client is refused without a certificate, secret or no secret") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- selfSignedClient(auth)
        result <- auth.clientCredentials(clientId, clientSecret)
        (status, error) <- rejection(result)
      yield assertTrue(error == "invalid_client") &&
        assertTrue(status == Status.Unauthorized)
          .label("the secret central issued must not stand in for the certificate")
    },

    test("a self-signed client's registered keys do not also authenticate an assertion") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        // Registered for §2.2 with keys the client also holds the private half of, which is
        // what makes this a rule rather than an accident of the fixture: those keys are
        // matched against a certificate, and RFC 7523 is a second credential nobody granted.
        (clientId, _) <- selfSignedClient(auth, jwks = signer.jwks)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
    },

    test("a token issued to a self-signed client is bound to the certificate it presented") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- selfSignedClient(auth)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)).success
        thumbprint <- thumbprintOf(token.accessToken)
      yield assertTrue(thumbprint.contains(certificate.thumbprint))
        .label("§2 implies §3 for this method exactly as it does for §2.1")
    },

    // ── §3 Certificate-bound access tokens ────────────────────────────────

    test("a token issued to an mTLS client carries the certificate thumbprint") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)).success
        thumbprint <- thumbprintOf(token.accessToken)
      yield assertTrue(thumbprint.contains(certificate.thumbprint))
        .label(s"RFC 8705 §3.1: expected cnf.x5t#S256=${certificate.thumbprint}, got $thumbprint")
    },

    test("a secret-authenticating client that asked for binding gets it too") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- boundClient(auth)
        // The client authenticates exactly as it always did. The certificate matters only to
        // §3, which is why the token endpoint reads the header for a wider set of clients
        // than the ones that authenticate by one.
        token <- auth.clientCredentials(clientId, clientSecret, certificate = Some(header)).success
        thumbprint <- thumbprintOf(token.accessToken)
      yield assertTrue(thumbprint.contains(certificate.thumbprint))
    },

    test("a client that binds nothing gets no confirmation, certificate or not") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        id <- uid.map(s => s"plain-client-$s")
        result <- auth.registerClient(id, "Plain Test Client", Set(redirectUri), allowedScopes = Set("openid", "email")).success
        _ <- auth.syncConfiguration()
        // A tenant whose proxy forwards a certificate on every connection must not end up
        // constraining tokens nobody asked to constrain.
        token <- auth.clientCredentials(id, result.secret, certificate = Some(header)).success
        cnf <- confirmation(token.accessToken)
      yield assertTrue(cnf.isEmpty)
        .label(s"expected no cnf for a client that binds nothing, got $cnf")
    },

    test("refreshing a bound grant requires the same certificate") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- boundClient(
          auth,
          scopes = Set("openid", "email", "offline_access"),
          authFlow = Some(Flows.emailOtpAuthFlow),
        )
        email <- uid.map(s => s"mtls-$s@example.test")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()

        authorize <- auth.authorize(
          scope = "openid email offline_access",
          clientId = Some(clientId),
          redirectUri = Some(redirectUri),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, email, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect

        issued <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(clientId),
          clientSecret = Some(clientSecret),
          redirectUri = Some(redirectUri),
          certificate = Some(header),
        ).success
        refreshToken <- ZIO.fromOption(issued.refreshToken)
          .orElseFail(RuntimeException("Expected a refresh token for the offline_access scope"))

        // A client registered for binding that forwards no certificate is refused on its
        // registration alone (§3.4), before the grant is read: there is no certificate for
        // the token it would issue to be bound to.
        unbound <- auth.refresh(refreshToken, Some(clientId), Some(clientSecret))
        (_, refusal) <- rejection(unbound)

        // Under a certificate that is not the one the grant was bound to, it is the grant
        // that fails: re-deriving the binding from whatever the presenter holds now is
        // exactly what must not happen.
        mismatched <- auth.refresh(
          refreshToken,
          Some(clientId),
          Some(clientSecret),
          certificate = Some(impostorHeader),
        )
        (_, mismatch) <- rejection(mismatched)

        renewed <- auth.refresh(
          refreshToken,
          Some(clientId),
          Some(clientSecret),
          certificate = Some(header),
        ).success
        thumbprint <- thumbprintOf(renewed.accessToken)
        _ <- auth.deleteUser(userId)
      yield assertTrue(refusal == "invalid_client")
        .label("a client registered for binding must not refresh without a certificate") &&
        assertTrue(mismatch == "invalid_grant")
          .label("a certificate-bound grant must not refresh under a different certificate") &&
        assertTrue(thumbprint.contains(certificate.thumbprint))
          .label("the renewed token must carry the same binding")
    },

    test("/userinfo honours a certificate-bound token only over the certificate it is bound to") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- boundClient(
          auth,
          scopes = Set("openid", "email"),
          authFlow = Some(Flows.emailOtpAuthFlow),
        )
        email <- uid.map(s => s"mtls-userinfo-$s@example.test")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()

        authorize <- auth.authorize(
          scope = "openid email",
          clientId = Some(clientId),
          redirectUri = Some(redirectUri),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, email, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect

        issued <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(clientId),
          clientSecret = Some(clientSecret),
          redirectUri = Some(redirectUri),
          certificate = Some(header),
        ).success
        thumbprint <- thumbprintOf(issued.accessToken)

        // The resource endpoint's half of §3: the binding is worth nothing if the token is
        // also accepted without the certificate, which is how a stolen copy would be used.
        served <- auth.userinfo(issued.accessToken, certificate = Some(header))
        bare <- auth.userinfo(issued.accessToken)
        mismatched <- auth.userinfo(issued.accessToken, certificate = Some(impostorHeader))
        _ <- auth.deleteUser(userId)
      yield assertTrue(thumbprint.contains(certificate.thumbprint))
        .label("the token has to be bound for the rest of this to mean anything") &&
        assertTrue(served.isInstanceOf[UserinfoResult.Success])
          .label("the same certificate the token was issued over must serve it") &&
        assertTrue(bare match { case f: UserinfoResult.Failure => f.response.status == Status.Unauthorized; case _ => false })
          .label("a bound token presented with no certificate is the downgrade §3 refuses") &&
        assertTrue(mismatched match { case f: UserinfoResult.Failure => f.response.status == Status.Unauthorized; case _ => false })
          .label("and another client's certificate is no better than none")
    },

    test("/userinfo leaves an unbound token alone though the tenant forwards certificates") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- plainClient(
          auth,
          scopes = Set("openid", "email"),
          authFlow = Some(Flows.emailOtpAuthFlow),
        )
        email <- uid.map(s => s"mtls-plain-$s@example.test")
        userId <- auth.registerUser(email = Some(email))
        _ <- auth.flushUserOutbox()

        authorize <- auth.authorize(
          scope = "openid email",
          clientId = Some(clientId),
          redirectUri = Some(redirectUri),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, email, credential.csrf)
        otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect
        issued <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(clientId),
          clientSecret = Some(clientSecret),
          redirectUri = Some(redirectUri),
        ).success
        binding <- confirmation(issued.accessToken)

        // Neither presentation is constrained: nothing bound this token, and a tenant whose
        // proxy forwards a certificate on every connection must not start demanding one.
        served <- auth.userinfo(issued.accessToken, certificate = Some(header))
        bare <- auth.userinfo(issued.accessToken)
        _ <- auth.deleteUser(userId)
      yield assertTrue(binding.isEmpty)
        .label("the token has to be unbound for the rest of this to mean anything") &&
        assertTrue(served.isInstanceOf[UserinfoResult.Success])
          .label("a forwarded certificate must not constrain a token nobody bound") &&
        assertTrue(bare.isInstanceOf[UserinfoResult.Success])
          .label("and neither must its absence")
    },

    // ── §6.5 Proxy termination ────────────────────────────────────────────

    test("a tenant behind a Traefik-style proxy reads base64 DER instead") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- mtlsClient(auth, "subject_dn", certificate.subjectDn)
        // The header and its encoding are one setting, not two: switching the tenant to the
        // other pairing must change which body shape is understood, and only that.
        outcome <- ZIO.acquireRelease(
          auth.upsertChallengeSettings(
            acrVocabulary = Map(Acr.OtpLevel -> List("otp"), Acr.PasswordLevel -> List("password"), Acr.PasskeyLevel -> List("passkey")),
            mtlsCertificateHeader = Some(OAuthClient.mtlsCertificateHeader),
            mtlsCertificateEncoding = Some("base64Der"),
          ) *> auth.syncConfiguration(),
        )(_ =>
          (auth.upsertChallengeSettings(
            acrVocabulary = Map(Acr.OtpLevel -> List("otp"), Acr.PasswordLevel -> List("password"), Acr.PasskeyLevel -> List("passkey")),
            mtlsCertificateHeader = Some(OAuthClient.mtlsCertificateHeader),
            mtlsCertificateEncoding = Some("urlEncodedPem"),
          ) *> auth.syncConfiguration()).orDie,
        ).flatMap: _ =>
          auth.clientCredentials(
            clientId,
            "",
            useBasicAuth = false,
            certificate = Some(certificate.base64Der),
          ).success.zip(
            auth.clientCredentials(clientId, "", useBasicAuth = false, certificate = Some(header)),
          )
        (accepted, refused) = outcome
      yield assertTrue(accepted.accessToken.nonEmpty)
        .label("the configured encoding must be understood") &&
        assertTrue(refused.isInstanceOf[TokenResult.Failure])
          .label("and the one the tenant is not configured for must not be")
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
