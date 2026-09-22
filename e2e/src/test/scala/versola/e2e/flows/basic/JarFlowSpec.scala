package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** RFC 9101 (JWT Secured Authorization Request, "JAR") end to end: a `request` object signed
  * with the client's registered key, submitted to `GET /authorize` and to `POST /par`.
  *
  * The unit suites already prove the verification rules (signature, `iss`/`aud`/`exp`,
  * `client_id` agreement, precedence over query parameters) against a stubbed configuration
  * service. What only this level can show is that a JWK Set survives the trip through
  * Central's registration API and auth's configuration cache, and that a real login flow
  * completes off the parameters a request object -- not the query string -- carried.
  */
object JarFlowSpec extends E2ESpec:

  private val redirectUri = "http://localhost:3000"

  private def decodeJwtPayload(jwt: String): String =
    String(
      java.util.Base64.getUrlDecoder.decode(jwt.split('.')(1)),
      java.nio.charset.StandardCharsets.UTF_8,
    )

  /** Only what this suite asserts on: a `nonce` the client never signed must not be there. */
  private case class IdTokenClaims(sub: String, nonce: Option[String] = None) derives JsonDecoder

  private def uid: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  /** A client that registered a key set, plus a user able to complete the login-password
    * flow -- everything a JAR test needs beyond the object itself.
    */
  private def jarClient(auth: OAuthClient, signer: AssertionSigner): Task[Flows.Setup] =
    for
      suffix <- uid
      clientId = s"jar-client-$suffix"
      login = s"jar-user-$suffix"
      password = s"Pass-$suffix-1!"
      clientResult <- auth.registerClient(
        clientId,
        "JAR Test Client",
        Set(redirectUri),
        authFlow = Some(Flows.loginPasswordAuthFlow),
        jwks = Some(signer.jwks),
      ).success
      userId <- auth.registerUser(login = Some(login))
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
      _ <- auth.syncConfiguration()
    yield Flows.Setup(clientId, clientResult.secret, redirectUri, userId, Some(login), None, None, password)

  /** The same client, registered as one that may only state its request in a signed object
    * (RFC 9101 §10.5).
    */
  private def signingOnlyClient(auth: OAuthClient, signer: AssertionSigner): Task[Flows.Setup] =
    for
      suffix <- uid
      clientId = s"jar-required-$suffix"
      login = s"jar-required-user-$suffix"
      password = s"Pass-$suffix-1!"
      clientResult <- auth.registerClient(
        clientId,
        "JAR Required Client",
        Set(redirectUri),
        authFlow = Some(Flows.loginPasswordAuthFlow),
        jwks = Some(signer.jwks),
        requireSignedRequestObject = true,
      ).success
      userId <- auth.registerUser(login = Some(login))
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
      _ <- auth.syncConfiguration()
    yield Flows.Setup(clientId, clientResult.secret, redirectUri, userId, Some(login), None, None, password)

  /** The claims of a request object carrying a whole authorization request, addressed at
    * `client`. The object -- not the query string -- is what auth checks PKCE against, so
    * the `code_challenge` here must be the one whose verifier the caller later redeems with.
    */
  private def requestClaims(
      client: Flows.Setup,
      auth: OAuthClient,
      codeChallenge: String,
      overrides: (String, Json)*,
  ): Seq[(String, Json)] =
    val defaults = Seq(
      "iss" -> Json.Str(client.clientId),
      "aud" -> Json.Str(auth.issuer),
      "exp" -> Json.Num(java.time.Instant.now.plusSeconds(60).getEpochSecond),
      "client_id" -> Json.Str(client.clientId),
      "redirect_uri" -> Json.Str(client.redirectUri),
      "response_type" -> Json.Str("code"),
      "scope" -> Json.Str("openid"),
      "state" -> Json.Str(s"from-the-object-${UUID.randomUUID()}"),
      "code_challenge" -> Json.Str(codeChallenge),
      "code_challenge_method" -> Json.Str("S256"),
    )
    val overridden = overrides.map(_._1).toSet
    defaults.filterNot((name, _) => overridden.contains(name)) ++ overrides

  def spec = suite("Request Objects (RFC 9101, JAR)")(

    test("GET /authorize accepts a signed request object and completes the login flow") {
      val (verifier, codeChallenge) = PkceHelper.generate()
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- jarClient(auth, signer)
        requestObject <- signer.requestObject(requestClaims(client, auth, codeChallenge)*)()
        authorize <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          request = Some(requestObject),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, client.login.get, client.password, challenge.csrf)
          .assertRedirect(auth, cookie)
        // Registering a key set is what lets this client sign a request object, and it is also
        // what stops it authenticating by secret -- so the code is redeemed with an assertion.
        tokenAssertion <- signer.assertion(client.clientId, s"${auth.issuer}/token")
        token <- auth.token(
          code,
          verifier,
          clientId = Some(client.clientId),
          redirectUri = Some(client.redirectUri),
          assertion = Some(tokenAssertion),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(userinfo.sub == client.userId)
        .label("the code redeemed off the request object must resolve to the same user")
    },

    // Reaching /challenge proves nothing here -- a server that merged the query parameters
    // would redirect just the same. The two unsigned parameters are therefore ones whose
    // effect is visible in what comes back: `state` is echoed in the final redirect, and a
    // `nonce` that reached the request would appear as a claim of the id_token.
    test("a query parameter cannot repeat or add to what the object carried") {
      val (verifier, codeChallenge) = PkceHelper.generate()
      val signedState = s"from-the-object-${UUID.randomUUID()}"
      val injectedNonce = s"injected-nonce-${UUID.randomUUID()}"
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- jarClient(auth, signer)
        requestObject <- signer.requestObject(
          requestClaims(client, auth, codeChallenge, "state" -> Json.Str(signedState))*,
        )()
        authorize <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          request = Some(requestObject),
          // An addition the object never made. `authorizeRaw` also sends its own random
          // `state`, which is the contradiction of a signed parameter.
          nonce = Some(injectedNonce),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        submitted <- auth.submitLoginPassword(cookie, client.login.get, client.password, challenge.csrf)
        code <- submitted.assertRedirect
        returnedState <- ZIO.fromEither(URL.decode(submitted.location))
          .map(_.queryParam("state"))
        tokenAssertion <- signer.assertion(client.clientId, s"${auth.issuer}/token")
        token <- auth.token(
          code,
          verifier,
          clientId = Some(client.clientId),
          redirectUri = Some(client.redirectUri),
          assertion = Some(tokenAssertion),
        ).success
        idToken <- ZIO.fromOption(token.idToken)
          .orElseFail(RuntimeException("expected an id_token for the openid scope the object signed"))
        claims <- ZIO.fromEither(decodeJwtPayload(idToken).fromJson[IdTokenClaims])
          .mapError(RuntimeException(_))
      yield assertTrue(returnedState.contains(signedState))
        .label(s"the signed state must be what comes back, got $returnedState instead of $signedState") &&
        assertTrue(returnedState != Some(authorize.state))
          .label("the query string's own state must not have reached the request") &&
        assertTrue(claims.nonce.isEmpty)
          .label(s"an unsigned nonce must not reach the request, yet the id_token carries ${claims.nonce}")
    },

    test("an object naming a different client than the request around it is refused") {
      val (_, codeChallenge) = PkceHelper.generate()
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- jarClient(auth, signer)
        requestObject <- signer.requestObject(
          requestClaims(client, auth, codeChallenge, "client_id" -> Json.Str("other-client"))*,
        )()
        result <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          request = Some(requestObject),
        )
      yield assertTrue(result.response.status.isClientError)
        .label(s"expected a 4xx for a client_id mismatch, got ${result.response.status}")
    },

    test("an object signed by a key the client never registered is refused") {
      val (_, codeChallenge) = PkceHelper.generate()
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- jarClient(auth, signer)
        foreign <- signer.foreignKey
        requestObject <- signer.requestObject(requestClaims(client, auth, codeChallenge)*)(signWith = Some(foreign))
        result <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          request = Some(requestObject),
        )
      yield assertTrue(result.response.status.isClientError)
        .label(s"expected a 4xx for an unregistered signing key, got ${result.response.status}")
    },

    test("a client that registered no key cannot push a request object") {
      val (_, codeChallenge) = PkceHelper.generate()
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        requestObject <- signer.requestObject(
          "iss" -> Json.Str(s.clientId),
          "aud" -> Json.Str(auth.issuer),
          "exp" -> Json.Num(java.time.Instant.now.plusSeconds(60).getEpochSecond),
          "client_id" -> Json.Str(s.clientId),
          "redirect_uri" -> Json.Str(s.redirectUri),
          "response_type" -> Json.Str("code"),
          "scope" -> Json.Str("openid"),
          "code_challenge" -> Json.Str(codeChallenge),
          "code_challenge_method" -> Json.Str("S256"),
        )()
        result <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          request = Some(requestObject),
        )
      yield assertTrue(result.response.status.isClientError)
        .label(s"expected a 4xx for a client with no registered key, got ${result.response.status}")
    },

    test("/par authenticates the pusher by assertion and stores what the pushed object stated") {
      val (verifier, codeChallenge) = PkceHelper.generate()
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- jarClient(auth, signer)
        requestObject <- signer.requestObject(requestClaims(client, auth, codeChallenge)*)()
        assertion <- signer.assertion(client.clientId, s"${auth.issuer}/par")
        pushed <- auth.pushAuthorization(
          client.clientId,
          "",
          client.redirectUri,
          assertion = Some(assertion),
          extraParams = Map("request" -> requestObject),
        ).success
        authorize <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          requestUri = Some(pushed.requestUri),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, client.login.get, client.password, challenge.csrf)
          .assertRedirect(auth, cookie)
        tokenAssertion <- signer.assertion(client.clientId, s"${auth.issuer}/token")
        token <- auth.token(
          code,
          verifier,
          clientId = Some(client.clientId),
          redirectUri = Some(client.redirectUri),
          assertion = Some(tokenAssertion),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("redeeming the request_uri must not need to re-verify the object's own exp")
    },

    test("a client registered as require_signed_request_object is refused a plain request") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- signingOnlyClient(auth, signer)
        _ <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
        ).assertErrorRedirect("invalid_request")
      yield assertCompletes
    },

    test("a client registered as require_signed_request_object still completes with an object") {
      val (_, codeChallenge) = PkceHelper.generate()
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- signingOnlyClient(auth, signer)
        requestObject <- signer.requestObject(requestClaims(client, auth, codeChallenge)*)()
        authorize <- auth.authorizeRaw(
          clientId = client.clientId,
          redirectUri = client.redirectUri,
          request = Some(requestObject),
        ).assertChallengeRedirect
      yield assertTrue(authorize.conversationCookie.isDefined)
        .label("the requirement must not stand in the way of a request that meets it")
    },

    test("a client registered as require_signed_request_object cannot push a plain parameter set") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        client <- signingOnlyClient(auth, signer)
        assertion <- signer.assertion(client.clientId, s"${auth.issuer}/par")
        pushed <- auth.pushAuthorization(
          client.clientId,
          "",
          client.redirectUri,
          assertion = Some(assertion),
        )
        error = pushed match
          case PushedAuthorizationResult.Failure(_, _, code) => code
          case _ => None
      yield assertTrue(
        pushed.response.status == zio.http.Status.BadRequest,
        error.contains("invalid_request"),
      ).label(s"expected /par to refuse an unsigned push, got ${pushed.response.status}")
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
