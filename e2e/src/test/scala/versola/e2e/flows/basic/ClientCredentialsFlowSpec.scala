package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** OAuth 2.0 client credentials grant (RFC 6749 §4.4) end-to-end coverage.
  *
  * The grant has no resource owner, so what it must get right is different from the
  * authorization code flow: the client is the subject, nothing about a user may leak into
  * the response, and every check that would otherwise happen at `/authorize` — scope,
  * `resource` (RFC 8707), `authorization_details` (RFC 9396) — has to happen at `/token`.
  */
object ClientCredentialsFlowSpec extends E2ESpec:

  /** A confidential client of its own, so that changing its scope or its resources cannot
    * disturb the shared bootstrap fixtures other specs run against.
    */
  private def registerClient(
      auth: OAuthClient,
      scopes: Set[String] = Set("openid", "email", "offline_access"),
  ): Task[(String, String)] =
    val clientId = s"cc-client-${UUID.randomUUID().toString.replace("-", "").take(8)}"
    for
      result <- auth.registerClient(
        clientId,
        "Client Credentials Test Client",
        Set("http://localhost:3000"),
        allowedScopes = scopes,
      ).success
      _ <- auth.syncConfiguration()
    yield (clientId, result.secret)

  /** The access token is a JWT, and the claims it carries are the point of most of these
    * assertions — the token response alone would not show `sub` or `aud`.
    */
  private def claims(accessToken: String): Task[Json.Obj] =
    for
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(1)), "UTF-8"))
        .mapError(error => RuntimeException(s"Access token is not a JWT [$error]: $accessToken"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj])
        .mapError(error => RuntimeException(s"Access token payload is not a JSON object [$error]: $payload"))
    yield json

  private def claim(claims: Json.Obj, name: String): Option[String] =
    claims.get(name).collect { case Json.Str(value) => value }

  /** `aud` is a single string when the token names one resource and an array otherwise. */
  private def audience(claims: Json.Obj): List[String] =
    claims.get("aud").toList.flatMap:
      case Json.Str(value) => List(value)
      case Json.Arr(values) => values.collect { case Json.Str(value) => value }.toList
      case _ => Nil

  /** The `error` code of a rejected token request; `None` when the request succeeded. */
  private def errorCode(result: TokenResult): Option[String] =
    result match
      case TokenResult.Failure(_, body) =>
        body.fromJson[Json.Obj].toOption.flatMap(_.get("error")).collect { case Json.Str(code) => code }
      case _: TokenResult.Success => None

  def spec = suite("Client credentials (RFC 6749 §4.4)")(
    test("a confidential client gets an access token for itself, and nothing about a user") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        token <- auth.clientCredentials(clientId, clientSecret).success
        payload <- claims(token.accessToken)
      yield assertTrue(token.tokenType == "Bearer") &&
        assertTrue(claim(payload, "sub").contains(clientId))
          .label(s"the client itself must be the subject, got sub=${claim(payload, "sub")}") &&
        assertTrue(claim(payload, "client_id").contains(clientId)) &&
        // There is no end user to describe, so an id_token would be a claim about nobody.
        assertTrue(token.idToken.isEmpty)
          .label(s"expected no id_token, got ${token.idToken}") &&
        // RFC 6749 §4.4.3: no refresh token, even though the client may ask for
        // `offline_access` — there is no consent to keep alive between requests.
        assertTrue(token.refreshToken.isEmpty)
          .label(s"expected no refresh token, got ${token.refreshToken}") &&
        assertTrue(token.scope.map(_.split(' ').toSet).contains(Set("openid", "email", "offline_access")))
          .label(s"an unrequested scope must default to the client's own, got ${token.scope}")
    },

    test("the granted scope narrows to the requested subset") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        token <- auth.clientCredentials(clientId, clientSecret, scope = Some("email")).success
        payload <- claims(token.accessToken)
      yield assertTrue(token.scope.contains("email")) &&
        assertTrue(claim(payload, "scope").contains("email"))
          .label(s"the access token must carry the narrowed scope, got ${claim(payload, "scope")}")
    },

    test("the issued token introspects as active and carries the requested audience") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth, scopes = Set("openid", "email"))
        resource = s"https://$clientId.example.test"
        _ <- auth.registerResource(s"res-$clientId", resource, audience = Set(clientId))
        _ <- auth.syncConfiguration()

        token <- auth.clientCredentials(
          clientId,
          clientSecret,
          scope = Some("email"),
          resources = Some(List(resource)),
        ).success
        payload <- claims(token.accessToken)
        introspection <- auth.introspect(token.accessToken, Some(clientId), Some(clientSecret)).success
      yield assertTrue(audience(payload) == List(resource))
        .label(s"expected aud=[$resource], got ${audience(payload)}") &&
        assertTrue(introspection.active)
          .label("the resource server must see the token as active") &&
        assertTrue(claim(payload, "scope").contains("email"))
    },

    test("a scope outside the client's registered scope is rejected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth, scopes = Set("openid", "email"))
        result <- auth.clientCredentials(clientId, clientSecret, scope = Some("openid profile"))
      yield assertTrue(errorCode(result).contains("invalid_scope"))
        .label(s"expected invalid_scope, got $result")
    },

    test("a wrong client secret is rejected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        result <- auth.clientCredentials(clientId, clientSecret.reverse)
      yield assertTrue(errorCode(result).contains("invalid_client"))
        .label(s"expected invalid_client, got $result") &&
        assertTrue(result.response.status == Status.Unauthorized)
    },

    test("a client that presents no secret at all is rejected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, _) <- registerClient(auth)
        // The grant is only open to confidential clients, so identifying the client without
        // authenticating it is not enough, however well-known the client id is.
        result <- auth.clientCredentials(clientId, "")
      yield assertTrue(errorCode(result).contains("invalid_client"))
        .label(s"expected invalid_client, got $result")
    },

    test("a native (public) client is refused the grant even though it names itself") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        clientId = s"cc-native-${UUID.randomUUID().toString.replace("-", "").take(8)}"
        registered <- auth.registerClient(
          clientId,
          "Client Credentials Native Client",
          Set("http://localhost:3000"),
          clientType = "native",
        ).success
        _ <- auth.syncConfiguration()
        // A public client has nothing to authenticate with, so naming itself is all it can
        // do - and RFC 6749 §4.4 does not open the grant to a client that cannot be
        // authenticated, however correctly it identifies itself.
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false)
      yield assertTrue(registered.secret.isEmpty)
        .label("central must not issue a secret to a native client") &&
        assertTrue(errorCode(result).contains("invalid_client"))
          .label(s"expected invalid_client, got $result")
    },

    test("an empty resource parameter is rejected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        // Sending `resource=` is not the same as omitting it: it asks for a token for no
        // resource at all, which is never what the client meant.
        result <- auth.clientCredentials(clientId, clientSecret, resources = Some(Nil))
      yield assertTrue(errorCode(result).contains("invalid_request"))
        .label(s"expected invalid_request, got $result")
    },

    test("an empty authorization_details parameter is rejected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        result <- auth.clientCredentials(clientId, clientSecret, authorizationDetails = Some("[]"))
      yield assertTrue(errorCode(result).contains("invalid_request"))
        .label(s"expected invalid_request, got $result")
    },

    test("a resource the client cannot reach is rejected with invalid_target") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        result <- auth.clientCredentials(
          clientId,
          clientSecret,
          resources = Some(List("https://unregistered.example.test")),
        )
      yield assertTrue(errorCode(result).contains("invalid_target"))
        .label(s"expected invalid_target, got $result")
    },

    test("a revoked token stops being accepted by the edge") {
      // The bootstrap fixture is used here rather than a client of this spec's own: the edge
      // only accepts security events for a client it already knows about, and `Flows.layer`
      // has already made it learn about this one.
      for
        (s, auth) <- setup(Flows.Id.BackChannelLogout)
        token <- auth.clientCredentials(s.clientId, s.clientSecret).success
        before <- auth.edgePermissions(token.accessToken).map(_.status)
        _ <- auth.revoke(token.accessToken, s.clientId, s.clientSecret)
        after <- auth.edgePermissions(token.accessToken).map(_.status)
          .repeat(Schedule.spaced(100.millis) && Schedule.recurUntil[Status](_ == Status.Unauthorized))
          .map(_._2)
      yield assertTrue(before == Status.Ok)
        .label("the edge must accept the token before it is revoked") &&
        assertTrue(after == Status.Unauthorized)
          .label("the edge must reject the token once auth has revoked it")
    },
    // The revocation above is recorded against wall-clock expiry, so these need the live clock.
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
