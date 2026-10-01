package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.test.*

import java.util.UUID

/** #352 / #353: what auth's client-authenticating endpoints do with a public client on a
  * `standard` tenant -- the one place a public client is still admitted.
  *
  * The FAPI 2.0 half of the rule (a public client refused at `/token`, `/par`, `/introspect`
  * and `/revoke`) guards clients registered before their tenant was moved to the profile, a
  * state central's API cannot produce: it refuses to register a public client under FAPI 2.0,
  * and refuses to switch a tenant holding one. The test for it therefore registers the client
  * under `standard` and writes the profile straight into central's database
  * ([[CentralDatabase]]), the way a deployment that predates the profile would have it.
  */
object SecurityProfileRuntimeSpec extends E2ESpec:

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  private def errorCode(body: String): Option[String] =
    body.fromJson[OAuthError].toOption.map(_.error)

  /** A login to `clientId`, left at the authorization code: its holder has only to redeem it. */
  private def authorizationCode(
      auth: OAuthClient,
      s: Flows.Setup,
      clientId: String,
  ): Task[(String, String)] =
    for
      authorize <- auth.authorize(clientId = Some(clientId), redirectUri = Some(s.redirectUri)).assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
    yield (code, authorize.verifier)

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

    // #352 / #353: the same client, once its tenant is moved to fapi2 underneath it. Everything
    // each endpoint needs -- a code never redeemed, an access token -- is obtained while the
    // tenant is still `standard`, and the client is shown admitted there, so the 401 below can
    // only be the profile check: client authentication comes before any grant is read.
    //
    // `/introspect` is the exception to "only the profile": it demands a secret of every
    // caller, so a public client is refused there under `standard` as well, and its 401 is not
    // evidence of the profile rule. It is asserted because the endpoint must refuse in both.
    test("a public client of a tenant moved to fapi2 is refused with invalid_client at /token, /par, /introspect and /revoke") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        results <- SecurityProfiles.withFapi2Tenant(auth.central): tenantId =>
          for
            clientId <- CentralApi.id("e2e-public")
            outcome <- (
              for
                // A new tenant starts on fapi2, which refuses to register a public client.
                _ <- SecurityProfiles.ensureStandard(auth.central, tenantId)
                _ <- auth.registerClient(
                  clientId,
                  "Public client",
                  Set(s.redirectUri),
                  tenantId = tenantId,
                  authFlow = Some(Flows.loginPasswordAuthFlow),
                  authMethod = "none",
                ).success
                _ <- auth.syncConfiguration()
                (code, verifier) <- authorizationCode(auth, s, clientId)
                (heldCode, heldVerifier) <- authorizationCode(auth, s, clientId)
                issued <- auth.token(
                  code,
                  verifier,
                  clientId = Some(clientId),
                  clientSecret = Some(""),
                  redirectUri = Some(s.redirectUri),
                ).success
                admitted <- auth.pushAuthorization(clientId, "", s.redirectUri)

                // Central's API would refuse this switch while the client exists.
                _ <- CentralDatabase.setSecurityProfile(tenantId, "fapi2")
                _ <- SecurityProfiles.awaitProfile(auth.central, tenantId, "fapi2")
                _ <- auth.syncConfiguration()

                token <- auth.token(
                  heldCode,
                  heldVerifier,
                  clientId = Some(clientId),
                  clientSecret = Some(""),
                  redirectUri = Some(s.redirectUri),
                ).flatMap:
                  case TokenResult.Failure(response, body) => ZIO.succeed(response.status -> errorCode(body))
                  case _: TokenResult.Success => ZIO.fail(RuntimeException("/token admitted a public client of a fapi2 tenant"))
                par <- auth.pushAuthorization(clientId, "", s.redirectUri).flatMap:
                  case PushedAuthorizationResult.Failure(response, _, error) => ZIO.succeed(response.status -> error)
                  case _: PushedAuthorizationResult.Success => ZIO.fail(RuntimeException("/par admitted a public client of a fapi2 tenant"))
                introspect <- auth.introspect(issued.accessToken, clientId = Some(clientId), clientSecret = Some("")).flatMap:
                  case IntrospectResult.Failure(response, body) => ZIO.succeed(response.status -> errorCode(body))
                  case _: IntrospectResult.Success => ZIO.fail(RuntimeException("/introspect admitted a public client of a fapi2 tenant"))
                revoke <- auth.revoke(issued.accessToken, clientId, "").flatMap: response =>
                  response.body.asString.map(body => response.status -> errorCode(body))
              yield (admitted.response.status, token, par, introspect, revoke)
            ).ensuring(auth.central.delete("/configuration/clients", "clientId" -> clientId).ignore)
          yield outcome
        (admitted, token, par, introspect, revoke) = results
      yield assertTrue(admitted == Status.Created)
        .label("/par admitted the public client while its tenant was on standard") &&
        assertTrue(token == (Status.Unauthorized, Some("invalid_client"))).label("/token") &&
        assertTrue(par == (Status.Unauthorized, Some("invalid_client"))).label("/par") &&
        assertTrue(introspect == (Status.Unauthorized, Some("invalid_client"))).label("/introspect") &&
        assertTrue(revoke == (Status.Unauthorized, Some("invalid_client"))).label("/revoke")
    },
  )
