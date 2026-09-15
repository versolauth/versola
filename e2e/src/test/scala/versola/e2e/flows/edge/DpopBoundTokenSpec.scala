package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.test.*

/** RFC 9449 end to end, across all three services: a client that holds a key, a token auth
  * bound to it, and the two resource servers that have to check that binding -- edge on a
  * proxied call, auth on `/userinfo`.
  *
  * The test that only this level can give is the first one. An endpoint with `fetchUserInfo`
  * makes edge call auth's `/userinfo` on its own behalf, presenting the user's token over
  * `Bearer` -- which, once the token is key-bound, is precisely the downgrade auth now
  * refuses. Edge cannot produce a proof for it: the key belongs to the client. It sends a
  * `Versola-Edge-Assertion` signed with the key central registered for it instead, and auth
  * honours it only after fetching that key from central's edges registry. Every component
  * spec on that path stubs the one next to it; here central really issues the key, auth
  * really syncs the registry, and edge really signs.
  *
  * Requires an edge with a `dpop { }` block configured (as `scripts/gen-env.scala` generates
  * by default) -- without one, edge refuses the `DPoP` scheme outright and the bound-token
  * tests below fail at the first call rather than telling you the assertion broke.
  */
object DpopBoundTokenSpec extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub]:

  /** Its own upstream origin: `EdgeProxySpec` already claims the default one, and two
    * resources may not share a URI.
    */
  private val UpstreamPort = 9105

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-dpop",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      // The endpoint that makes edge call `/userinfo`, and the injected header that proves
      // the call came back with claims rather than a 401.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        fetchUserInfo = true,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
      ),
      EdgeFixture.Endpoint(name = "items", method = "GET", path = "/items"),
    ),
    awaitProxyReady = true,
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub] =
    val clients = (E2EConfig.live ++ Client.default) >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    (clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val edge = ZIO.service[EdgeApi]
  private val auth = ZIO.service[OAuthClient]
  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]

  /** An authorization code flow for the fixture's user, ending at `/token`.
    *
    * `dpop` decides whether the issued token carries a `cnf.jkt`, which is the only difference
    * between the bound and unbound cases every test below contrasts. The redirect URI is
    * edge's `/complete` because that is what the fixture's client registers; the code is
    * redeemed here rather than by edge, so nothing is proxied by accident.
    */
  private def accessToken(
      dpop: Option[DpopProver] = None,
  ): ZIO[OAuthClient & EdgeApi & EdgeFixture, Throwable, String] =
    for
      authApi <- auth
      edgeApi <- edge
      f <- fixture
      started <- authApi.authorizeRaw(f.clientId, edgeApi.completeUri)
      conversation <- ZIO.fromOption(started.conversationCookie)
        .orElseFail(RuntimeException(s"the OP started no conversation (status=${started.response.status})"))
      challenge <- authApi.getChallenge(conversation)
      submitted <- authApi.submitLoginPassword(conversation, f.login, f.password, challenge.csrf)
      code <- submitted.assertRedirect
      issued <- authApi.token(
        code,
        started.verifier,
        clientId = Some(f.clientId),
        clientSecret = Some(f.clientSecret),
        redirectUri = Some(edgeApi.completeUri),
        dpop = dpop,
      ).success
    yield issued.accessToken

  /** A proxied call under the `DPoP` scheme, including the §9 nonce round trip.
    *
    * Edge demands a nonce for every proof once it is configured for DPoP, so the first call is
    * expected to come back 401 carrying one. Exactly one retry: a second challenge would mean
    * the nonce edge just issued is not one it accepts.
    */
  private def dpopProxy(
      method: Method,
      path: String,
      token: String,
      prover: DpopProver,
  ): ZIO[EdgeApi & EdgeFixture, Throwable, ApiResult] =
    for
      edgeApi <- edge
      f <- fixture
      htu = edgeApi.proxyUrl(f.resourceId, path)
      call = (nonce: Option[String]) =>
        prover.proof(method, htu, accessToken = Some(token), nonce = nonce)
          .flatMap(proof => edgeApi.proxy(method, f.resourceId, path, EdgeAuth.Dpop(token, proof)))
      challenged <- call(None)
      answered <- DpopProver.nonceOf(challenged.response) match
        case None => ZIO.succeed(challenged)
        case Some(nonce) => call(Some(nonce))
    yield answered

  private def clean[R, A](effect: ZIO[R, Throwable, A]): ZIO[R & UpstreamStub, Throwable, A] =
    upstream.flatMap(_.reset) *> effect

  def spec = suite("Edge + auth: DPoP-bound tokens")(
    test("a bound token reaches an endpoint whose authorization needs userinfo") {
      clean:
        for
          f <- fixture
          stub <- upstream
          prover <- DpopProver.make
          token <- accessToken(dpop = Some(prover))
          result <- dpopProxy(Method.GET, "/profile", token, prover)
          seen <- stub.lastRequest
        yield assertTrue(result.status == Status.Ok)
          .label(
            "edge has to fetch userinfo for this endpoint, and it can only present the user's " +
              "bound token over Bearer -- a 401 here means auth did not honour edge's assertion",
          ) &&
          assertTrue(seen.flatMap(_.header("X-User-Sub")).contains(f.userId.toString))
            .label("the injected header is built from the userinfo response, so it proves the call came back")
    },
    test("an unbound token reaches the same endpoint over Bearer, with no assertion involved") {
      clean:
        for
          edgeApi <- edge
          f <- fixture
          stub <- upstream
          token <- accessToken()
          result <- edgeApi.proxy(Method.GET, f.resourceId, "/profile", EdgeAuth.Bearer(token))
          seen <- stub.lastRequest
        yield assertTrue(result.status == Status.Ok) &&
          assertTrue(seen.flatMap(_.header("X-User-Sub")).contains(f.userId.toString))
            .label("nothing about the assertion may cost the common case: this token was never bound")
    },
    test("a bound token presented over Bearer at the edge is still refused") {
      clean:
        for
          edgeApi <- edge
          f <- fixture
          stub <- upstream
          prover <- DpopProver.make
          token <- accessToken(dpop = Some(prover))
          result <- edgeApi.proxy(Method.GET, f.resourceId, "/items", EdgeAuth.Bearer(token))
          seen <- stub.requests
        yield assertTrue(result.status == Status.Unauthorized)
          .label("§7.2: the downgrade edge refuses -- the assertion is not a way to turn DPoP off")
          && assertTrue(seen.isEmpty)
    },
    test("auth refuses a bound token at /userinfo over Bearer and accepts it with a proof") {
      for
        authApi <- auth
        f <- fixture
        prover <- DpopProver.make
        token <- accessToken(dpop = Some(prover))
        downgraded <- authApi.userinfo(token)
        proved <- authApi.userinfoDpop(token, prover).success
      yield assertTrue(downgraded.response.status == Status.Unauthorized)
        .label("a client is not an edge: nothing signs for it, so §7.2 stands")
        && assertTrue(proved.sub == f.userId)
    },
    test("auth refuses an unbound token at /userinfo under the DPoP scheme") {
      for
        authApi <- auth
        prover <- DpopProver.make
        token <- accessToken()
        result <- authApi.userinfoDpop(token, prover)
      yield assertTrue(result.response.status == Status.Unauthorized)
        .label("§7.1: a proof over a token nobody bound proves nothing, so it is not waved through")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
