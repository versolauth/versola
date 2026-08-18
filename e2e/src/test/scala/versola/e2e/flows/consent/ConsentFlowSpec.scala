package versola.e2e.flows.consent

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

/** Consent gate between a satisfied authentication and the authorization code. */
object ConsentFlowSpec extends E2ESpec:

  /** Signs in, expecting to land on the consent step rather than a code. */
  private def authenticateToConsent(
      auth: OAuthClient,
      s: Flows.Setup,
      scope: String = "openid email",
      sessionCookie: Option[String] = None,
  ) =
    for
      authorize <- auth.authorizeRaw(
        clientId = s.clientId,
        redirectUri = s.redirectUri,
        scope = Some(scope),
        sessionCookie = sessionCookie,
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      submit <- auth.submitLoginPassword(cookie, s.login.get, s.password, credential.csrf)
      consent <- auth.getChallenge(cookie).assertStep(ConversationStep.Consent)
    yield (authorize, cookie, submit, consent)

  def spec = suite("Consent Flow")(
    test("a first authorization stops on consent and completes once granted") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (authorize, cookie, _, consent) <- authenticateToConsent(auth, s)
        code <- auth.submitConsent(cookie, Set("openid", "email"), consent.csrf)
          .assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("granting consent must yield a usable access token")
    },
    test("the consent screen names the client and the requested scopes") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, _, _, consent) <- authenticateToConsent(auth, s)
      yield assertTrue(consent.html.contains("Consent Test Client"))
        .label("the consent screen must name the requesting client") &&
        assertTrue(consent.html.contains("email"))
          .label("the consent screen must list the requested 'email' scope") &&
        assertTrue(consent.html.contains("consent-scopes-container"))
          .label("the requested scopes must be rendered in a scrollable region") &&
        assertTrue(consent.html.contains("overflow-y: auto"))
          .label("the scopes region must enable vertical scrolling when it overflows")
    },
    test("a remembered grant is reused on the next authorization") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        granted <- auth.submitConsent(cookie, Set("openid", "email"), consent.csrf)
        session = granted.sessionCookie.get
        // The SSO session alone would satisfy authentication; this asserts the grant
        // survives alongside it and no second prompt is rendered.
        second <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          scope = Some("openid email"),
          sessionCookie = Some(session),
        ).assertCodeRedirect
      yield assertTrue(second.nonEmpty)
        .label("a remembered grant must authorize silently")
    },
    test("a remembered grant does not cover a newly requested scope") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s, scope = "openid")
        granted <- auth.submitConsent(cookie, Set("openid"), consent.csrf)
        session = granted.sessionCookie.get
        widened <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          scope = Some("openid email"),
          sessionCookie = Some(session),
        ).assertChallengeRedirect
        step <- auth.getChallenge(widened.conversationCookie.get)
      yield assertTrue(step.step.contains(ConversationStep.Consent))
        .label("widening the requested scope must re-prompt for consent")
    },
    test("prompt=consent re-prompts even though the grant is remembered") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        granted <- auth.submitConsent(cookie, Set("openid", "email"), consent.csrf)
        session = granted.sessionCookie.get
        reprompt <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          scope = Some("openid email"),
          prompt = Some("consent"),
          sessionCookie = Some(session),
        ).assertChallengeRedirect
        step <- auth.getChallenge(reprompt.conversationCookie.get)
      yield assertTrue(step.step.contains(ConversationStep.Consent))
        .label("prompt=consent must ignore a remembered grant")
    },
    test("prompt=none without a grant redirects with error=consent_required") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        // Authenticate elsewhere to obtain a session without granting consent for this client.
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        denied <- auth.denyConsent(cookie, consent.csrf)
        _ <- auth.authorizeRaw(
          clientId = s.clientId,
          redirectUri = s.redirectUri,
          scope = Some("openid email"),
          prompt = Some("none"),
          sessionCookie = denied.sessionCookie,
        ).assertErrorRedirect("consent_required")
      yield assertCompletes
    },
    test("denying consent renders access-denied instead of issuing a code") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        _ <- auth.denyConsent(cookie, consent.csrf)
        step <- auth.getChallenge(cookie)
      yield assertTrue(step.step.contains(ConversationStep.AccessDenied))
        .label("a denied grant must not issue an authorization code")
    },
    test("a partial grant narrows the scope carried by the issued token") {
      for
        (s, auth) <- setup(Flows.Id.ConsentPartial)
        (authorize, cookie, _, consent) <- authenticateToConsent(auth, s)
        code <- auth.submitConsent(cookie, Set("openid"), consent.csrf)
          .assertRedirect(auth, cookie)
        token <- auth.token(
          code,
          authorize.verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
        ).success
      yield assertTrue(!token.scope.exists(_.contains("email")))
        .label("a deselected scope must not appear in the token response")
    },
    test("a client that forbids partial grants rejects a narrowed submission") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        _ <- auth.submitConsent(cookie, Set("openid"), consent.csrf)
        step <- auth.getChallenge(cookie)
      yield assertTrue(step.step.contains(ConversationStep.Consent))
        .label("an all-or-nothing client must re-render consent on a narrowed grant")
    },
    test("dropping openid is rejected even when partial grants are allowed") {
      for
        (s, auth) <- setup(Flows.Id.ConsentPartial)
        (_, cookie, _, consent) <- authenticateToConsent(auth, s)
        _ <- auth.submitConsent(cookie, Set("email"), consent.csrf)
        step <- auth.getChallenge(cookie)
      yield assertTrue(step.step.contains(ConversationStep.Consent))
        .label("openid is mandatory and cannot be deselected")
    },
    test("a consent submission carrying a stale csrf token is rejected") {
      for
        (s, auth) <- setup(Flows.Id.Consent)
        (_, cookie, _, _) <- authenticateToConsent(auth, s)
        result <- auth.submitConsent(cookie, Set("openid", "email"), "stale-csrf")
        step <- auth.getChallenge(cookie)
      yield assertTrue(result.response.status.code == 400)
        .label("a stale csrf token must be rejected with 400") &&
        assertTrue(step.step.contains(ConversationStep.Consent))
          .label("the conversation must stay on the consent step")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
