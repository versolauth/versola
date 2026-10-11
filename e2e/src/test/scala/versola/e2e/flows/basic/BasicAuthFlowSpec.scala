package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Header
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** Happy-path authorization code + PKCE flows. */
object BasicAuthFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  private def idTokenClaims(idToken: String): Task[Json.Obj] =
    ZIO.attempt(String(Base64.getUrlDecoder.decode(idToken.split('.')(1)), StandardCharsets.UTF_8))
      .flatMap(json => ZIO.fromEither(json.fromJson[Json.Obj]).mapError(RuntimeException(_)))

  /** An email-OTP login for `scope`, with `claims` as the request's `claims` parameter if given,
    * redeemed for tokens and read back as (ID Token claims, UserInfo email). */
  private def loginForClaims(scope: String, claims: Option[String]): ZIO[Flows.Setups, Throwable, (Json.Obj, Option[String])] =
    for
      (s, auth) <- setup(Flows.Id.EmailOtp)
      authorize <- auth.authorize(scope = scope, clientId = Some(s.clientId), redirectUri = Some(s.redirectUri), claims = claims)
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, s.email.get, challenge1.csrf)
      challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      code <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("no id_token for an openid scope"))
      claimsInIdToken <- idTokenClaims(idToken)
      userinfo <- auth.userinfo(token.accessToken).success
    yield (claimsInIdToken, userinfo.email)

  def spec = suite("Basic Authorization Flow")(
    test("login + password: complete login flow") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        csrf = challenge.csrf
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, csrf).assertRedirect(
          auth,
          authorize.conversationCookie.get,
        )
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.tokenType.toLowerCase == "bearer")
        .label("token_type must be 'bearer'") &&
        assertTrue(token.accessToken.nonEmpty)
          .label("access_token must not be empty") &&
        // RFC 6749 §5.1: a response carrying tokens must not be cached -- what the conformance
        // suite's oidcc-refresh-token checks, and over real HTTP rather than a handler.
        assertTrue(token.response.headers.get(Header.CacheControl).contains(Header.CacheControl.NoStore))
          .label("the token response must carry Cache-Control: no-store") &&
        assertTrue(token.response.headers.get(Header.Pragma).contains(Header.Pragma.NoCache))
          .label("the token response must carry Pragma: no-cache")
    },
    // OIDC Core §5.4: with an access token issued, the claims a scope stands for are UserInfo's;
    // the ID Token does not repeat them (the conformance suite warns when it does).
    test("scope claims are returned from UserInfo and not repeated in the ID Token") {
      for
        (idTokenClaims, userinfoEmail) <- loginForClaims("openid email", None)
      yield assertTrue(userinfoEmail.nonEmpty)
        .label("UserInfo must carry the email the scope stands for") &&
        assertTrue(!idTokenClaims.fields.exists(_._1 == "email"))
          .label(s"the ID Token must not carry the scope's email claim, got ${idTokenClaims.toJson}") &&
        assertTrue(idTokenClaims.fields.exists(_._1 == "sub"))
          .label("the ID Token still identifies the subject")
    },
    // §5.5: what the request names in `claims.id_token` is what the ID Token carries.
    test("a claim named in claims.id_token is carried in the ID Token") {
      for
        (idTokenClaims, _) <- loginForClaims(
          "openid email",
          Some("""{"userinfo":{},"id_token":{"email":{"essential":true}}}"""),
        )
      yield assertTrue(idTokenClaims.fields.exists(_._1 == "email"))
        .label(s"claims.id_token asked for email, so the ID Token must carry it, got ${idTokenClaims.toJson}")
    }, // The conformance suite estimates a code's entropy from its encoded form and fails a 22-character
    // (16-byte) one at random, so the length that reaches the client over HTTP is what matters -- the
    // generator's unit test only proves what it asked the random source for.
    test("the authorization code issued over HTTP is 32 bytes, 43 base64url characters") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(
          clientId = Some(s.clientId),
          redirectUri = Some(s.redirectUri),
        ).assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(code.length == 43)
        .label(s"a 32-byte code is 43 base64url characters, got ${code.length}: '$code'") &&
        assertTrue(code.matches("[A-Za-z0-9_-]{43}"))
          .label("the code must be unpadded base64url") &&
        assertTrue(token.accessToken.nonEmpty)
          .label("a code of the new length must still redeem")
    },
    // OIDC Core §5.5: a `claims` request may name just one member, and a claim's value may be null. The
    // request is stored with the conversation and read back at the token step, so completing the login
    // is what shows the stored shape round-trips through Postgres, not only that the parser accepts it.
    test("a claims request that names only the userinfo member completes the login") {
      for
        (_, userinfoEmail) <- loginForClaims("openid email", Some("""{"userinfo":{"email":{"essential":true}}}"""))
      yield assertTrue(userinfoEmail.nonEmpty)
        .label("UserInfo must carry the email the request asked for")
    },
    test("a claims request whose claim is null completes the login") {
      for
        (_, userinfoEmail) <- loginForClaims("openid email", Some("""{"userinfo":{"email":null}}"""))
      yield assertTrue(userinfoEmail.nonEmpty)
        .label("a null claim is the claim with no constraints, so UserInfo still carries it")
    },
    // The token is read from the Authorization header only; a valid one in a form body is refused
    // (the conformance suite's oidcc-userinfo-post-body records that as a warning, the accepted result).
    test("UserInfo refuses an access token that is only in a form body") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        challenge <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(authorize.conversationCookie.get, s.login.get, s.password, challenge.csrf)
          .assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        viaBody <- auth.userinfoTokenInBodyOnly(token.accessToken)
        viaHeader <- auth.userinfo(token.accessToken)
      yield assertTrue(viaBody.response.status == zio.http.Status.Unauthorized)
        .label(s"a token only in the form body must be refused, got ${viaBody.response.status}") &&
        assertTrue(viaHeader.isInstanceOf[UserinfoResult.Success])
          .label("the same token in the Authorization header must still be accepted")
    },
    // The suite's clients show no consent screen, so they are first-party: a claim named in `claims` is
    // released from any scope registered for the client, here `email` with only `openid` granted.
    // (oidcc-claims-essential asks for `name` that way.)
    test("a first-party client gets a claim it names in claims.userinfo without the scope granted") {
      for
        (_, userinfoEmail) <- loginForClaims("openid", Some("""{"userinfo":{"email":{"essential":true}}}"""))
      yield assertTrue(userinfoEmail.nonEmpty)
        .label("a first-party client's claims request must release email although only openid was granted")
    },
    // The same rule for the ID Token: `email` is registered for the client but only `openid` is granted.
    test("a first-party client gets a claim it names in claims.id_token without the scope granted") {
      for
        (idTokenClaims, _) <- loginForClaims("openid", Some("""{"id_token":{"email":{"essential":true}}}"""))
      yield assertTrue(idTokenClaims.fields.exists(_._1 == "email"))
        .label(s"a first-party client's claims.id_token request must put email in the ID Token, got ${idTokenClaims.toJson}")
    },
    test("otp + permanent password: complete otp flow") {
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        challenge1 <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Credential)
        csrf1 = challenge1.csrf
        _ <- auth.submitEmail(authorize.conversationCookie.get, s.email.get, csrf1)
        challenge2 <- auth.getChallenge(authorize.conversationCookie.get).assertStep(ConversationStep.Otp)
        csrf2 = challenge2.csrf
        code <- auth.submitOtp(authorize.conversationCookie.get, fixedOtp, csrf2).assertRedirect(auth, authorize.conversationCookie.get)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
        userinfo <- auth.userinfo(token.accessToken).success
      yield assertTrue(userinfo.sub == s.userId)
        .label(s"userinfo 'sub' must equal registered userId ${s.userId}, got ${userinfo.sub}") &&
        assertTrue(userinfo.email.contains(s.email.get))
          .label(s"userinfo 'email' must be ${s.email.get}")
    },
    test("unknown credential without registration rejects the fake OTP") {
      val unknownEmail = s"unknown-${UUID.randomUUID()}@example.test"
      for
        (s, auth) <- setup(Flows.Id.EmailOtp)
        authorize <- auth.authorize(scope = "openid email", clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
          .assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge1 <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        _ <- auth.submitEmail(cookie, unknownEmail, challenge1.csrf)
        challenge2 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
        _ <- auth.submitOtp(cookie, fixedOtp, challenge2.csrf)
        challenge3 <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      yield assertTrue(challenge3.html.contains("Invalid verification code"))
        .label("an unknown credential must reject the fake OTP")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
