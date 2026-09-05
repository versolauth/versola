package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

/** Rotation is single-use, so a client whose response never arrived is holding a token the
  * server has already retired. Presenting it again is indistinguishable from a replay, and
  * costs the user the whole session -- for a dropped connection rather than a leak.
  *
  * An `Idempotency-Key` is what tells the two apart. These exercise it against the real
  * endpoint, since the interesting part is that reuse detection still bites for everyone who
  * cannot produce the key.
  */
object RefreshRetryFlowSpec extends E2ESpec:

  /** Logs in and exchanges the code for a first refresh token. */
  private def login(s: Flows.Setup, auth: OAuthClient): Task[String] =
    for
      authorize <- auth.authorize(
        clientId = Some(s.clientId),
        redirectUri = Some(s.redirectUri),
        scope = "openid email offline_access",
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      refreshToken <- ZIO.fromOption(token.refreshToken)
        .orElseFail(RuntimeException("expected a refresh_token for the offline_access scope"))
    yield refreshToken

  def spec = suite("RefreshRetryFlowSpec")(
    test("a retry under the same key is served instead of killing the session") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        refreshToken <- login(s, auth)

        // The exchange whose response the client never sees.
        first <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-1"),
        ).success

        // The client is still holding the token it started with, and tries again.
        retry <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-1"),
        ).success

        // The token it is finally given has to work, which is the whole point.
        continued <- auth.refresh(
          retry.refreshToken.get,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
      yield assertTrue(
        // A fresh token rather than the one that went missing: the original was never stored.
        retry.refreshToken.isDefined,
        retry.refreshToken != first.refreshToken,
        continued.refreshToken.isDefined,
      )
    },
    test("a client that retries repeatedly is still served") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        refreshToken <- login(s, auth)

        // A backoff loop routinely gets this far, so honouring only the first retry would
        // mean the feature never survives contact with a bad connection.
        attempts <- ZIO.foreach(1 to 3): _ =>
          auth.refresh(
            refreshToken,
            clientId = Some(s.clientId),
            clientSecret = Some(s.clientSecret),
            idempotencyKey = Some("retry-key-2"),
          ).success

        last = attempts.last
        continued <- auth.refresh(
          last.refreshToken.get,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        ).success
      yield assertTrue(
        attempts.forall(_.refreshToken.isDefined),
        attempts.map(_.refreshToken).distinct.size == 3,
        continued.refreshToken.isDefined,
      )
    },
    test("the same token without the key is still treated as a replay") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        refreshToken <- login(s, auth)

        rotated <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-3"),
        ).success

        // Whoever presents the retired token without the key cannot be the client that made
        // the request, so this is reuse and the chain goes.
        replayed <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )

        // And the revocation is real: the successor the replay condemned is dead too.
        successor <- auth.refresh(
          rotated.refreshToken.get,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
        )
      yield assertTrue(
        replayed.response.status == Status.BadRequest,
        successor.response.status == Status.BadRequest,
      )
    },
    test("a key stops working once the client gets through and moves on") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        refreshToken <- login(s, auth)

        rotated <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-4"),
        ).success

        // The client received this one and carried on under a new key, which settles the
        // earlier attempt: the old key must not still be a way back into the chain.
        _ <- auth.refresh(
          rotated.refreshToken.get,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-5"),
        ).success

        stale <- auth.refresh(
          refreshToken,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          idempotencyKey = Some("retry-key-4"),
        )
      yield assertTrue(stale.response.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential
