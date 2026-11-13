package versola.e2e.flows.edge

import versola.e2e.support.*
import zio.*
import zio.http.{Client, Method, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** A client registered by the name of an edge group, served by two edges of the group that hold different
  * certificates, over one session store (#1008).
  *
  * Auth binds an access token to the certificate it was issued over (RFC 8705 §3) and checks that thumbprint exactly
  * on `/userinfo`. So a session that moves to another edge of the group, or meets its own edge after a renewal, holds a
  * token auth will not take. What keeps it alive is the refresh token: the edge refreshes before asking auth, and auth
  * lets the grant of such a client follow the certificate that authenticated it (§7.1, §6.3).
  *
  * How the two edges come to hold different certificates here. On `main` an edge cannot yet present its own group
  * certificate as a client's credential (that is the partner edge, #983/#997): a client's certificate is the one
  * central hands every edge. So the certificate of the group is supplied at registration (`edgeClientCertificate`), a
  * second edge is started from the staged build on the same database with its configuration refresh held back, and the
  * client's certificate is then replaced and synced to the staged edge only. From there on the staged edge presents
  * the new certificate and the second edge the old one: two certificates of one group, both of which authenticate the
  * client, which is the state every renewal passes through and the one a group of partner edges is always in.
  *
  * What only this level shows: that the registration by a group name survives central's API and auth's cache, that the
  * re-bound grant is what the terminator, auth and the other edge all agree on, and that the session row two edges
  * share carries the rotation from one to the other.
  */
object EdgeGroupCertificateSpec
  extends ZIOSpec[OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client]:

  private val UpstreamPort = 9115

  private val SecondEdgeUrl = "http://localhost:9045"
  // MPORT too: the staged edge holds the default one (8083) for its mutual-TLS listener.
  private val SecondEdgePorts = Map("PORT" -> "9045", "DPORT" -> "9046", "MPORT" -> "9047")

  /** The second edge reads central at start-up and when it is asked to, never on its own within a run: what it holds
    * for the client is then what the spec last synced to it. */
  private val HoldConfiguration = """configuration-cache-refresh-interval = "1 hour""""

  private val Group = "e2e-rebind"

  /** How long a configuration change is given to reach an edge that was asked to sync: central brings its own caches
    * up to date by a notification the writing request does not wait for. */
  private val Propagation = 60.seconds

  private val config = EdgeFixture.Config(
    resourceId = "e2e-edge-group-certificate",
    resourceUri = UpstreamStub.uriOn(UpstreamPort),
    endpoints = List(
      EdgeFixture.Endpoint(
        name = "items",
        method = "GET",
        path = "/items",
        permitted = true,
        fetchUserInfo = false,
        allow = None,
        inject = Nil,
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
      // The endpoint that makes an edge show the session's token to auth, and the injected header that proves auth
      // answered with claims.
      EdgeFixture.Endpoint(
        name = "profile",
        method = "GET",
        path = "/profile",
        permitted = true,
        fetchUserInfo = true,
        allow = None,
        inject = List(Fixtures.inject("header", "X-User-Sub", "user.sub")),
        stepUpCondition = None,
        stepUpAcr = None,
        maxAge = None,
      ),
    ),
    scopes = Set("openid", "email", "offline_access"),
    // `offline_access`: the session needs the refresh token the edge re-binds it with.
    presetScope = Set("openid", "offline_access"),
    postLogoutRedirectUri = None,
    cookiePath = None,
    awaitProxyReady = true,
    privateKeyJwt = false,
    requireSignedRequestObject = false,
    requirePushedAuthorizationRequests = false,
    mutualTls = false,
    nativeApp = false,
    issuedCertificate = false,
    enrolledCertificate = false,
    groupCertificate = Some(Group),
  )

  override val bootstrap: ZLayer[Any, Any, OAuthClient & CentralApi & EdgeApi & EdgeFixture & UpstreamStub & E2EConfig & Client] =
    val base = E2EConfig.live ++ Client.default
    val clients = base >>> (OAuthClient.live ++ CentralApi.live ++ EdgeApi.live)
    base ++ ((clients ++ UpstreamStub.liveOn(UpstreamPort)) >+> EdgeFixture.layer(config))

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private val fixture = ZIO.service[EdgeFixture]
  private val upstream = ZIO.service[UpstreamStub]

  /** The staged edge, and the second one once it runs. */
  private val firstEdge = ZIO.service[EdgeApi]
  private val secondEdge =
    ZIO.serviceWith[Client](client => ZIO.serviceWith[E2EConfig](config => EdgeApi(client, config.copy(edgeUrl = SecondEdgeUrl)))).flatten

  private def signIn(edge: EdgeApi): ZIO[OAuthClient & EdgeFixture, Throwable, EdgeSession] =
    for
      auth <- ZIO.service[OAuthClient]
      f <- fixture
      session <- edge.browserLogin(auth, f.presetId, f.login, f.password)
    yield session

  /** `x5t#S256` of the access token an `EDGE_SESSION` cookie carries, or `None` for a token bound to no certificate. */
  private def boundTo(cookie: String): Task[Option[String]] =
    for
      token <- ZIO.succeed(cookie.dropWhile(_ != ':').drop(1))
      payload <- ZIO.attempt(String(java.util.Base64.getUrlDecoder.decode(token.split('.')(1)), "UTF-8"))
      json <- ZIO.fromEither(payload.fromJson[Json.Obj]).mapError(RuntimeException(_))
    yield json.get("cnf").flatMap(_.asObject).flatMap(_.get("x5t#S256")).flatMap(_.asString)

  /** One call of the endpoint that needs `/userinfo`, as a browser holding `cookie` makes it, and the cookie the browser
    * holds afterwards: the one the response set, or the one it sent. */
  private def profile(edge: EdgeApi, cookie: String): ZIO[EdgeFixture, Throwable, (ApiResult, String)] =
    for
      f <- fixture
      result <- edge.proxy(Method.GET, f.resourceId, "/profile", EdgeAuth.Session(cookie))
    yield (result, EdgeApi.sessionCookie(result.response).filter(_.nonEmpty).getOrElse(cookie))

  /** Starts the second edge and waits until it serves the fixture with the certificate it read at start-up. */
  private def startSecondEdge(session: EdgeSession): ZIO[Scope & Client & E2EConfig & EdgeFixture, Throwable, EdgeApi] =
    for
      e2e <- ZIO.service[E2EConfig]
      f <- fixture
      running <- ExtraService.start(e2e.edgeLauncher, e2e.edgeEnvConf, HoldConfiguration, SecondEdgePorts)
      _ <- ExtraService.awaitReady(running)
      edge <- secondEdge
      // Awaited: the second edge proxies the fixture's resource for a session of the first. A 404 or a 403 is a
      // snapshot of central taken before the fixture reached its caches, so the edge is asked again.
      settled <- (edge.syncConfiguration *> edge.proxy(Method.GET, f.resourceId, "/items", session.auth).map(_.status == Status.Ok))
        .catchAll(_ => ZIO.succeed(false))
        .repeat(Schedule.spaced(1.second) *> Schedule.recurUntilEquals(true))
        .timeout(Propagation)
      _ <- ZIO.fail(RuntimeException("the second edge never served the fixture's resource")).unless(settled.contains(true))
    yield edge

  /** Replaces the client's certificate in central with a new one of the same group and has `edge`, and only it, read
    * it. Returns once that edge presents it: a fresh sign-in there yields a token bound to it. */
  private def renewOn(edge: EdgeApi): ZIO[OAuthClient & EdgeFixture, Throwable, EdgeCertificate] =
    for
      auth <- ZIO.service[OAuthClient]
      f <- fixture
      renewed <- EdgeCertificate.make(uri = Some(EdgeFixture.groupName(Group)))
      _ <- auth.updateClient(Fixtures.clientUpdate(f.clientId, "edgeClientCertificate" -> Json.Str(renewed.edgeClientCertificate)))
      // Awaited: the edge holds the new certificate. Each attempt is a fresh pull, for the reason `startSecondEdge` gives.
      presented <- (edge.syncConfiguration *> signIn(edge).flatMap(session => boundTo(session.cookie)).map(_.contains(renewed.thumbprint)))
        .catchAll(_ => ZIO.succeed(false))
        .repeat(Schedule.spaced(1.second) *> Schedule.recurUntilEquals(true))
        .timeout(Propagation)
      _ <- ZIO.fail(RuntimeException("the edge never presented the renewed certificate")).unless(presented.contains(true))
    yield renewed

  def spec = suite("a client registered by an edge-group name, served by edges with different certificates")(
    test("a session started on one edge continues on another, and back, with no sign-in: each edge re-binds it") {
      ZIO.scoped:
        for
          f <- fixture
          stub <- upstream
          first <- firstEdge
          original <- ZIO.fromOption(f.certificate).orElseFail(RuntimeException("the fixture holds no certificate"))
          warmUp <- signIn(first)
          second <- startSecondEdge(warmUp)
          // From here the first edge presents `renewed` and the second still `original`.
          renewed <- renewOn(first)

          session <- signIn(first)
          boundAtSignIn <- boundTo(session.cookie)

          _ <- stub.reset
          (onSecond, afterSecond) <- profile(second, session.cookie)
          boundAfterSecond <- boundTo(afterSecond)
          seenFromSecond <- stub.requests.map(_.findLast(_.path == "/profile"))

          _ <- stub.reset
          (onFirst, afterFirst) <- profile(first, afterSecond)
          boundAfterFirst <- boundTo(afterFirst)
          seenFromFirst <- stub.requests.map(_.findLast(_.path == "/profile"))

          // Bound to this edge's certificate now: asking again refreshes nothing.
          (again, afterAgain) <- profile(first, afterFirst)
        yield assertTrue(original.thumbprint != renewed.thumbprint, boundAtSignIn.contains(renewed.thumbprint))
          .label("the control: the session starts on the first edge, bound to the certificate only that edge holds") &&
          assertTrue(onSecond.status == Status.Ok, seenFromSecond.flatMap(_.header("X-User-Sub")).exists(_.nonEmpty))
            .label("the second edge serves the call that needs /userinfo, with the user's claims") &&
          assertTrue(boundAfterSecond.contains(original.thumbprint), afterSecond != session.cookie)
            .label("having re-bound the session to its own certificate and handed the browser the new cookie") &&
          assertTrue(onFirst.status == Status.Ok, seenFromFirst.flatMap(_.header("X-User-Sub")).exists(_.nonEmpty))
            .label("the first edge serves the session the second one re-bound") &&
          assertTrue(boundAfterFirst.contains(renewed.thumbprint), afterFirst != afterSecond)
            .label("by re-binding it back: both certificates are the group's") &&
          assertTrue(again.status == Status.Ok, afterAgain == afterFirst)
            .label("and a session bound to the edge's own certificate is left as it is")
    },
    test("a certificate renewal on an edge does not end the sessions it holds") {
      for
        stub <- upstream
        first <- firstEdge
        session <- signIn(first)
        boundAtSignIn <- boundTo(session.cookie)
        (before, _) <- profile(first, session.cookie)

        renewed <- renewOn(first)

        _ <- stub.reset
        (after, cookie) <- profile(first, session.cookie)
        boundAfter <- boundTo(cookie)
        seen <- stub.requests.map(_.findLast(_.path == "/profile"))
      yield assertTrue(before.status == Status.Ok, !boundAtSignIn.contains(renewed.thumbprint))
        .label("the control: the session was served before the renewal, bound to the certificate of that time") &&
        assertTrue(after.status == Status.Ok, seen.flatMap(_.header("X-User-Sub")).exists(_.nonEmpty))
          .label("the same cookie is served after the renewal, with no sign-in") &&
        assertTrue(boundAfter.contains(renewed.thumbprint), cookie != session.cookie)
          .label("because the edge re-bound the session to the renewed certificate")
    },
  ) @@ TestAspect.sequential
