package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** JARM (JWT Secured Authorization Response Mode, `response_mode=jwt`/`query.jwt`) happy path
  * and error path: the authorization response — success or error — is returned as a single
  * signed JWT (`response=…`) rather than as individual query parameters.
  *
  * The response is signed with the same key `/.well-known/jwks.json` publishes, so a relying
  * party verifies it exactly the way [[DiscoveryAndJwksSpec]] verifies an id_token.
  */
object JarmFlowSpec extends E2ESpec:

  private def decodeJwtPayload(jwt: String): Task[Json.Obj] =
    ZIO.attempt(String(Base64.getUrlDecoder.decode(jwt.split('.')(1)), StandardCharsets.UTF_8))
      .flatMap(json => ZIO.fromEither(json.fromJson[Json.Obj]).mapError(RuntimeException(_)))

  def spec = suite("JARM (response_mode=jwt)")(

    test("a completed code-flow login returns the whole response as a signed JWT") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseMode = Some("jwt"),
        ).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        responseJwt <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf)
          .assertJarmQueryRedirect
        claims <- decodeJwtPayload(responseJwt)
        jwkSet <- auth.jwks()
        verified <- auth.verifyJwtSignature(responseJwt, jwkSet)
        code <- ZIO.fromOption(claims.get("code").flatMap(_.as[String].toOption))
          .orElseFail(RuntimeException(s"JARM response JWT has no 'code' claim: ${claims.toJson}"))
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(verified)
        .label("the response JWT must verify against the published JWKS") &&
        assertTrue(claims.get("iss").flatMap(_.as[String].toOption).isDefined)
          .label(s"the response JWT must carry an 'iss' claim: ${claims.toJson}") &&
        assertTrue(claims.get("state").flatMap(_.as[String].toOption).contains(authorize.state))
          .label(s"the response JWT 'state' claim must equal the sent state, got ${claims.toJson}") &&
        assertTrue(userinfo.sub == s.userId)
          .label("the JARM-carried code must exchange for a token resolving to the same 'sub'")
    },

    test("a protocol error is still delivered as a signed JWT, not as bare query parameters") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        result <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          responseMode = Some("jwt"),
          omitCodeChallenge = true,
        )
        responseJwt <- result.assertJarmQueryRedirect
        claims <- decodeJwtPayload(responseJwt)
        jwkSet <- auth.jwks()
        verified <- auth.verifyJwtSignature(responseJwt, jwkSet)
      yield assertTrue(verified)
        .label("the error response JWT must also verify against the published JWKS") &&
        assertTrue(claims.get("error").flatMap(_.as[String].toOption).contains("invalid_request"))
          .label(s"expected error='invalid_request' inside the response JWT, got ${claims.toJson}") &&
        assertTrue(result.response.header(zio.http.Header.Location).exists(!_.url.encode.contains("error=invalid_request")))
          .label("the error must not additionally appear as a bare, unsigned query parameter")
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
