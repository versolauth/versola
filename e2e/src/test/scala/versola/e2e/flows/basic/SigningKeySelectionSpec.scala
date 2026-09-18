package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

/** The key a tenant selects in central is the key auth signs that tenant's tokens with.
  *
  * The unit tests prove the resolution rule; this proves the wiring it depends on — the
  * keypair central generates, the private half it publishes to auth over the sync channel,
  * the algorithm that travels with it, and the header a relying party then verifies against
  * the published JWKS. A break anywhere along that chain shows up here as a token signed
  * under the wrong algorithm, or one nothing can verify.
  *
  * Every test restores the tenant's selection and removes the key it generated, because the
  * signing key is deployment-wide state the rest of the suite reads.
  */
object SigningKeySelectionSpec extends E2ESpec:

  /** The `kid` and `alg` of a JWT header, read without verifying anything. */
  private case class JwtHeader(kid: String, alg: String) derives JsonDecoder

  private def header(token: String): Task[JwtHeader] =
    for
      json <- ZIO.attempt(String(Base64.getUrlDecoder.decode(token.split('.')(0)), StandardCharsets.UTF_8))
        .mapError(error => RuntimeException(s"Cannot decode the JWT header [$error]: $token"))
      parsed <- ZIO.fromEither(json.fromJson[JwtHeader])
        .mapError(error => RuntimeException(s"JWT header has no usable 'kid'/'alg' [$error]: $json"))
    yield parsed

  private def registerClient(auth: OAuthClient): Task[(String, String)] =
    val clientId = s"signing-client-${UUID.randomUUID().toString.replace("-", "").take(8)}"
    for
      result <- auth.registerClient(
        clientId,
        "Signing Key Selection Test Client",
        Set("http://localhost:3000"),
        allowedScopes = Set("openid"),
      ).success
      _ <- auth.syncConfiguration()
    yield (clientId, result.secret)

  /** Points the default tenant at `signingKeyId` and makes auth pick the change up, key set
    * included — a selected key is only signable once its private half has been synced.
    */
  private def select(auth: OAuthClient, signingKeyId: Option[String]): Task[Unit] =
    auth.upsertChallengeSettings(signingKeyId = signingKeyId) *> auth.syncConfiguration()

  /** An access token issued under whatever key the tenant is currently pointed at, with its
    * header and the key set it must verify against.
    */
  private def issue(auth: OAuthClient, clientId: String, clientSecret: String): Task[(JwtHeader, Boolean)] =
    for
      token <- auth.clientCredentials(clientId, clientSecret).success
      head <- header(token.accessToken)
      jwkSet <- auth.jwks()
      verified <- auth.verifyJwtSignature(token.accessToken, jwkSet)
    yield (head, verified)

  /** Runs `use` with a freshly generated key selected for the tenant, then puts the tenant
    * back on its own configured key and deletes the generated one — in that order, because
    * central refuses to delete a key a tenant still signs with.
    */
  private def withSelectedKey[A](auth: OAuthClient, alg: String)(use: String => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(auth.generateJwksKey(alg))(kid =>
      (select(auth, None) *> auth.deleteJwksKey(kid)).orDie,
    )(kid => select(auth, Some(kid)) *> use(kid))

  def spec = suite("Tenant signing key selection")(
    test("a tenant's tokens are signed with the PS256 key it selected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        result <- withSelectedKey(auth, "PS256") { kid =>
          issue(auth, clientId, clientSecret).map(kid -> _)
        }
        (kid, (head, verified)) = result
      yield assertTrue(head.kid == kid)
        .label(s"the token must name the selected key, got kid=${head.kid} for selection '$kid'") &&
        assertTrue(head.alg == "PS256")
          .label(s"the algorithm must be the selected key's, got ${head.alg}") &&
        assertTrue(verified)
          .label("a token signed with the selected key must verify against the published JWKS")
    },

    // The one that cannot work by accident: auth's own configured private key is RSA, so an
    // ES256 token can only have been signed with a private half central generated and
    // published over the sync channel.
    test("a tenant's tokens are signed with the ES256 key it selected") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        result <- withSelectedKey(auth, "ES256") { kid =>
          issue(auth, clientId, clientSecret).map(kid -> _)
        }
        (kid, (head, verified)) = result
      yield assertTrue(head.kid == kid)
        .label(s"the token must name the selected key, got kid=${head.kid} for selection '$kid'") &&
        assertTrue(head.alg == "ES256")
          .label(s"an EC key cannot be signed with under an RSA algorithm, got ${head.alg}") &&
        assertTrue(verified)
          .label("the published EC JWK must verify the token signed with its private half")
    },

    // Clearing the selection is what a deployment seeded from `bootstrap.jwks` lives on
    // permanently: central holds no private half for those keys, so auth signs with the one
    // in its own configuration.
    test("clearing the selection puts the tenant back on auth's configured key") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- registerClient(auth)
        selected <- withSelectedKey(auth, "PS256")(_ => issue(auth, clientId, clientSecret))
        // `withSelectedKey` has already cleared the selection by here.
        (clearedHead, clearedVerified) <- issue(auth, clientId, clientSecret)
      yield assertTrue(selected._1.alg == "PS256")
        .label("the selected key must have been in use for the comparison to mean anything") &&
        assertTrue(clearedHead.kid != selected._1.kid)
          .label(s"the cleared tenant must not still sign with '${selected._1.kid}'") &&
        assertTrue(clearedVerified)
          .label("the fallback key must be published too, or nothing can verify its tokens")
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(120.seconds)
