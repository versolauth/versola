package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Response, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** #420/#421 end to end: a mobile app under FAPI 2.0, its client role split between edge and
  * the device.
  *
  * Central registers the client native and confidential at once (`application_type=native`,
  * `tls_client_auth`, with a certificate central issues from its own CA (#440), which auth's
  * mutual-TLS listener trusts, held by edge). Edge authenticates as it straight on that listener (`MPORT`), the device's
  * DPoP key is the only sender constraint, and the browser leg goes to auth directly. What only
  * this level shows is that the pieces agree across the services: the `applicationType` column
  * surviving central -> auth (`cnf` carrying `jkt` and no `x5t#S256`) and central -> edge (the
  * native endpoints serving the client at all), the certificate central encrypted to edge
  * being the one the listener's handshake accepts, the `dpop_jkt` edge pushed binding the code,
  * and the device proof reaching auth byte for byte through edge.
  *
  * Needs the staged stack with auth's mutual-TLS listener, edge's `native { }` block and
  * central's `client-certificate-authority`, all of which `scripts/gen-env.scala`'s `local`
  * target writes (see develop.md).
  */
object NativeAppFlowSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  private val UpstreamPort = 9109

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-native-app",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items")),
    nativeApp = true,
    issuedCertificate = true,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val auth = ZIO.service[OAuthClient]
  private val fixture = ZIO.service[EdgeFixture]

  /** `/native/start`'s answer, as the SDK reads it. */
  private case class Started(
      client_id: String,
      request_uri: String,
      expires_in: Long,
      authorization_endpoint: String,
      state: String,
      blob: String,
      token_endpoint: String,
      revocation_endpoint: String,
  ) derives JsonDecoder

  /** RFC 9449 §8/§9: a `DPoP-Nonce` challenge from whoever answered -- auth for the relayed
    * calls, edge for the proxied one -- answered once with the nonce signed into a fresh proof.
    * A second challenge would mean the nonce just issued is not accepted. */
  private def withNonce(call: Option[String] => Task[Response]): Task[Response] =
    call(None).flatMap: first =>
      DpopProver.nonceOf(first) match
        case Some(nonce) if first.status == Status.BadRequest || first.status == Status.Unauthorized =>
          call(Some(nonce))
        case _ => ZIO.succeed(first)

  private def body(response: Response): Task[String] = response.body.asString

  /** The claims of a JWT access token, read without verifying it -- only what `cnf` says is of
    * interest here, and auth signed it. */
  private def claims(accessToken: String): Json.Obj =
    val payload = java.util.Base64.getUrlDecoder.decode(accessToken.split('.')(1))
    String(payload, "UTF-8").fromJson[Json.Obj].toOption.get

  private def start(prover: DpopProver): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, Started] =
    for
      edgeApi <- edge
      f <- fixture
      authApi <- auth
      attempt = prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId))
        .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof)))
      // A client central registered a moment ago reaches edge's cache (404 here) and auth's
      // (401 invalid_client from /par, relayed) by a notification the registering request does
      // not wait for -- so an early answer of either is a fresh sync and another try, not a result.
      response <- withNonce(nonce =>
        prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId), nonce = nonce)
          .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof))),
      ).flatMap: first =>
        if first.status != Status.NotFound && first.status != Status.Unauthorized then ZIO.succeed(first)
        else
          (edgeApi.syncConfiguration *> authApi.syncConfiguration() *> withNonce(nonce =>
            prover.proof(Method.POST, edgeApi.nativeUrl("start", f.clientId), nonce = nonce)
              .flatMap(proof => edgeApi.native("start", f.clientId, List("scope" -> "openid offline_access"), Some(proof))),
          ))
            .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](r =>
              r.status != Status.NotFound && r.status != Status.Unauthorized,
            ))
            .timeout(60.seconds)
            .someOrElse(first)
      text <- body(response)
      started <- ZIO.fromEither(text.fromJson[Started])
        .mapError(reason => RuntimeException(s"/native/start answered ${response.status}: $text ($reason)"))
    yield started

  private def signIn(started: Started): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, NativeCallback] =
    for
      edgeApi <- edge
      authApi <- auth
      f <- fixture
      callback <- edgeApi.nativeAuthorize(
        authApi,
        started.authorization_endpoint,
        started.client_id,
        started.request_uri,
        f.login,
        f.password,
      )
    yield callback

  private def complete(
      started: Started,
      callback: NativeCallback,
      prover: DpopProver,
      blob: Option[String] = None,
  ): ZIO[EdgeApi & EdgeFixture, Throwable, Response] =
    for
      edgeApi <- edge
      f <- fixture
      form = List("code" -> callback.code, "state" -> callback.state, "iss" -> callback.iss) ++
        blob.orElse(Some(started.blob)).filter(_.nonEmpty).map("blob" -> _).toList
      response <- withNonce(nonce =>
        prover.proof(Method.POST, started.token_endpoint, nonce = nonce)
          .flatMap(proof => edgeApi.native("complete", f.clientId, form, Some(proof))),
      )
    yield response

  private def refresh(started: Started, refreshToken: String, prover: DpopProver): ZIO[EdgeApi & EdgeFixture, Throwable, Response] =
    for
      edgeApi <- edge
      f <- fixture
      response <- withNonce(nonce =>
        prover.proof(Method.POST, started.token_endpoint, nonce = nonce)
          .flatMap(proof => edgeApi.native("token", f.clientId, List("refresh_token" -> refreshToken), Some(proof))),
      )
    yield response

  /** Start, sign in, complete: the tokens a device ends up holding. */
  private def tokens(prover: DpopProver): ZIO[EdgeApi & OAuthClient & EdgeFixture, Throwable, (Started, TokenResult.Success)] =
    for
      started <- start(prover)
      callback <- signIn(started)
      completed <- complete(started, callback, prover)
      issued <- TokenResult.parse(completed).flatMap(_.success)
    yield (started, issued)

  def spec = suite("Native app through edge (#420)")(
    test("start -> authorize -> complete -> DPoP API call -> refresh -> revoke") {
      for
        edgeApi <- edge
        f <- fixture
        prover <- DpopProver.make
        (started, issued) <- tokens(prover)
        tokenClaims = claims(issued.accessToken)
        cnf = tokenClaims.get("cnf").flatMap(_.as[Json.Obj].toOption)

        // The API call, DPoP-bound to the device key, through edge's resource proxy. Edge's
        // configuration caches may lag the fixture by one notification, so a 404/403 is retried.
        htu = edgeApi.proxyUrl(f.resourceId, "/items")
        proxyOnce = withNonce(nonce =>
          prover.proof(Method.GET, htu, accessToken = Some(issued.accessToken), nonce = nonce)
            .flatMap(proof => edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Dpop(issued.accessToken, proof)))
            .map(_.response),
        )
        proxied <- (edgeApi.syncConfiguration *> proxyOnce)
          .repeat(Schedule.spaced(1.second) *> Schedule.recurUntil[Response](r => r.status != Status.NotFound && r.status != Status.Forbidden))
          .timeout(60.seconds).someOrFail(RuntimeException("edge never served the proxied resource"))

        refreshToken <- ZIO.fromOption(issued.refreshToken).orElseFail(RuntimeException("no refresh token issued"))
        refreshed <- refresh(started, refreshToken, prover)
        refreshedTokens <- TokenResult.parse(refreshed).flatMap(_.success)

        revoked <- withNonce(nonce =>
          prover.proof(Method.POST, started.revocation_endpoint, nonce = nonce)
            .flatMap(proof => edgeApi.native("revoke", f.clientId, List("token" -> refreshToken), Some(proof))),
        )
        afterRevoke <- refresh(started, refreshToken, prover)
        afterRevokeBody <- body(afterRevoke)
      yield assertTrue(
        issued.tokenType == "DPoP",
        cnf.flatMap(_.get("jkt")).contains(Json.Str(prover.jkt)),
      ).label("the code was bound at /par to the device key, and so is the token") &&
        assertTrue(cnf.exists(_.get("x5t#S256").isEmpty))
          .label("#421: never edge's certificate, which every installation shares") &&
        assertTrue(proxied.status == Status.Ok).label("the device-bound token is spendable at the resource") &&
        assertTrue(refreshedTokens.tokenType == "DPoP") &&
        assertTrue(revoked.status == Status.Ok) &&
        assertTrue(afterRevoke.status == Status.BadRequest, afterRevokeBody.contains("invalid_grant"))
          .label("a revoked refresh token is dead")
    },
    test("a code presented without its blob, or with another flow's blob, is refused without being spent") {
      for
        edgeApi <- edge
        f <- fixture
        prover <- DpopProver.make
        started <- start(prover)
        other <- start(prover)
        callback <- signIn(started)
        withoutBlob <- complete(started, callback, prover, blob = Some(""))
        withoutBlobBody <- body(withoutBlob)
        foreignBlob <- complete(started, callback, prover, blob = Some(other.blob))
        foreignBody <- body(foreignBlob)
        // Neither refusal reached auth, so the code is still good for the flow that owns it.
        rightful <- complete(started, callback, prover)
      yield assertTrue(withoutBlob.status == Status.BadRequest, withoutBlobBody.contains("invalid_request")) &&
        assertTrue(foreignBlob.status == Status.BadRequest, foreignBody.contains("invalid_grant")) &&
        assertTrue(rightful.status == Status.Ok)
    },
    test("a blob redeemed with a different device key is refused") {
      for
        prover <- DpopProver.make
        thief <- DpopProver.make
        started <- start(prover)
        callback <- signIn(started)
        stolen <- complete(started, callback, thief)
        stolenBody <- body(stolen)
        rightful <- complete(started, callback, prover)
      yield assertTrue(stolen.status == Status.BadRequest, stolenBody.contains("invalid_grant")) &&
        assertTrue(rightful.status == Status.Ok).label("the thief's attempt did not burn the code")
    },
    test("a refresh with a different device key is refused by auth, relayed as is") {
      for
        prover <- DpopProver.make
        thief <- DpopProver.make
        (started, issued) <- tokens(prover)
        refreshToken <- ZIO.fromOption(issued.refreshToken).orElseFail(RuntimeException("no refresh token issued"))
        stolen <- refresh(started, refreshToken, thief)
        stolenBody <- body(stolen)
        rightful <- refresh(started, refreshToken, prover)
      yield assertTrue(stolen.status == Status.BadRequest, stolenBody.contains("invalid_grant")) &&
        assertTrue(rightful.status == Status.Ok)
    },
    test("a clientId that is not a native client this edge fronts is a 404") {
      for
        edgeApi <- edge
        prover <- DpopProver.make
        unknown <- prover.proof(Method.POST, edgeApi.nativeUrl("start", "no-such-client"))
          .flatMap(proof => edgeApi.native("start", "no-such-client", Nil, Some(proof)))
        // A real, synced, confidential client -- just not a native one.
        web <- prover.proof(Method.POST, edgeApi.nativeUrl("start", "central-admin"))
          .flatMap(proof => edgeApi.native("start", "central-admin", Nil, Some(proof)))
      yield assertTrue(unknown.status == Status.NotFound, web.status == Status.NotFound)
    },
    test("start without a device proof is refused before anything is pushed") {
      for
        edgeApi <- edge
        f <- fixture
        response <- edgeApi.native("start", f.clientId, Nil, proof = None)
        text <- body(response)
      yield assertTrue(response.status == Status.BadRequest, text.contains("invalid_dpop_proof"))
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(240.seconds)
