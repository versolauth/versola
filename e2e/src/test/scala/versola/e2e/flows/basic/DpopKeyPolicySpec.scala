package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** The per-client half of RFC 9449 §5.1: which keys a given client's proofs are accepted with.
  *
  * The unit suites prove the two rules against a stubbed configuration service — the metadata
  * set intersected with the client's own, and the RSA modulus floor. What only this level
  * shows is that a policy registered through Central's API reaches the proof check at all: it
  * travels as two columns, through the client sync response, into auth's configuration cache,
  * and is read on a request whose proof is a header auth parses for itself.
  *
  * `client_credentials` rather than an authorization code flow, because nothing here depends
  * on a user — the proof is the client's, and the grant is the shortest path to a `/token`
  * call that carries one.
  */
object DpopKeyPolicySpec extends E2ESpec:

  private val redirectUri = "http://localhost:3000"

  /** RFC 6749 §5.2 error body, of which only the code is asserted on. */
  private case class OAuthError(error: String) derives JsonDecoder

  private def uid: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  private def client(
      auth: OAuthClient,
      dpopSigningAlgs: Set[String] = Set.empty,
      dpopMinRsaKeySize: Option[Int] = None,
      authFlow: Option[Json] = None,
  ): Task[(String, String)] =
    for
      id <- uid.map(s => s"dpop-policy-$s")
      result <- auth.registerClient(
        id,
        "DPoP Key Policy Test Client",
        Set(redirectUri),
        authFlow = authFlow,
        dpopSigningAlgs = dpopSigningAlgs,
        dpopMinRsaKeySize = dpopMinRsaKeySize,
      ).success
      _ <- auth.syncConfiguration()
    yield (id, result.secret)

  private def rejection(result: TokenResult): Task[String] =
    result match
      case s: TokenResult.Success =>
        ZIO.fail(RuntimeException(s"Expected /token to reject the proof, got ${s.response.status}"))
      case TokenResult.Failure(response, body) =>
        ZIO.fromEither(body.fromJson[OAuthError])
          .mapBoth(
            error => RuntimeException(s"Unparsable /token error body [$error]: $body"),
            parsed => parsed.error,
          )

  def spec = suite("DPoP proof key policy (RFC 9449 §5.1)")(
    test("a client that registered no policy is still refused an RSA key under the floor") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth)
        prover <- DpopProver.rsa(keySize = 1024)
        result <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover))
        error <- rejection(result)
      yield assertTrue(error == "invalid_dpop_proof")
        .label("RFC 7518 §3.3 is a floor, not an opt-in: a 1024-bit modulus binds a token to nothing")
    },
    test("the same client is issued a bound token for a 2048-bit RSA key") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth)
        prover <- DpopProver.rsa()
        issued <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover)).success
      yield assertTrue(issued.tokenType == "DPoP")
        .label("the refusal above has to be about the modulus, not about RSA proofs at all")
    },
    test("a registered minimum the deployment's floor does not reach is enforced") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth, dpopMinRsaKeySize = Some(4096))
        prover <- DpopProver.rsa()
        result <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover))
        error <- rejection(result)
      yield assertTrue(error == "invalid_dpop_proof")
        .label("the same 2048-bit key the previous test was issued a token for, refused by registration alone")
    },
    test("a client that registered ES256 only is refused a PS256 proof the deployment allows") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth, dpopSigningAlgs = Set("ES256"))
        prover <- DpopProver.rsa()
        result <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover))
        error <- rejection(result)
      yield assertTrue(error == "invalid_dpop_proof")
        .label("dpop_signing_alg_values_supported advertises PS256; this client narrowed itself out of it")
    },
    test("the same client's ES256 proof is honoured") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth, dpopSigningAlgs = Set("ES256"))
        prover <- DpopProver.make
        issued <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover)).success
      yield assertTrue(issued.tokenType == "DPoP")
    },
    test("Central refuses a registration whose RSA minimum is below the floor") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        id <- uid.map(s => s"dpop-policy-$s")
        result <- auth.registerClient(
          id,
          "DPoP Key Policy Test Client",
          Set(redirectUri),
          dpopMinRsaKeySize = Some(1024),
        )
      yield assertTrue(!result.response.status.isSuccess)
        .label("a stored value auth would ignore is a registration that lies about what it enforces")
    },
    // The policy constrains the moment a token is bound to a key, not every later use of that
    // binding. An authorization code flow rather than `client_credentials` because the proof
    // has to be re-presented at `/userinfo`, which needs a user behind the token.
    test("narrowing the policy leaves a token already bound under the wider one working") {
      for
        (login, auth) <- setup(Flows.Id.LoginPassword)
        (clientId, clientSecret) <- client(auth, authFlow = Some(Flows.loginPasswordAuthFlow))
        prover <- DpopProver.rsa()
        started <- auth.authorizeRaw(clientId, redirectUri)
        conversation <- ZIO.fromOption(started.conversationCookie)
          .orElseFail(RuntimeException(s"the OP started no conversation (status=${started.response.status})"))
        challenge <- auth.getChallenge(conversation)
        submitted <- auth.submitLoginPassword(conversation, login.login.get, login.password, challenge.csrf)
        code <- submitted.assertRedirect
        issued <- auth.token(
          code,
          started.verifier,
          clientId = Some(clientId),
          clientSecret = Some(clientSecret),
          redirectUri = Some(redirectUri),
          dpop = Some(prover),
        ).success

        _ <- auth.updateClient(
          Fixtures.clientUpdate(clientId, "dpopSigningAlgs" -> Json.Arr(Json.Str("ES256"))),
        )
        _ <- auth.syncConfiguration()

        // The narrowing has to be in force, or the assertion below passes for the wrong reason.
        refused <- auth.clientCredentials(clientId, clientSecret, dpop = Some(prover))
        error <- rejection(refused)

        served <- auth.userinfoDpop(issued.accessToken, prover).success
      yield assertTrue(error == "invalid_dpop_proof")
        .label("a new binding to this key is refused: the client no longer registers PS256")
        && assertTrue(served.sub == login.userId)
          .label(
            "the existing binding stands: it was made under the wider policy, and auth cannot " +
              "unbind a token an operator's later edit would not have allowed",
          )
    },
  ) @@ TestAspect.sequential
