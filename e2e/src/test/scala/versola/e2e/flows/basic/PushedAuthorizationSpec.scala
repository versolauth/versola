package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** RFC 9126 Pushed Authorization Requests: POST /par and its redemption via
  * `request_uri` at GET /authorize.
  */
object PushedAuthorizationSpec extends E2ESpec:

  def spec = suite("Pushed Authorization Requests")(

    test("push + redeem: request_uri carries the pushed parameters through to /authorize") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
        ).success
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        csrf = challenge.csrf
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
      yield assertTrue(pushed.expiresIn > 0).label("expires_in must be positive") &&
        assertTrue(code.nonEmpty).label("code must not be empty")
    },

    test("push + redeem via client_secret_post authentication") {
      // client_secret must be accepted as an authentication credential (RFC 6749 §2.3.1)
      // and stripped from the persisted authorization parameters afterward.
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          useBasicAuth = false,
        ).success
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
        ).assertChallengeRedirect
      yield assertTrue(authorize.conversationCookie.isDefined)
    },

    test("request_uri is single-use: second redemption fails") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
        ).success
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
        ).assertChallengeRedirect
        second <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
        )
      yield assertTrue(second.response.status == Status.BadRequest)
        .label(s"expected 400 on reuse, got ${second.response.status}")
    },

    test("redemption fails when client_id does not match the client that pushed the request") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        (other, _) <- setup(Flows.Id.EmailOtp)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
        ).success
        result <- auth.authorizeRaw(
          clientId = other.clientId,
          redirectUri = other.redirectUri,
          requestUri = Some(pushed.requestUri),
        )
      yield assertTrue(result.response.status == Status.BadRequest)
        .label(s"expected 400 for mismatched client_id, got ${result.response.status}")
    },

    test("redemption fails when client_id is omitted") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
        ).success
        result <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
          omitClientId = true,
        )
      yield assertTrue(result.response.status == Status.BadRequest)
        .label(s"expected 400 for missing client_id, got ${result.response.status}")
    },

    test("unknown request_uri returns 400 at /authorize") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some("urn:ietf:params:oauth:request_uri:does-not-exist"),
        )
      yield assertTrue(result.response.status == Status.BadRequest)
    },

    test("PAR endpoint rejects invalid client credentials with 401 and a WWW-Authenticate challenge") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = "wrong-secret",
          redirectUri = s.redirectUri,
        )
      yield assertTrue(pushed.response.status == Status.Unauthorized) &&
        assertTrue(pushed.response.header(zio.http.Header.WWWAuthenticate).isDefined)
          .label("401 from /par must include a WWW-Authenticate challenge")
    },

    test("PAR endpoint rejects a request_uri parameter in the pushed body") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          extraParams = Map("request_uri" -> "urn:ietf:params:oauth:request_uri:whatever"),
        )
      yield assertTrue(pushed.response.status == Status.BadRequest)
    },

    test("PAR endpoint rejects code_challenge_method=plain") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          extraParams = Map("code_challenge_method" -> "plain"),
        )
        error = pushed match
          case PushedAuthorizationResult.Failure(_, _, code) => code
          case _ => None
      yield assertTrue(
        pushed.response.status == Status.BadRequest,
        error.contains("invalid_request"),
      )
    },

    test("non-POST /par returns 405 with an Allow header") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.parRaw(zio.http.Method.GET)
      yield assertTrue(response.status == Status.MethodNotAllowed) &&
        assertTrue(response.header(zio.http.Header.Allow).isDefined)
          .label("405 from /par must include an Allow header")
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
