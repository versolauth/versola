package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
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

    test("missing code_challenge redirects with error=invalid_request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
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
