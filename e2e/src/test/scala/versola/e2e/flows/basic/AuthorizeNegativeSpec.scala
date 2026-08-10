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

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
