package versola.e2e.flows.mtls

import versola.e2e.support.{*, given}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** RFC 8705 §5: `auth`'s own mutual-TLS listener, terminating TLS itself rather than reading a
  * header a proxy forwarded -- the separate, unrelated path [[MutualTlsSpec]] covers.
  *
  * [[versola.oauth.clientauth.ClientAuthenticationSpec]] already proves, against a stubbed
  * configuration service, that a certificate off the connection outranks one in a header.
  * [[versola.edge.SSOClientMutualTlsHandshakeSpec]] already proves, against a stub handler,
  * that a real Netty handshake delivers that certificate at all. What only this level can
  * show is that the two meet: a client registered through Central's real admin API, cached by
  * auth's real `OAuthConfigurationService`, is the one a real handshake against the staged
  * `auth-postgres-impl` process actually authenticates -- and that the address the discovery
  * document names for it (`mtls_endpoint_aliases`) is the address that works.
  *
  * `auth.registerClient` and `auth.discoveryDocument` reach the plain listener as every other
  * e2e spec does; only the request that is the point of this suite is sent over TLS, with the
  * certificate `scripts/gen-env.scala`'s `genAuthMutualTlsCertificate` wrote to
  * `auth/dev/mtls/client.{crt,key}` -- see [[versola.e2e.support.E2EConfig]] for where the
  * paths and the listener's own address come from.
  */
object MutualTlsListenerSpec extends E2ESpec:

  private val redirectUri = "http://localhost:3000"

  /** The `dNSName` `scripts/gen-env.scala` burned into the e2e client certificate. Duplicated
    * here rather than read off the certificate at runtime, the same way `Fixtures`' header
    * fixtures duplicate theirs -- there is nothing running yet, at the point that script signs
    * it, to hand this spec the value instead.
    */
  private val clientDnsName = "e2e-native-mtls-client.versola.test"

  private def uid: UIO[String] =
    ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  /** The two `ClientSSLConfig`s the tests below connect with: one presenting the registered
    * certificate, one presenting none at all. Both trust the listener's own certificate --
    * signed, like the client's, by `scripts/gen-env.scala`'s single generated CA -- since a
    * connection that does not even trust the *server* would fail for a reason neither test is
    * about.
    */
  private def authenticatedSsl(auth: OAuthClient): ClientSSLConfig =
    ClientSSLConfig.FromClientAndServerCert(
      ClientSSLConfig.FromCertFile(auth.authMutualTlsTrustedCertificates),
      ClientSSLCertConfig.FromClientCertFile(auth.authMutualTlsClientCertificate, auth.authMutualTlsClientKey),
    )

  private def anonymousSsl(auth: OAuthClient): ClientSSLConfig =
    ClientSSLConfig.FromCertFile(auth.authMutualTlsTrustedCertificates)

  private def clientCredentialsRequest(url: String, clientId: String, proof: Option[String] = None): Request =
    val request = Request.post(url, Body.fromString(s"grant_type=client_credentials&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}"))
      .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
    proof.fold(request)(value => request.addHeader(Header.Custom("DPoP", value)))

  /** One token call over a real handshake, `ssl` deciding what -- if anything -- is presented
    * on it. `Client.default`'s own instance is reused rather than rebuilt per call, and given
    * the connection's certificate the way `versola.edge.SSOClient` gives its own: `.ssl(...)`
    * on the client, not on the request, since a certificate is a fact about the connection.
    */
  private def tokenOver(
      ssl: ClientSSLConfig,
      url: String,
      clientId: String,
      proof: Option[String] = None,
  ): Task[TokenResult] =
    ZIO.scoped:
      for
        client <- Client.default.build.map(_.get[Client])
        result <- Client.batched(clientCredentialsRequest(url, clientId, proof)).provide(ZLayer.succeed(client.ssl(ssl)))
          .flatMap(TokenResult.parse)
      yield result

  /** A client registered to authenticate over this listener: no secret it would ever present,
    * `tls_client_auth` matching the certificate `scripts/gen-env.scala` generated. */
  private def mtlsClient(auth: OAuthClient): Task[String] =
    for
      id <- uid.map(s => s"native-mtls-client-$s")
      _ <- auth.registerClient(
        id,
        "Native Mutual TLS Test Client",
        Set(redirectUri),
        allowedScopes = Set("openid"),
        authMethod = "tls_client_auth",
        mtlsAuth = Some(Fixtures.mutualTlsAuth("san_dns", clientDnsName)),
      ).success
      _ <- auth.syncConfiguration()
    yield id

  def spec = suite("Mutual TLS listener (RFC 8705 §5)")(
    test("the discovery document aliases every endpoint the listener serves at its own address") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        aliases <- ZIO.fromOption(document.get("mtls_endpoint_aliases").flatMap(_.as[Json.Obj].toOption))
          .orElseFail(RuntimeException(s"Discovery document carries no 'mtls_endpoint_aliases' object: ${document.toJson}"))
        tokenAlias <- ZIO.fromOption(aliases.get("token_endpoint").flatMap(_.as[String].toOption))
          .orElseFail(RuntimeException(s"'mtls_endpoint_aliases' names no 'token_endpoint': ${aliases.toJson}"))
      yield assertTrue(tokenAlias == s"${auth.authMutualTlsUrl}/token")
        .label(s"expected the aliased token_endpoint to be ${auth.authMutualTlsUrl}/token, got $tokenAlias")
    },
    test("a client registered for mutual TLS authenticates over a real handshake against the listener") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        clientId <- mtlsClient(auth)
        result <- tokenOver(authenticatedSsl(auth), s"${auth.authMutualTlsUrl}/token", clientId).success
      yield assertTrue(result.accessToken.nonEmpty)
        .label("registration through Central, auth's configuration cache and a real TLS handshake must agree")
    },
    // RFC 9449 §4.3 over RFC 8705 §5: a client that followed the alias called a different
    // authority, so that is the authority §4.3 obliges it to stamp into `htu`. Only at this
    // level do the two RFCs actually meet -- the proof is signed against the address the
    // discovery document named, and verified by the listener that address reaches.
    test("a DPoP proof stamped with the listener's own address binds a token over it") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        clientId <- mtlsClient(auth)
        prover <- DpopProver.make
        aliasedTokenEndpoint = s"${auth.authMutualTlsUrl}/token"
        proof <- prover.proof(Method.POST, aliasedTokenEndpoint)
        result <- tokenOver(authenticatedSsl(auth), aliasedTokenEndpoint, clientId, Some(proof)).success
      yield assertTrue(result.tokenType == "DPoP")
        .label("a proof naming the aliased address is the correct one, and must not be rejected")
    },
    test("a DPoP proof stamped with the main listener's address is refused over this one") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        clientId <- mtlsClient(auth)
        prover <- DpopProver.make
        // The address the *unaliased* `token_endpoint` names -- correct for the main listener,
        // and for that reason exactly what §4.3 forbids here.
        proof <- prover.proof(Method.POST, s"${auth.issuer}/token")
        result <- tokenOver(authenticatedSsl(auth), s"${auth.authMutualTlsUrl}/token", clientId, Some(proof))
        body <- result.response.body.asString
      yield assertTrue(result.response.status == Status.BadRequest, body.contains("invalid_dpop_proof"))
        .label(s"expected the mismatched htu to be refused, got ${result.response.status}: $body")
    },
    test("the listener refuses a connection that presents no certificate at all") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        clientId <- mtlsClient(auth)
        // `ClientAuth.Required`: refused in the handshake itself, before any request reaches
        // a handler -- there is no HTTP response to inspect, only a failed connection.
        result <- tokenOver(anonymousSsl(auth), s"${auth.authMutualTlsUrl}/token", clientId).either
      yield assertTrue(result.isLeft)
        .label(s"expected the handshake itself to fail with no certificate presented, got $result")
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
