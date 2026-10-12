package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The routing key a multi-cell deployment writes at the front of its opaque values and the
  * `cell` JOSE header parameter of its access tokens (versolauth/versola#547 §6.2, #558): the
  * staged `auth` runs as cell `local-1` (`scripts/gen-env.scala`), so every code, refresh
  * token and `request_uri` it issues names that cell, and every one of them redeems as before.
  */
object RoutingKeySpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private val offlineScope = "openid email offline_access"

  private def prefixOf(auth: OAuthClient): String = s"v1.${auth.cellId}."

  /** The JOSE header of `accessToken`, where a cell names itself; the claims are left alone. */
  private def header(accessToken: String): Task[Json.Obj] =
    for
      segment <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(0)), "UTF-8"))
        .mapError(error => RuntimeException(s"Access token is not a JWT [$error]: $accessToken"))
      json <- ZIO.fromEither(segment.fromJson[Json.Obj])
        .mapError(error => RuntimeException(s"Access token header is not a JSON object [$error]: $segment"))
    yield json

  private def cellHeader(accessToken: String): Task[Option[String]] =
    header(accessToken).map(_.get("cell").collect { case Json.Str(value) => value })

  private def refreshTokenOf(tokens: TokenResult.Success): Task[String] =
    ZIO.fromOption(tokens.refreshToken)
      .orElseFail(RuntimeException("Expected a refresh token for the offline_access scope"))

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  private def rejection(result: TokenResult): Task[(Status, String)] =
    result match
      case s: TokenResult.Success =>
        ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(response, body) =>
        ZIO.fromEither(body.fromJson[OAuthError])
          .mapBoth(
            err => RuntimeException(s"Unparsable /token error body [$err]: $body"),
            parsed => response.status -> parsed.error,
          )

  /** Finishes the email+OTP conversation `cookie` belongs to and returns the code it ends in. */
  private def loginWithOtp(s: Flows.Setup, auth: OAuthClient, cookie: String): Task[String] =
    for
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, s.email.get, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- auth.submitOtp(cookie, fixedOtp, otp.csrf).assertRedirect
    yield code

  def spec = suite("Routing key and cell header")(
    test("the code, both refresh tokens and the header of both access tokens of a login name the staged cell") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(
          scope = offlineScope,
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        code <- loginWithOtp(s, auth, authorize.conversationCookie.get)
        tokens <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        refreshToken <- refreshTokenOf(tokens)
        cell <- cellHeader(tokens.accessToken)
        refreshed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
        rotated <- refreshTokenOf(refreshed)
        refreshedCell <- cellHeader(refreshed.accessToken)
        userinfo <- auth.userinfo(refreshed.accessToken).success
      yield assertTrue(code.startsWith(prefixOf(auth)))
        .label(s"the authorization code must start with ${prefixOf(auth)}, got $code") &&
        assertTrue(refreshToken.startsWith(prefixOf(auth)))
          .label(s"the refresh token must start with ${prefixOf(auth)}") &&
        assertTrue(cell.contains(auth.cellId))
          .label(s"the access token header must carry cell=${auth.cellId}, got $cell") &&
        assertTrue(rotated.startsWith(prefixOf(auth)))
          .label(s"the rotated refresh token must start with ${prefixOf(auth)}") &&
        assertTrue(refreshedCell.contains(auth.cellId))
          .label(s"the refreshed access token header must carry cell=${auth.cellId}, got $refreshedCell") &&
        assertTrue(userinfo.sub == s.userId)
          .label("the refreshed access token must still resolve to the user")
    },
    test("a pushed request_uri names the cell inside the RFC 9126 namespace and redeems through to tokens") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        pushed <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          scope = offlineScope,
        ).success
        authorize <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          requestUri = Some(pushed.requestUri),
        ).assertChallengeRedirect
        code <- loginWithOtp(s, auth, authorize.conversationCookie.get)
        tokens <- auth.token(
          code,
          pushed.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        refreshToken <- refreshTokenOf(tokens)
        cell <- cellHeader(tokens.accessToken)
      yield assertTrue(pushed.requestUri.startsWith("urn:ietf:params:oauth:request_uri:" + prefixOf(auth)))
        .label(s"the request_uri must keep the RFC 9126 namespace ahead of the routing key, got ${pushed.requestUri}") &&
        assertTrue(code.startsWith(prefixOf(auth)))
          .label("the code issued for a pushed request must name the cell too") &&
        assertTrue(refreshToken.startsWith(prefixOf(auth))) &&
        assertTrue(cell.contains(auth.cellId))
    },
    test("a refresh token presented without its routing key is refused, and the intact one still works") {
      // The MAC a refresh token is stored under covers the whole presented string, prefix
      // included: stripped of it, the value is read the way a token issued before cells
      // existed would be, and no such token was ever issued under these bytes.
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(
          scope = offlineScope,
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        code <- loginWithOtp(s, auth, authorize.conversationCookie.get)
        tokens <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        refreshToken <- refreshTokenOf(tokens)
        stripped = refreshToken.stripPrefix(prefixOf(auth))
        refused <- auth.refresh(stripped, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).flatMap(rejection)
        // An unknown value is not a replay, so the family the real token belongs to is intact.
        refreshed <- auth.refresh(refreshToken, clientId = Some(s.clientId), clientSecret = Some(s.clientSecret)).success
      yield assertTrue(stripped != refreshToken).label("the test must actually have stripped a prefix") &&
        assertTrue(refused == (Status.BadRequest, "invalid_grant"))
          .label(s"a prefix-stripped refresh token must be refused as invalid_grant, got $refused") &&
        assertTrue(refreshed.refreshToken.exists(_.startsWith(prefixOf(auth))))
    },
  )
