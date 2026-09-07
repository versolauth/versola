package versola.e2e.flows.sso

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** Single Sign-On across two different registered clients.
  *
  * `/authorize` resolves the `SSO_SESSION` cookie without scoping the lookup to the client that
  * created it, so a second client presenting the same cookie authorizes against the session
  * established by the first one. What the second client still has to satisfy on its own is
  * everything the session does not carry: its own required factors, its own `acr_values`, and its
  * own consent grant.
  */
object CrossClientSsoFlowSpec extends E2ESpec:

  // In non-prod the OTP is always the first N digits of "1234567890"; default length is 6.
  private val fixedOtp = "123456"

  /** The id_token claims this spec compares between the two clients. `aud` is a bare JSON value
    * because a single-audience token serializes it as a string rather than an array.
    */
  private case class IdTokenClaims(sub: String, aud: Json, sid: Option[String]) derives JsonDecoder

  private case class AccessTokenClaims(sub: String, client_id: String) derives JsonDecoder

  /** Client A signed in, plus the tokens it exchanged its code for. */
  private case class ClientALogin(userId: UUID, sessionCookie: String, idToken: String, accessToken: String)

  private def claimsOf[A: JsonDecoder](jwt: String): Task[A] =
    for
      payload <- ZIO.attempt(String(Base64.getUrlDecoder.decode(jwt.split('.')(1)), StandardCharsets.UTF_8))
      claims <- ZIO.fromEither(payload.fromJson[A])
        .mapError(error => RuntimeException(s"Unexpected JWT claims [$error]: $payload"))
    yield claims

  private def audienceOf(claims: IdTokenClaims): List[String] = claims.aud match
    case Json.Str(single) => List(single)
    case Json.Arr(values) => values.collect { case Json.Str(value) => value }.toList
    case _                => Nil

  /** Registers a second client in the same tenant, reachable by the same browser session.
    *
    * Inline rather than through `Flows`: every scenario needs its own client so that a consent
    * grant or a registered participation from one test cannot decide another.
    */
  private def registerClientB(
      auth: OAuthClient,
      redirectUri: String,
      consentFlow: Option[Json] = None,
      allowedScopes: Set[String] = Set("openid", "email"),
  ): Task[(String, String)] =
    val clientId = s"sso-client-b-${UUID.randomUUID().toString.replace("-", "").take(8)}"
    for
      result <- auth.registerClient(
        clientId,
        "Cross-Client SSO Client B",
        Set(redirectUri),
        allowedScopes = allowedScopes,
        authFlow = Some(Flows.emailOtpAuthFlow),
        consentFlow = consentFlow,
      ).success
      _ <- auth.syncConfiguration()
    yield (clientId, result.secret)

  /** Full interactive email+OTP login with client A, for a user created just for this test.
    *
    * A per-test user keeps the resulting session out of the way of every other spec: sibling
    * sessions of the same user under the same user agent are invalidated when a new one is
    * created, so sharing the bootstrap user would let a concurrent login end this session.
    */
  private def loginWithClientA(a: Flows.Setup, auth: OAuthClient): Task[ClientALogin] =
    val email = s"sso-a-${UUID.randomUUID().toString.replace("-", "").take(10)}@example.test"
    for
      userId <- auth.registerUser(email = Some(email))
      _ <- auth.flushUserOutbox()
      authorize <- auth.authorize(
        scope = "openid email",
        clientId = Some(a.clientId),
        redirectUri = Some(a.redirectUri),
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitEmail(cookie, email, credential.csrf)
      otp <- auth.getChallenge(cookie).assertStep(ConversationStep.Otp)
      submit <- auth.submitOtp(cookie, fixedOtp, otp.csrf)
      code <- ZIO.succeed(submit).assertRedirect(auth, cookie)
      sessionCookie <- ZIO.fromOption(submit.sessionCookie)
        .orElseFail(RuntimeException("No SSO_SESSION cookie in final submission response"))
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(a.clientId),
        clientSecret = Some(a.clientSecret),
        redirectUri = Some(a.redirectUri),
      ).success
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("Missing id_token for client A"))
    yield ClientALogin(userId, sessionCookie, idToken, token.accessToken)

  def spec = suite("Cross-client SSO")(

    test("client B authorizes silently on the session client A established") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri)
        result <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        )
        code <- ZIO.succeed(result).assertCodeRedirect
      yield assertTrue(code.nonEmpty)
        .label("client B must receive an authorization code without any interaction") &&
        assertTrue(result.conversationCookie.isEmpty)
          .label("no conversation may be started: a credential prompt would need one")
    },

    test("client B's token names the same user but its own audience") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, secretB) <- registerClientB(auth, a.redirectUri)
        authorizeB <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        )
        codeB <- ZIO.succeed(authorizeB).assertCodeRedirect
        tokenB <- auth.token(
          codeB,
          authorizeB.verifier,
          clientId = Some(clientB),
          clientSecret = Some(secretB),
          redirectUri = Some(a.redirectUri),
        ).success
        idTokenB <- ZIO.fromOption(tokenB.idToken).orElseFail(RuntimeException("Missing id_token for client B"))
        claimsA <- claimsOf[IdTokenClaims](login.idToken)
        claimsB <- claimsOf[IdTokenClaims](idTokenB)
        accessClaimsB <- claimsOf[AccessTokenClaims](tokenB.accessToken)
      yield assertTrue(claimsB.sub == claimsA.sub)
        .label(s"client B's subject must be the user client A signed in, got ${claimsB.sub} vs ${claimsA.sub}") &&
        assertTrue(claimsB.sub == login.userId.toString)
          .label("the subject must be the user this test registered") &&
        assertTrue(audienceOf(claimsB) == List(clientB))
          .label(s"client B's id_token must be audienced to client B, got ${audienceOf(claimsB)}") &&
        assertTrue(audienceOf(claimsA) == List(a.clientId))
          .label(s"client A's id_token must stay audienced to client A, got ${audienceOf(claimsA)}") &&
        assertTrue(accessClaimsB.client_id == clientB)
          .label(s"client B's access token must name client B as the party, got ${accessClaimsB.client_id}")
    },

    test("both clients' id_tokens name the same session") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, secretB) <- registerClientB(auth, a.redirectUri)
        authorizeB <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        )
        codeB <- ZIO.succeed(authorizeB).assertCodeRedirect
        tokenB <- auth.token(
          codeB,
          authorizeB.verifier,
          clientId = Some(clientB),
          clientSecret = Some(secretB),
          redirectUri = Some(a.redirectUri),
        ).success
        idTokenB <- ZIO.fromOption(tokenB.idToken).orElseFail(RuntimeException("Missing id_token for client B"))
        claimsA <- claimsOf[IdTokenClaims](login.idToken)
        claimsB <- claimsOf[IdTokenClaims](idTokenB)
      yield assertTrue(claimsA.sid.isDefined)
        .label("client A's id_token must carry a sid claim") &&
        assertTrue(claimsB.sid == claimsA.sid)
          .label(s"both clients must name the same session, got ${claimsB.sid} vs ${claimsA.sid}")
    },

    test("client B without the session cookie is refused with login_required") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        _ <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri)
        _ <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
        ).assertErrorRedirect("login_required")
      yield assertCompletes
    },

    test("client B requesting the ACR the session satisfies still authorizes silently") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri)
        code <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
          acrValues = Some(Acr.OtpLevel),
        ).assertCodeRedirect
      yield assertTrue(code.nonEmpty)
        .label("an ACR the session's factors already meet must not force interaction")
    },

    test("client B requesting an unsatisfied ACR is not silently satisfied by the session") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri)
        // Client B's flow offers no passkey and the user has none enrolled, so the requested
        // level is unreachable rather than merely unmet — an interactive challenge would have
        // nothing to ask for.
        _ <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          sessionCookie = Some(login.sessionCookie),
          acrValues = Some(Acr.PasskeyLevel),
        ).assertErrorRedirect("unmet_authentication_requirements")
        // The same request under prompt=none reports the interaction it cannot perform instead.
        _ <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
          acrValues = Some(Acr.PasskeyLevel),
        ).assertErrorRedirect("login_required")
      yield assertCompletes
    },

    test("logging out ends the session for client B too") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri)
        // Proves the session was usable by client B before the logout, so the refusal below is
        // the logout's doing and not a client B misconfiguration.
        before <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        ).assertCodeRedirect
        // The logout is keyed by the `sid` of client A's id_token, and ends the whole session
        // rather than client A's participation in it.
        _ <- auth.logoutWithIdTokenHint(login.idToken)
        _ <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        ).assertErrorRedirect("login_required")
      yield assertTrue(before.nonEmpty)
        .label("client B must have been able to use the session before the logout")
    },

    test("client B requiring consent prompts for it despite the valid session") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri, consentFlow = Some(Flows.rememberedConsentFlow))
        // Authentication is settled by the session, so the conversation opens on consent
        // rather than on a credential card.
        interactive <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          sessionCookie = Some(login.sessionCookie),
        ).assertChallengeRedirect
        consent <- auth.getChallenge(interactive.conversationCookie.get).assertStep(ConversationStep.Consent)
        // A grant the user has never given cannot be inferred from the session, so the silent
        // request names consent as the missing interaction.
        _ <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(login.sessionCookie),
        ).assertErrorRedirect("consent_required")
      yield assertTrue(consent.html.contains("Cross-Client SSO Client B"))
        .label("the consent screen must name client B, not the client that established the session")
    },

    test("client B authorizes silently once consent has been granted") {
      for
        (a, auth) <- setup(Flows.Id.EmailOtp)
        login <- loginWithClientA(a, auth)
        (clientB, _) <- registerClientB(auth, a.redirectUri, consentFlow = Some(Flows.rememberedConsentFlow))
        interactive <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          sessionCookie = Some(login.sessionCookie),
        ).assertChallengeRedirect
        cookie = interactive.conversationCookie.get
        consent <- auth.getChallenge(cookie).assertStep(ConversationStep.Consent)
        granted <- auth.submitConsent(cookie, Set("openid", "email"), consent.csrf)
        _ <- ZIO.succeed(granted).assertRedirect(auth, cookie)
        // Finishing a conversation rotates the session, so the browser continues under the
        // cookie the consent submission set rather than the one client A's login produced.
        rotated <- ZIO.fromOption(granted.sessionCookie)
          .orElseFail(RuntimeException("No SSO_SESSION cookie in the consent submission response"))
        code <- auth.authorizeRaw(
          clientId = clientB,
          redirectUri = a.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = Some(rotated),
        ).assertCodeRedirect
      yield assertTrue(code.nonEmpty)
        .label("a remembered grant must let the session authorize client B silently") &&
        assertTrue(rotated != login.sessionCookie)
          .label("completing the consent conversation must issue a rotated session cookie")
    },

  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
