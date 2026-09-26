package versola.edge

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import versola.edge.model.*
import versola.util.{Base64, ClientAssertion, EdgeAssertion, JWT, PrivateClientCertificate, PrivateJsonWebKey, RedirectUri, RequestObject, Secret, TestCertificates}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

object SSOClientSpec extends ZIOSpecDefault:

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    // Named for the same reason a certificate client would name it in production: without
    // anchors to authenticate auth with, a certificate is refused rather than presented. The
    // path is never opened here -- these tests stub the `Client`, so no handshake happens.
    versolaInternalTrustedCertificates = Some("auth-ca.pem"),
    edgeUrl = URL.decode("https://edge.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val basePreset = AuthorizationPreset(
    id = PresetId("test"),
    clientId = ClientId("web-app"),
    description = "test",
    redirectUri = RedirectUri("https://app.example/callback"),
    postLoginRedirectUri = RedirectUri("https://app.example/home"),
    postLogoutRedirectUri = None,
    scope = Set("openid"),
    responseType = "code",
    uiLocales = None,
    customParameters = Map.empty,
    cookieDomain = None,
    cookiePath = None,
  )

  private val state = State.fromBytes(Array.fill(16)(1.toByte))

  private val secretClient = OAuthClient(
    id = ClientId("web-app"),
    credential = ClientCredential.ClientSecret(Secret("s3cret".getBytes("UTF-8").nn)),
    permissions = Set.empty,
    accessTokenTtl = 15.minutes,
  )

  /** Stands in for the directory the real one writes to. What a certificate is written to is
    * `ClientCertificateFilesSpec`'s subject; here it only has to be somewhere the credential
    * can be observed arriving. */
  private val certificateFiles: ClientCertificateFiles = _ =>
    ZIO.succeed(ClientSSLCertConfig.FromClientCertFile("client.crt", "client.key"))

  private def recordingCertificateFiles(
      presented: Ref[List[PrivateClientCertificate.Material]],
  ): ClientCertificateFiles = certificate =>
    presented.update(certificate :: _)
      .as(ClientSSLCertConfig.FromClientCertFile("client.crt", "client.key"))

  private def queryParam(url: URL, key: String): Option[Chunk[String]] =
    url.queryParams.map.get(key)

  def spec = suite("SSOClient")(
    authorizeUriSuite,
    privateKeyJwtSuite,
    mutualTlsSuite,
    requestObjectSuite,
    pushedAuthorizationSuite,
    exchangeSuite,
    refreshSuite,
    userInfoSuite,
  )


  /** The key edge is provisioned with for a client that authenticates by key, and the public
    * half auth would have registered as that client's `jwks`. */
  private val signingJwk = RSAKey.Builder(keyPair.getPublic.nn.asInstanceOf[RSAPublicKey])
    .privateKey(keyPair.getPrivate.nn)
    .keyID("client-key-1")
    .algorithm(JWSAlgorithm.PS256)
    .build().nn

  private val publicKeys = JWT.PublicKeys(JWKSet(signingJwk.toPublicJWK).nn)

  private val signing = PrivateJsonWebKey(
    signingJwk.toJSONString.nn.fromJson[Json.Obj].toOption.get,
  ).signing.toOption.get

  private val keyClient = secretClient.copy(credential = ClientCredential.PrivateKeyJwt(signing))

  /** The certificate edge is provisioned with for a client that authenticates by mutual TLS,
    * and the client as it arrives over sync. */
  private val certificate = TestCertificates.generate(dnsName = Some("web-app.versola.test"))

  private val certificateMaterial =
    PrivateClientCertificate(certificate.bundle).material.toOption.get

  private val certificateClient =
    secretClient.copy(credential = ClientCredential.MutualTls(certificateMaterial))

  /** The issuer identifier, which is what auth checks an assertion's and a request object's
    * `aud` against. */
  private val issuer = config.versolaUrl.encode

  private val privateKeyJwtSuite = suite("SSOClient private_key_jwt")(
    test("authenticates the token request with an assertion instead of a basic secret") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.exchangeAuthorizationCode(
          Code("c-1"),
          CodeVerifier("v-1"),
          redirectUri,
          clientId,
          ClientCredential.PrivateKeyJwt(signing),
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
        assertion = form.get("client_assertion").flatMap(_.stringValue).get
        now <- Clock.instant
        verified <- ClientAssertion.verify(
          token = assertion,
          keys = publicKeys,
          allowedAlgorithms = ClientAssertion.Algorithm.Default,
          clientId = clientId,
          acceptedAudiences = Set(issuer),
          now = now,
          maxLifetime = 5.minutes,
        ).either
      yield assertTrue(
        // RFC 7523 §2.2 replaces the secret rather than accompanying it: sending both would
        // present two credentials for one client, and auth authenticates by exactly one.
        request.header(Header.Authorization).isEmpty,
        form.get("client_assertion_type").flatMap(_.stringValue) == Some(ClientAssertion.Type),
        form.get("client_id").flatMap(_.stringValue) == Some(clientId.toString),
        verified.isRight,
      )
    },
    test("mints a fresh assertion per request, so one observed cannot be replayed as another") {
      for
        seen <- Ref.make(List.empty[String])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](request =>
            request.body.asURLEncodedForm.orDie.flatMap(form =>
              seen.update(form.get("client_assertion").flatMap(_.stringValue).toList ::: _)
                .as(Response.json(tokenJson)),
            ),
          ).toRoutes,
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        credential = ClientCredential.PrivateKeyJwt(signing)
        _ <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential)
        _ <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential)
        assertions <- seen.get
      yield assertTrue(
        assertions.size == 2,
        assertions.distinct.size == 2,
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  /** RFC 8705 §2: the credential is the connection, not the request. What these assert is
    * that nothing of it leaks into the request -- a certificate client that also sent a
    * secret or an assertion would be presenting two credentials -- and that the one thing the
    * request must still carry, `client_id`, is there.
    */
  private val mutualTlsSuite = suite("SSOClient mutual TLS")(
    test("presents the certificate and puts no credential in the token request") {
      for
        seen <- Ref.make(Option.empty[Request])
        presented <- Ref.make(List.empty[PrivateClientCertificate.Material])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, recordingCertificateFiles(presented))
        _ <- sso.exchangeAuthorizationCode(
          Code("c-1"),
          CodeVerifier("v-1"),
          redirectUri,
          clientId,
          ClientCredential.MutualTls(certificateMaterial),
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
        certificates <- presented.get
      yield assertTrue(
        request.header(Header.Authorization).isEmpty,
        form.get("client_assertion").isEmpty,
        // §2.1: the certificate is matched against what the named client registered, so the
        // request still has to name one.
        form.get("client_id").flatMap(_.stringValue) == Some(clientId.toString),
        certificates.map(_.leaf.getSubjectX500Principal.getName) ==
          List(certificate.certificate.getSubjectX500Principal.getName),
      )
    },
    test("pushes to /par over the same certificate, with no credential in the body") {
      for
        seen <- Ref.make(Option.empty[Request])
        presented <- Ref.make(List.empty[PrivateClientCertificate.Material])
        _ <- captureRequest(seen, Response.json("""{"request_uri":"urn:ietf:params:oauth:request_uri:tls","expires_in":60}""").status(Status.Created))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, recordingCertificateFiles(presented))
        url <- sso.authorizeUri(
          basePreset,
          certificateClient.copy(requirePushedAuthorizationRequests = true),
          "challenge",
          state,
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
        certificates <- presented.get
      yield assertTrue(
        request.header(Header.Authorization).isEmpty,
        form.get("client_assertion").isEmpty,
        form.get("client_id").flatMap(_.stringValue) == Some(certificateClient.id.toString),
        certificates.size == 1,
        queryParam(url, "request_uri") == Some(Chunk("urn:ietf:params:oauth:request_uri:tls")),
      )
    },
    test("refuses a plaintext endpoint instead of calling it with no credential at all") {
      // There is no handshake to present a certificate in, so the call would arrive
      // unauthenticated and be answered `invalid_client` -- naming a certificate the request
      // never carried, which says nothing about the address that is the actual mistake.
      val plaintext = config.copy(
        versolaInternalUrl = Some(URL.decode("http://auth.internal:9003").toOption.get),
      )
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, plaintext, certificateFiles)
        error <- sso.exchangeAuthorizationCode(
          Code("c-1"),
          CodeVerifier("v-1"),
          redirectUri,
          clientId,
          ClientCredential.MutualTls(certificateMaterial),
        ).flip
      yield assertTrue(
        error == SSOClient.CredentialNeedsTls(clientId, plaintext.internalUrl / "token"),
      )
    },
    test("refuses an endpoint with no trust anchors instead of trusting whatever answers") {
      // zio-http's fallback authenticates no server at all, so presenting the certificate
      // without anchors would carry the session it opens over a connection to any host able
      // to intercept the route. Refused on the same terms as the plaintext case above.
      val untrusted = config.copy(versolaInternalTrustedCertificates = None)
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, untrusted, certificateFiles)
        error <- sso.exchangeAuthorizationCode(
          Code("c-1"),
          CodeVerifier("v-1"),
          redirectUri,
          clientId,
          ClientCredential.MutualTls(certificateMaterial),
        ).flip
      yield assertTrue(
        error == SSOClient.CredentialNeedsTrustedServer(clientId, untrusted.internalUrl / "token"),
      )
    },
    test("reports that it cannot sign a request object rather than sending an unsigned one") {
      // A certificate carries a key auth holds, but names no `kid` for a signature header to
      // select it by -- registration refuses the combination, and this is the guard for a
      // registration that predates it.
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.authorizeUri(
          basePreset,
          certificateClient.copy(requireSignedRequestObject = true),
          "challenge",
          state,
        ).flip
      yield assertTrue(error == SSOClient.CredentialCannotSign(certificateClient.id))
    },
    // RFC 8705 §3: the token auth issues this client is bound to the certificate, and
    // `/userinfo` refuses it to a connection that does not present that certificate. The
    // binding is proven the same way it was earned -- by the handshake -- so this call goes
    // over the certificate too, rather than carrying anything extra in the request.
    test("presents the certificate on /userinfo for a certificate-bound token") {
      for
        seen <- Ref.make(Option.empty[Request])
        presented <- Ref.make(List.empty[PrivateClientCertificate.Material])
        _ <- captureRequest(seen, Response.json("""{"sub":"user-1"}"""))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, recordingCertificateFiles(presented))
        _ <- sso.userInfo(
          AccessToken("at-1"),
          SSOClient.TokenBinding.Certificate(clientId, ClientCredential.MutualTls(certificateMaterial)),
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        certificates <- presented.get
      yield assertTrue(
        certificates.map(_.leaf.getSubjectX500Principal.getName) ==
          List(certificate.certificate.getSubjectX500Principal.getName),
        // The assertion exempts a DPoP-bound token from a proof it cannot produce. This token
        // is bound to something it can produce, so there is nothing to be exempted from.
        request.rawHeader(EdgeAssertion.HeaderName).isEmpty,
      )
    },
    test("refuses /userinfo over a plaintext endpoint for a certificate-bound token") {
      // The binding cannot be proven where there is no handshake, and calling anyway reads
      // back a bare refusal naming nothing about the certificate that was missing.
      val plaintext = config.copy(
        versolaInternalUrl = Some(URL.decode("http://auth.internal:9003").toOption.get),
      )
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, plaintext, certificateFiles)
        error <- sso.userInfo(
          AccessToken("at-1"),
          SSOClient.TokenBinding.Certificate(clientId, ClientCredential.MutualTls(certificateMaterial)),
        ).flip
      yield assertTrue(
        error == SSOClient.CredentialNeedsTls(clientId, plaintext.internalUrl / "userinfo"),
      )
    },
    test("names the client when a token is bound to a certificate this edge no longer holds") {
      // Central rotating the registration to another credential leaves tokens already issued
      // bound to a certificate nothing here can present.
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.userInfo(
          AccessToken("at-1"),
          SSOClient.TokenBinding.Certificate(clientId, secretClient.credential),
        ).flip
      yield assertTrue(error == SSOClient.TokenBoundToAbsentCertificate(clientId))
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val requestObjectSuite = suite("SSOClient request objects")(
    test("signs the request and sends it as the `request` parameter") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(
          basePreset,
          keyClient.copy(requireSignedRequestObject = true),
          "challenge",
          state,
        )
        now <- Clock.instant
        claims <- RequestObject.verify(
          token = queryParam(url, RequestObject.Parameter).get.head,
          keys = publicKeys,
          allowedAlgorithms = ClientAssertion.Algorithm.Default,
          clientId = keyClient.id,
          acceptedAudiences = Set(issuer),
          now = now,
          maxLifetime = 10.minutes,
        )
        parameters = RequestObject.parameters(claims)
      yield assertTrue(
        // RFC 9101 §6.3: the object travels with the client_id it names, and nothing else --
        // a parameter left outside would be one the signature does not cover.
        url.queryParams.map.keySet == Set("client_id", RequestObject.Parameter),
        queryParam(url, "client_id") == Some(Chunk(keyClient.id.toString)),
        parameters("redirect_uri") == Chunk(basePreset.redirectUri.toString),
        parameters("code_challenge") == Chunk("challenge"),
        parameters("code_challenge_method") == Chunk("S256"),
        parameters("state") == Chunk(state.toString),
        parameters("scope") == Chunk("openid"),
      )
    },
    test("leaves the request plain for a client that did not register the requirement") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(basePreset, keyClient, "challenge", state)
      yield assertTrue(
        queryParam(url, RequestObject.Parameter).isEmpty,
        queryParam(url, "code_challenge") == Some(Chunk("challenge")),
      )
    },
    test("fails naming the client when it requires signing but edge holds only a secret") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.authorizeUri(
          basePreset,
          secretClient.copy(requireSignedRequestObject = true),
          "challenge",
          state,
        ).flip
      yield assertTrue(
        error == SSOClient.CredentialCannotSign(secretClient.id),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val pushedAuthorizationSuite = suite("SSOClient pushed authorization requests")(
    test("pushes the request and redirects with only the request_uri it hands back") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json("""{"request_uri":"urn:ietf:params:oauth:request_uri:abc","expires_in":60}""").status(Status.Created))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(
          basePreset,
          secretClient.copy(requirePushedAuthorizationRequests = true),
          "challenge",
          state,
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
      yield assertTrue(
        request.method == Method.POST,
        request.url.path.toString.endsWith("par"),
        form.get("code_challenge").flatMap(_.stringValue) == Some("challenge"),
        form.get("state").flatMap(_.stringValue) == Some(state.toString),
        request.header(Header.Authorization).contains(
          Header.Authorization.Basic(secretClient.id, Base64.urlEncode(clientSecret)),
        ),
        // RFC 9126 §6.2 buys nothing if the request is also appended to the redirect: the
        // reference has to be all the user agent carries.
        url.queryParams.map.keySet == Set("client_id", "request_uri"),
        queryParam(url, "request_uri") == Some(Chunk("urn:ietf:params:oauth:request_uri:abc")),
      )
    },
    test("pushes the signed object for a client that requires both") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json("""{"request_uri":"urn:ietf:params:oauth:request_uri:def","expires_in":60}""").status(Status.Created))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(
          basePreset,
          keyClient.copy(requireSignedRequestObject = true, requirePushedAuthorizationRequests = true),
          "challenge",
          state,
        )
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
        now <- Clock.instant
        claims <- RequestObject.verify(
          token = form.get(RequestObject.Parameter).flatMap(_.stringValue).get,
          keys = publicKeys,
          allowedAlgorithms = ClientAssertion.Algorithm.Default,
          clientId = keyClient.id,
          acceptedAudiences = Set(issuer),
          now = now,
          maxLifetime = 10.minutes,
        )
      yield assertTrue(
        // The object states the request that is pushed, so it must be signed before the push
        // rather than after it.
        RequestObject.parameters(claims)("state") == Chunk(state.toString),
        form.get("client_assertion_type").flatMap(_.stringValue) == Some(ClientAssertion.Type),
        // The request object and the assertion both name the client, and RFC 6749 §3.1 allows
        // the parameter once: a second copy is decoded as the two joined by a comma, which
        // identifies nothing and is refused before the assertion is ever verified.
        form.get("client_id").flatMap(_.stringValue) == Some(keyClient.id.toString),
        queryParam(url, "request_uri") == Some(Chunk("urn:ietf:params:oauth:request_uri:def")),
      )
    },
    test("reports what /par refused rather than redirecting to a request it never stored") {
      for
        _ <- respondWith(
          Response.json("""{"error":"invalid_request","error_description":"redirect_uri not registered"}""")
            .status(Status.BadRequest),
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.authorizeUri(
          basePreset,
          secretClient.copy(requirePushedAuthorizationRequests = true),
          "challenge",
          state,
        ).flip
      yield assertTrue(
        error.getMessage.nn.contains("400"),
        error.getMessage.nn.contains("invalid_request"),
        error.getMessage.nn.contains("redirect_uri not registered"),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val authorizeUriSuite = suite("SSOClient.authorizeUri")(
    test("overrideParams replace a same-named key in customParameters") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        preset = basePreset.copy(customParameters = Map("prompt" -> List("none")))
        url <- sso.authorizeUri(preset, secretClient, "challenge", state, Map("prompt" -> "login"))
      yield assertTrue(
        queryParam(url, "prompt") == Some(Chunk("login")),
      )
    },
    test("overrideParams replace ui_locales from preset.uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        preset = basePreset.copy(uiLocales = Some(List("en", "fr")))
        url <- sso.authorizeUri(preset, secretClient, "challenge", state, Map("ui_locales" -> "de"))
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("de")),
      )
    },
    test("overrideParams are added when not present in preset") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(basePreset, secretClient, "challenge", state, Map("acr_values" -> "mfa"))
      yield assertTrue(
        queryParam(url, "acr_values") == Some(Chunk("mfa")),
      )
    },
    test("omits scope when the preset does not specify one") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        url <- sso.authorizeUri(basePreset.copy(scope = Set.empty), secretClient, "challenge", state)
      yield assertTrue(queryParam(url, "scope").isEmpty)
    },
    test("empty overrideParams preserves customParameters and uiLocales") {
      for
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        preset = basePreset.copy(
          uiLocales = Some(List("en")),
          customParameters = Map("prompt" -> List("none")),
        )
        url <- sso.authorizeUri(preset, secretClient, "challenge", state, Map.empty)
      yield assertTrue(
        queryParam(url, "ui_locales") == Some(Chunk("en")),
        queryParam(url, "prompt") == Some(Chunk("none")),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val clientId = ClientId("web-app")
  private val clientSecret = Secret("s3cret".getBytes("UTF-8").nn)
  private val credential = ClientCredential.ClientSecret(clientSecret)
  private val redirectUri = RedirectUri("https://app.example/callback")

  private val tokenJson =
    """{"access_token":"at-1","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1","refresh_token_expires_in":7200,"scope":"openid","id_token":"id-1"}"""

  private def respondWith(response: Response) =
    TestClient.addRoutes(Handler.succeed(response).toRoutes)

  private def captureRequest(seen: Ref[Option[Request]], response: Response) =
    TestClient.addRoutes(
      Handler.fromFunctionZIO[Request](r =>
        r.body.asURLEncodedForm.orDie.flatMap(form =>
          seen.set(Some(r.copy(body = Body.fromURLEncodedForm(form)))).as(response),
        ),
      ).toRoutes,
    )

  private val exchangeSuite = suite("SSOClient.exchangeAuthorizationCode")(
    test("decodes the token response on success") {
      for
        _ <- respondWith(Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        result <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, credential)
      yield assertTrue(
        result.accessToken == AccessToken("at-1"),
        result.tokenType == "Bearer",
        result.expiresIn == 3600L,
        result.refreshToken == Some(RefreshToken("rt-1")),
        result.scope == Some("openid"),
        result.idToken == Some("id-1"),
      )
    },
    test("posts the authorization_code grant as a urlencoded form with basic auth") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, credential)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
      yield assertTrue(
        request.method == Method.POST,
        request.url.path.toString.endsWith("token"),
        form.get("grant_type").flatMap(_.stringValue) == Some("authorization_code"),
        form.get("code").flatMap(_.stringValue) == Some("c-1"),
        form.get("code_verifier").flatMap(_.stringValue) == Some("v-1"),
        form.get("redirect_uri").flatMap(_.stringValue) == Some(redirectUri.toString),
        request.header(Header.Authorization).contains(
          Header.Authorization.Basic(clientId, Base64.urlEncode(clientSecret)),
        ),
      )
    },
    test("fails with the error and description reported by the server") {
      for
        _ <- respondWith(
          Response.json("""{"error":"invalid_request","error_description":"code expired"}""").status(Status.BadRequest),
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, credential).flip
      yield assertTrue(
        error.getMessage.nn.contains("400"),
        error.getMessage.nn.contains("invalid_request"),
        error.getMessage.nn.contains("code expired"),
      )
    },
    test("omits the description when the server does not send one") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_client"}""").status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.exchangeAuthorizationCode(Code("c-1"), CodeVerifier("v-1"), redirectUri, clientId, credential).flip
      yield assertTrue(
        error.getMessage.nn.contains("invalid_client"),
        !error.getMessage.nn.contains(" - "),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val refreshSuite = suite("SSOClient.exchangeRefreshToken")(
    test("decodes the token response on success") {
      for
        _ <- respondWith(Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        result <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential)
      yield assertTrue(result.accessToken == AccessToken("at-1"))
    },
    test("posts the refresh_token grant as a urlencoded form") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- captureRequest(seen, Response.json(tokenJson))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        form <- request.body.asURLEncodedForm
      yield assertTrue(
        form.get("grant_type").flatMap(_.stringValue) == Some("refresh_token"),
        form.get("refresh_token").flatMap(_.stringValue) == Some("rt-0"),
      )
    },
    test("maps an invalid_grant error to InvalidGrant rather than a failure") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_grant"}""").status(Status.BadRequest))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential).flip
      yield assertTrue(error == SSOClient.InvalidGrant)
    },
    test("fails with the reported error for any other rejection") {
      for
        _ <- respondWith(Response.json("""{"error":"invalid_client"}""").status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.exchangeRefreshToken(RefreshToken("rt-0"), clientId, credential).flip
      yield assertTrue(
        error.isInstanceOf[RuntimeException],
        error.asInstanceOf[RuntimeException].getMessage.nn.contains("invalid_client"),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging

  private val userInfoSuite = suite("SSOClient.userInfo")(
    test("returns the claims object on success") {
      for
        _ <- respondWith(Response.json("""{"sub":"user-1"}"""))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        claims <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key)
      yield assertTrue(claims.get("sub").flatMap(_.asString) == Some("user-1"))
    },
    test("sends the access token as a bearer credential") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json("""{"sub":"user-1"}"""))).toRoutes,
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(
        request.url.path.toString.endsWith("userinfo"),
        request.header(Header.Authorization).exists {
          case Header.Authorization.Bearer(t) => t.stringValue == "at-1"
          case _ => false
        },
      )
    },
    test("sends an edge assertion bound to the access token it presents") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json("""{"sub":"user-1"}"""))).toRoutes,
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
        assertion <- ZIO.fromOption(request.rawHeader(EdgeAssertion.HeaderName))
          .orElseFail(new RuntimeException("no edge assertion sent"))
        edgeId <- EdgeAssertion.edgeIdOf(assertion)
        // Verified against this edge's own public key, the way auth will once central has
        // synced it -- and against the token actually presented, not merely any token.
        keys = JWT.PublicKeys.fromJson(
          Json.Obj("keys" -> Json.Arr(
            RSAKey.Builder(keyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID(config.keyId).build()
              .toJSONString.fromJson[Json.Obj].getOrElse(Json.Obj()),
          )),
        )
        accepted <- EdgeAssertion.verify(assertion, keys, "at-1").either
        rejected <- EdgeAssertion.verify(assertion, keys, "a-different-token").flip
      yield assertTrue(
        edgeId == config.id,
        accepted.isRight,
        rejected == EdgeAssertion.Error.TokenMismatch,
      )
    },
    // Unbound tokens are the common case, and auth already accepts this call over `Bearer`
    // without an assertion -- so signing one here would be a wasted RSA operation on every
    // fetchUserInfo call for a token that never needed the exemption.
    test("signs no edge assertion for a token that is not DPoP-bound") {
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(
          Handler.fromFunctionZIO[Request](r => seen.set(Some(r)).as(Response.json("""{"sub":"user-1"}"""))).toRoutes,
        )
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        _ <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Unbound)
        request <- seen.get.someOrFail(new RuntimeException("no request captured"))
      yield assertTrue(request.rawHeader(EdgeAssertion.HeaderName).isEmpty)
    },

    test("rejects a non-object JSON body") {
      for
        _ <- respondWith(Response.json("""["not","an","object"]"""))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key).flip
      yield assertTrue(error.isInstanceOf[RuntimeException])
    },
    test("maps 401 to UserInfoUnauthorized") {
      for
        _ <- respondWith(Response.status(Status.Unauthorized))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key).flip
      yield assertTrue(error == SSOClient.UserInfoUnauthorized)
    },
    test("fails for any other error status") {
      for
        _ <- respondWith(Response.status(Status.InternalServerError))
        client <- ZIO.service[Client]
        sso = SSOClient.Impl(client, config, certificateFiles)
        error <- sso.userInfo(AccessToken("at-1"), SSOClient.TokenBinding.Key).flip
      yield assertTrue(
        error.isInstanceOf[RuntimeException],
        error.asInstanceOf[RuntimeException].getMessage.nn.contains("500"),
      )
    },
  ).provideLayer(TestClient.layer) @@ TestAspect.silentLogging
