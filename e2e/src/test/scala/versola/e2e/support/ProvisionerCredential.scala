package versola.e2e.support

import zio.*
import zio.json.*
import zio.json.ast.Json

/** What `loadgen provision` holds as `utils`: the private half of the key central registered,
  * with which it signs an RFC 7523 assertion for a token that RFC 9449 binds to a DPoP key.
  *
  * The token and the key travel together because edge asks for a proof from that key on every
  * proxied call -- a bearer presentation of the same token is refused.
  */
final case class ProvisionerSession(token: String, prover: DpopProver)

object ProvisionerCredential:

  def signer(config: E2EConfig): Task[AssertionSigner] =
    ZIO.fromEither(config.provisionerPrivateKey.fromJson[Json.Obj])
      .mapError(error => RuntimeException(s"E2E_PROVISIONER_PRIVATE_KEY is not a JWK: $error"))
      .flatMap(AssertionSigner.fromPrivateJwk)

  /** The token request itself, left unparsed for the test that expects it refused. */
  def request(auth: OAuthClient, config: E2EConfig, resources: List[String]): Task[(TokenResult, DpopProver)] =
    for
      key <- signer(config)
      prover <- DpopProver.make
      assertion <- key.assertion(config.provisionerClientId, auth.issuer)
      result <- auth.clientCredentials(
        clientId = config.provisionerClientId,
        clientSecret = "",
        resources = Some(resources),
        useBasicAuth = false,
        assertion = Some(assertion),
        dpop = Some(prover),
      )
    yield (result, prover)

  def session(auth: OAuthClient, config: E2EConfig, resources: List[String]): Task[ProvisionerSession] =
    request(auth, config, resources).flatMap((result, prover) =>
      result.success.map(issued => ProvisionerSession(issued.accessToken, prover)),
    )
