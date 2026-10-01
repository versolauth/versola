package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.test.*

import java.util.UUID

/** #352 / #353: what auth's client-authenticating endpoints do with a public client on a
  * `standard` tenant -- the one place a public client is still admitted.
  *
  * The FAPI 2.0 half of the rule (a public client refused at `/token`, `/par`, `/introspect`
  * and `/revoke`) guards clients registered before their tenant was moved to the profile, a
  * state central's API cannot produce: it refuses to register a public client under FAPI 2.0,
  * and refuses to switch a tenant holding one. That half is covered by `ClientAuthenticationSpec`
  * alone.
  */
object SecurityProfileRuntimeSpec extends E2ESpec:

  def spec = suite("Security profile at runtime")(
    // RFC 7009 §2.1: a public client has no credential, and is held instead to the token having
    // been issued to it -- so a bare client_id revokes its own token and nobody else's.
    test("a public client of a standard tenant exchanges its code and revokes its token by client_id alone") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        clientId = s"e2e-public-${UUID.randomUUID().toString.replace("-", "").take(8)}"
        _ <- auth.registerClient(
          clientId,
          "Public client",
          Set(s.redirectUri),
          authFlow = Some(Flows.loginPasswordAuthFlow),
          authMethod = "none",
        ).success
        _ <- auth.syncConfiguration()
        authorize <- auth.authorize(clientId = Some(clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
        cookie = authorize.conversationCookie.get
        challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
        code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
        token <- auth.token(code, authorize.verifier, clientId = Some(clientId), clientSecret = Some(""), redirectUri = Some(s.redirectUri)).success
        revoked <- auth.revoke(token.accessToken, clientId, "")
        otherClients <- auth.revoke(token.accessToken, s.clientId, s.clientSecret)
      yield assertTrue(
        revoked.status == Status.Ok,
        otherClients.status == Status.Unauthorized,
      )
    },
  )
