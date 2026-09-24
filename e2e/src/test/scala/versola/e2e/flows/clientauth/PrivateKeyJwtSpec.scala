package versola.e2e.flows.clientauth

import com.nimbusds.jose.jwk.{Curve, ECKey}
import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.interfaces.ECPublicKey
import java.util.UUID

/** RFC 7523 §2.2 `private_key_jwt` client authentication, end to end.
  *
  * The unit suites already prove the assertion rules against a stubbed configuration service
  * and an in-memory replay guard. What only this level can show is that a JWK Set survives
  * the trip through Central's registration API and auth's configuration cache, that a
  * form-encoded `client_assertion` is read the way the spec expects, and that the replay
  * guard behind Postgres rejects the second use of a `jti` the first use recorded.
  */
object PrivateKeyJwtSpec extends E2ESpec:

  private val redirectUri = "http://localhost:3000"

  private def uid: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  /** A client that authenticates by assertion.
    *
    * Central still issues a secret — every `web` client gets one — but a client with `jwks`
    * never authenticates with it (RFC 7523 §2.2 read the way RFC 8705 §2.1 is read for
    * certificates), which the test below relies on.
    */
  private def assertionClient(
      auth: OAuthClient,
      signer: AssertionSigner,
      scopes: Set[String] = Set("openid", "email"),
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"jwt-client-$s")
      result <- auth.registerClient(
        id,
        "Private Key JWT Test Client",
        Set(redirectUri),
        allowedScopes = scopes,
        authMethod = "private_key_jwt",
        jwks = Some(signer.jwks),
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  /** A well-formed JWK Set holding a key no algorithm auth verifies with can use: `ES256`
    * names P-256 (RFC 7518 §3.4), so nothing here could ever check a signature made with
    * this one. */
  private val unusableJwks: Json.Obj =
    val generator = java.security.KeyPairGenerator.getInstance("EC").nn
    generator.initialize(Curve.P_384.toECParameterSpec)
    val key = ECKey.Builder(Curve.P_384, generator.generateKeyPair().nn.getPublic.asInstanceOf[ECPublicKey])
      .keyID(UUID.randomUUID().toString)
      .build()
    Json.Obj("keys" -> Json.Arr(key.toJSONString.fromJson[Json.Obj].toOption.get))

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  private def rejection(result: TokenResult): Task[(Status, String)] =
    result match
      case s: TokenResult.Success =>
        ZIO.fail(RuntimeException(s"Expected /token to reject the request, got ${s.response.status}"))
      case TokenResult.Failure(response, body) =>
        ZIO.fromEither(body.fromJson[OAuthError])
          .mapBoth(
            error => RuntimeException(s"Unparsable /token error body [$error]: $body"),
            parsed => response.status -> parsed.error,
          )

  def spec = suite("Private Key JWT (RFC 7523)")(

    test("a client with registered keys authenticates at /token with an assertion") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        // No secret at all: the assertion is the whole credential.
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion)).success
      yield assertTrue(token.accessToken.nonEmpty)
        .label("RFC 7523 §2.2: a key the client registered is the credential, so no secret is needed")
    },

    test("the issuer identifier is accepted as the audience, not only the endpoint URL") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, auth.issuer)
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion)).success
      yield assertTrue(token.accessToken.nonEmpty)
    },

    test("a client that registered keys is refused its own secret") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, clientSecret) <- assertionClient(auth, signer)
        // The secret Central issued is presented deliberately: registering keys must not
        // leave `private_key_jwt` as an option an attacker can simply decline to use.
        result <- auth.clientCredentials(clientId, clientSecret)
        (status, error) <- rejection(result)
      yield assertTrue(error == "invalid_client", status == Status.Unauthorized)
        .label("a secret must not authenticate a client that registered a key set")
    },

    test("the same assertion cannot be spent twice") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token", jti = Some(s"replayed-${clientId}"))
        first <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion)).success
        replay <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(replay)
      yield assertTrue(first.accessToken.nonEmpty, error == "invalid_client")
        .label("RFC 7523 §3: a jti already seen is a replay, whatever else the assertion says")
    },

    test("an assertion signed by a key the client never registered is refused") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        foreign <- signer.foreignKey
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token", signWith = Some(foreign))
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
    },

    test("an assertion addressed to somewhere else is refused") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, "https://elsewhere.example/token")
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
        .label("RFC 7523 §3: an assertion is only good for the server its aud names")
    },

    test("an assertion valid for longer than the tenant allows is refused") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        // The default tenant's ceiling is five minutes, and a `jti` is only remembered for as
        // long as the assertion carrying it is acceptable -- an hour-long one would outlive
        // the replay guard's memory of it.
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token", lifetime = 1.hour)
        result <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
    },

    test("a client that registered no keys cannot authenticate with an assertion") {
      for
        (setupResult, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        id <- uid.map(s => s"secret-client-$s")
        _ <- auth.registerClient(id, "Secret Client", Set(redirectUri), allowedScopes = Set("openid")).success
        _ <- auth.syncConfiguration()
        assertion <- signer.assertion(id, s"${auth.issuer}/token")
        result <- auth.clientCredentials(id, "", useBasicAuth = false, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client", setupResult.clientId.nonEmpty)
        .label("an assertion must not make a secret-authenticated client assertion-authenticable")
    },

    test("an assertion presented beside Basic authentication is refused as two methods") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, clientSecret) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        result <- auth.clientCredentials(clientId, clientSecret, assertion = Some(assertion))
        (_, error) <- rejection(result)
      yield assertTrue(error == "invalid_client")
        .label("RFC 6749 §2.3: a client uses one authentication method per request")
    },

    test("/introspect authenticates the caller by assertion") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        resource = s"https://$clientId.example.test"
        _ <- auth.registerResource(s"res-$clientId", resource, audience = Set(clientId))
        _ <- auth.syncConfiguration()
        tokenAssertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        token <- auth.clientCredentials(
          clientId,
          "",
          useBasicAuth = false,
          resources = Some(List(resource)),
          assertion = Some(tokenAssertion),
        ).success
        // A fresh assertion, addressed to the endpoint this one reaches: the first one's jti
        // is spent, and `/introspect` is not `/token`.
        introspectAssertion <- signer.assertion(clientId, s"${auth.issuer}/introspect")
        introspection <- auth.introspect(
          token.accessToken,
          Some(clientId),
          assertion = Some(introspectAssertion),
        ).success
      yield assertTrue(introspection.active)
    },

    test("/par authenticates the pushing client by assertion") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/par")
        result <- auth.pushAuthorization(clientId, "", redirectUri, assertion = Some(assertion)).success
      yield assertTrue(result.requestUri.nonEmpty)
        .label("RFC 9126 authenticates the pusher the same way /token does")
    },

    test("/par refuses an assertion addressed to the token endpoint instead of /par") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        assertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        result <- auth.pushAuthorization(clientId, "", redirectUri, assertion = Some(assertion))
      yield result match
        case _: PushedAuthorizationResult.Success =>
          throw RuntimeException("Expected /par to reject an assertion addressed elsewhere")
        case PushedAuthorizationResult.Failure(response, _, error) =>
          assertTrue(response.status == Status.Unauthorized, error.contains("invalid_client"))
    },

    test("/revoke authenticates the caller by assertion") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        signer <- AssertionSigner.make
        (clientId, _) <- assertionClient(auth, signer)
        tokenAssertion <- signer.assertion(clientId, s"${auth.issuer}/token")
        token <- auth.clientCredentials(clientId, "", useBasicAuth = false, assertion = Some(tokenAssertion)).success
        // A fresh assertion, addressed to the endpoint this one reaches: the first one's jti
        // is spent, and `/revoke` is not `/token`.
        revokeAssertion <- signer.assertion(clientId, s"${auth.issuer}/revoke")
        response <- auth.revoke(token.accessToken, clientId, "", assertion = Some(revokeAssertion))
        introspectAssertion <- signer.assertion(clientId, s"${auth.issuer}/introspect")
        introspection <- auth.introspect(token.accessToken, Some(clientId), assertion = Some(introspectAssertion)).success
      yield assertTrue(response.status.isSuccess, !introspection.active)
        .label("RFC 7009 accepts the same credential /token and /introspect do")
    },

    test("registration refuses a key set no supported algorithm could verify against") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        id <- uid.map(s => s"jwt-client-$s")
        result <- auth.registerClient(
          id,
          "Private Key JWT Test Client",
          Set(redirectUri),
          authMethod = "private_key_jwt",
          jwks = Some(unusableJwks),
        )
      yield result match
        case _: RegisterClientResult.Success =>
          throw RuntimeException("Expected registration to refuse a key set that can never authenticate")
        case RegisterClientResult.Failure(response, _) =>
          assertTrue(response.status == Status.BadRequest)
            .label("a credential the client could never use must fail at registration, not at /token")
    },

    test("the metadata document advertises the method and the algorithms it will accept") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        methods = document.get("token_endpoint_auth_methods_supported")
        algorithms = document.get("token_endpoint_auth_signing_alg_values_supported")
      yield assertTrue(
        methods.exists(_.toString.contains("private_key_jwt")),
        algorithms.exists(_.toString.contains("ES256")),
      ).label("RFC 8414 §2: a client discovers the method and the alg it may sign with here")
    },
  )
