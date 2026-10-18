package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.test.*

/** RFC 9449 §10.1: a DPoP proof sent to `/par` binds the authorization code to its key.
  *
  * The unit suites prove the rule against a stubbed `DpopService`. What only this level shows
  * is the chain it rides on -- the `DPoP` header read off the PAR request, the thumbprint
  * stored with the pushed request, read back at `/authorize`, kept with the code, and held
  * against the proof at `/token` -- all of it through auth's own HTTP and Postgres.
  *
  * A `standard` client, because the binding does not depend on the profile: FAPI 2.0 tenants
  * reach it the same way, and their extra requirements (private_key_jwt, DPoP-bound tokens)
  * are the subject of other specs.
  */
object DpopAtParSpec extends E2ESpec:

  /** A code obtained through a pushed request that carried `pushWith`'s proof, left unredeemed. */
  private def pushedCode(
      auth: OAuthClient,
      s: Flows.Setup,
      pushWith: DpopProver,
  ): Task[(String, String)] =
    for
      pushed <- auth.pushAuthorization(
        clientId = s.clientId,
        clientSecret = s.clientSecret,
        redirectUri = s.redirectUri,
        dpop = Some(pushWith),
      ).success
      authorize <- auth.authorizeRaw(
        clientId = s.clientId,
        redirectUri = s.redirectUri,
        requestUri = Some(pushed.requestUri),
      ).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
    yield (code, pushed.verifier)

  def spec = suite("DPoP proof at the PAR endpoint")(
    test("a code pushed with a proof is redeemed with the same key, and the token is bound to it") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        key <- DpopProver.make
        (code, verifier) <- pushedCode(auth, s, key)
        token <- auth.token(
          code,
          verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
          dpop = Some(key),
        ).success
      yield assertTrue(token.tokenType == "DPoP")
    },
    test("a code pushed with a proof is refused at /token to a proof from another key") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        pushedWith <- DpopProver.make
        other <- DpopProver.make
        (code, verifier) <- pushedCode(auth, s, pushedWith)
        result <- auth.token(
          code,
          verifier,
          clientId = Some(s.clientId),
          clientSecret = Some(s.clientSecret),
          redirectUri = Some(s.redirectUri),
          dpop = Some(other),
        )
      yield assertTrue(result.isInstanceOf[TokenResult.Failure])
    },
    test("a dpop_jkt that names another key than the proof's is refused at /par") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        key <- DpopProver.make
        other <- DpopProver.make
        result <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          extraParams = Map("dpop_jkt" -> other.jkt),
          dpop = Some(key),
        )
      yield assertTrue(
        result.isInstanceOf[PushedAuthorizationResult.Failure],
        result.asInstanceOf[PushedAuthorizationResult.Failure].error.contains("invalid_dpop_proof"),
      )
    },
    test("a dpop_jkt that agrees with the proof is accepted") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        key <- DpopProver.make
        result <- auth.pushAuthorization(
          clientId = s.clientId,
          clientSecret = s.clientSecret,
          redirectUri = s.redirectUri,
          extraParams = Map("dpop_jkt" -> key.jkt),
          dpop = Some(key),
        )
      yield assertTrue(result.isInstanceOf[PushedAuthorizationResult.Success])
    },
  )
