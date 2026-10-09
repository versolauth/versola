package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Status, URL}
import zio.test.*

/** Negative test cases for the /authorize endpoint.
  *
  * Covers RFC 6749 / OAuth 2.1 error responses:
  *   - 400 Bad Request when client_id or redirect_uri is invalid (no safe redirect target)
  *   - Redirect with `error` param for all other protocol violations
  */
object AuthorizeNegativeSpec extends E2ESpec:

  def spec = suite("Authorize - negative cases")(

    test("unknown client_id returns 400") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- auth.authorizeRaw(clientId = "no-such-client", redirectUri = s.redirectUri)
      yield assertTrue(result.response.status == Status.BadRequest)
    },

    test("unregistered redirect_uri returns 400") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- auth.authorizeRaw(clientId = s.clientId, redirectUri = "http://localhost:9999/not-registered")
      yield assertTrue(result.response.status == Status.BadRequest)
    },

    test("unsupported response_type redirects with error=unsupported_response_type") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseType = Some("token"),
        ).assertErrorRedirect("unsupported_response_type")
      yield assertCompletes
    },

    test("missing code_challenge from a public client redirects with error=invalid_request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        publicClientId <- auth.registerPublicClient(s.redirectUri)
        _ <- auth.authorizeRaw(
          clientId = publicClientId,
          redirectUri = s.redirectUri,
          omitCodeChallenge = true,
        ).assertErrorRedirect("invalid_request")
      yield assertCompletes
    },

    // OIDC Core §3.1.2.1: these are hints the server may act on or not. The conformance suite sends a
    // locale no tenant has, an identifier the client's flow does not take, and acr values the tenant does
    // not define, and expects the login to go ahead rather than an error.
    test("an unsupported ui_locales does not refuse the request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, uiLocales = Some("se"))
          .assertChallengeRedirect
      yield assertCompletes
    },

    test("a login_hint the client's flow does not take does not refuse the request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, loginHint = Some("buffy@example.test"))
          .assertChallengeRedirect
      yield assertCompletes
    },

    test("acr_values the tenant does not define do not refuse the request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, acrValues = Some("1 2"))
          .assertChallengeRedirect
      yield assertCompletes
    },

    // A native client behind edge is confidential on paper (`tls_client_auth`), but the
    // certificate is edge's and the same for every install, so it proves nothing about the app.
    // The rule reads `applicationType`, which only reaches auth through central's sync -- so this
    // is the case that fails if that column is lost on the way. The description is asserted, not
    // just the code: a native client also has to push (`/par`), and an `invalid_request` for
    // that would pass without PKCE ever being the reason.
    test("an edge-fronted native client of a standard tenant must still send code_challenge") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        clientId <- CentralApi.id("e2e-native")
        _ <- auth.registerClient(
          clientId,
          "Native client",
          // Central accepts only an https App Link for an edge-fronted native client.
          Set(EdgeFixture.nativeRedirectUri),
          authFlow = Some(Flows.loginPasswordAuthFlow),
          authMethod = "tls_client_auth",
          applicationType = Some("native"),
          dpopBoundAccessTokens = true,
          requirePushedAuthorizationRequests = true,
          enrollEdgeClientCertificate = true,
        ).success
        _ <- auth.syncConfiguration()
        result <- auth.authorizeRaw(clientId = clientId, redirectUri = EdgeFixture.nativeRedirectUri, omitCodeChallenge = true)
        _ <- result.assertErrorRedirect("invalid_request")
        url <- ZIO.fromEither(URL.decode(result.location)).mapError(RuntimeException(_))
        description <- url.queryZIO[String]("error_description")
      yield assertTrue(description.contains("code_challenge"))
        .label(s"the refusal must be about the missing code_challenge, got '$description'")
    },

    // OAuth 2.1 §7.5.2: the one client that may omit PKCE is a confidential one of a `standard`
    // tenant, whose secret already proves who redeems the code. The suite's own clients are such.
    test("a confidential client of a standard tenant may omit code_challenge and redeem without a verifier") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          omitCodeChallenge = true,
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
        issued <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
          omitVerifier = true,
        ).success
      yield assertTrue(issued.accessToken.nonEmpty)
    },

    // RFC 7636 §4.6: a verifier presented for a code that committed to no challenge is a client
    // that thinks it is protected and is not, so it is refused rather than ignored.
    test("a verifier presented for a code that committed to no challenge is refused with invalid_grant") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          omitCodeChallenge = true,
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
        result <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        )
      yield assertTrue(result match
        case TokenResult.Failure(response, body) => response.status == Status.BadRequest && body.contains("invalid_grant")
        case _ => false,
      )
    },

    test("code_challenge_method=plain redirects with error=invalid_request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          codeChallengeMethod = Some("plain"),
        ).assertErrorRedirect("invalid_request")
      yield assertCompletes
    },

    test("missing code_challenge_method redirects with error=invalid_request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          codeChallengeMethod = None,
        ).assertErrorRedirect("invalid_request")
      yield assertCompletes
    },

    test("prompt=none without session redirects with error=login_required") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          prompt = Some("none"),
        ).assertErrorRedirect("login_required")
      yield assertCompletes
    },

    test("prompt=none without session redirects with fragment error=login_required for hybrid response_type") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseType = Some("code id_token"),
          prompt = Some("none"),
          nonce = Some("test-nonce"),
        ).assertFragmentErrorRedirect("login_required")
      yield assertCompletes
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
