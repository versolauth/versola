package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import versola.loadgen.protocol.Credentials
import zio.http.*
import zio.test.*
import zio.{Ref, UIO, ZIO}

/** §8.4 as a flow: the hop sequence, what each hop is measured as, and the cookie session's
  * lifecycle from establishment through rotation to expiry.
  */
object WebFlowsSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val request = WebLoginRequest(PresetId(StubSut.preset), None)
  private val credentials = Credentials.PhoneOtp("+70000000101")
  private val accounts = ActionCall(Method.GET, "/resources/core/accounts", None)

  private final class RecordingObserver(steps: Ref[Vector[(FlowName, StepName, Option[ProtocolError])]], flows: Ref[Vector[FlowName]])
    extends FlowObserver:
    override def step(flow: FlowName, step: StepName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] =
      steps.update(_ :+ (flow, step, error))

    override def flow(flow: FlowName, elapsedNanos: Long, error: Option[ProtocolError]): UIO[Unit] =
      flows.update(_ :+ flow)

    def stepNames: UIO[Vector[String]] = steps.get.map(_.map(_._2.value))
    def failures: UIO[Vector[(StepName, ProtocolError)]] = steps.get.map(_.collect { case (_, step, Some(error)) => (step, error) })
    def flowNames: UIO[Vector[String]] = flows.get.map(_.map(_.value))

  private def observer: UIO[RecordingObserver] =
    for
      steps <- Ref.make(Vector.empty[(FlowName, StepName, Option[ProtocolError])])
      flows <- Ref.make(Vector.empty[FlowName])
    yield RecordingObserver(steps, flows)

  private def flowsFor(routes: Routes[Any, Nothing], recorder: RecordingObserver): ZIO[TestClient & Client, ProtocolError, WebFlows] =
    for
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
      actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
      edge <- HttpEdgeClient.make(client, targets, actions, StubSut.requestTimeout)
    yield WebFlows(edge, auth, recorder, Otp.nonProd(6), StubSut.origin)

  def spec = suite("WebFlows")(
    test("§8.4 walks edge's login, auth's conversation and edge's complete, ending in a cookie") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, ssoSession) <- flows.webOtp(request, credentials)
        hops <- sut.paths
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        // The flow ends in a cookie and not a bearer token -- the one thing §8.4 has that
        // §8.1-8.3, §8.5 and §8.6 do not -- and it carries the expiry vu_sessions needs.
        cookie == EdgeCookie(EdgeSession(StubSut.edgeSession), Some(StubSut.edgeCookieTtl)),
        // The conversation still leaves an SSO_SESSION behind, even though edge started it.
        ssoSession == Some(SsoSession(StubSut.ssoSession)),
        hops == Vector(
          "GET /login/" + StubSut.preset,
          "GET /authorize",
          "GET /challenge",
          "POST /challenge/phone",
          "GET /challenge",
          "POST /challenge/otp",
          "GET /complete",
        ),
        // Seven hops, seven measured steps, and no /token: the driver never sees the code
        // exchange on this path, because edge makes it.
        steps == Vector("edge-login", "authorize", "challenge", "submit-phone", "challenge", "submit-otp", "edge-complete"),
        flowNames == Vector("web-otp"),
      )
    },
    test("the conversation is the one §8.1 walks: an extra password step is followed, not refused") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp", "password"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, _) <- flows.webOtp(request, Credentials.PhoneOtpPassword("+70000000102", "hunter2"))
        form <- sut.formOf("/challenge/password")
        steps <- recorder.stepNames
      yield assertTrue(
        cookie.session == EdgeSession(StubSut.edgeSession),
        form == Some(Map("password" -> "hunter2", "csrf" -> StubSut.csrf)),
        steps == Vector(
          "edge-login",
          "authorize",
          "challenge",
          "submit-phone",
          "challenge",
          "submit-otp",
          "challenge",
          "submit-password",
          "edge-complete",
        ),
      )
    },
    test("a protected resource is called with the cookie, and the rotated one is what comes back") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, _) <- flows.webOtp(request, credentials)
        (outcome, next) <- flows.businessAction(cookie.session, accounts)
        sent <- sut.headerOf("/resources/core/accounts", "cookie")
        flowNames <- recorder.flowNames
      yield assertTrue(
        outcome.status == Status.Ok,
        sent == Some("EDGE_SESSION=" + StubSut.edgeSession),
        // §8.4's adoption rule: the session to use next is the one edge just rotated to, not
        // the one the call was made with.
        next == EdgeSession(StubSut.rotatedEdgeSession),
        flowNames == Vector("web-otp", "business-action"),
      )
    },
    test("an expired cookie is a planned Unauthorized, not a failure, and a fresh login renews it") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (_, routes) = stub
        recorder <- observer
        // Edge answers a cookie it can no longer refresh with a 401 plus a Location to the
        // app's own /login and a cleared cookie (`EdgeService`'s Reauthenticate outcome).
        expired = routes.transform[Any](route =>
          route.contramapZIO(request =>
            if request.url.path.toString.startsWith("/resources") then
              ZIO.fail(
                Response
                  .status(Status.Unauthorized)
                  .addHeader(Header.Location(URL.decode("/login/" + StubSut.preset).toOption.get))
                  .addCookie(Cookie.Response("EDGE_SESSION", "", maxAge = Some(zio.Duration.Zero))),
              )
            else ZIO.succeed(request),
          ),
        )
        flows <- flowsFor(expired, recorder)
        (cookie, _) <- flows.webOtp(request, credentials)
        failure <- flows.businessAction(cookie.session, accounts).either
        (renewed, _) <- flows.webOtp(request, credentials)
        flowNames <- recorder.flowNames
      yield assertTrue(
        failure == Left(ProtocolError.Unauthorized(accounts.path)),
        // Renewal is a fresh §8.4, measured as one: a web session has no refresh grant of its
        // own to exchange.
        renewed.session == EdgeSession(StubSut.edgeSession),
        flowNames == Vector("web-otp", "business-action", "web-otp"),
      )
    },
    test("a web logout is both hops: the navigation and the front-channel call that revokes") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, _) <- flows.webOtp(request, credentials)
        _ <- flows.logout(request, cookie.session)
        hops <- sut.paths
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        hops.takeRight(2) == Vector("GET /logout/" + StubSut.preset, "GET /logout/frontchannel"),
        steps.takeRight(2) == Vector("edge-logout", "edge-end-session"),
        flowNames == Vector("web-otp", "web-logout"),
      )
    },
    test("a state auth did not echo back stops the flow instead of completing someone else's login") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        tampered = routes.transform[Any](route =>
          route.contramapZIO(request =>
            if request.url.path.toString == "/challenge/otp" then
              ZIO.fail(Response.seeOther(URL.decode(StubSut.edgeUrl + "/complete?code=" + StubSut.code + "&state=another-users-state").toOption.get))
            else ZIO.succeed(request),
          ),
        )
        flows <- flowsFor(tampered, recorder)
        failure <- flows.webOtp(request, credentials).either
        hops <- sut.paths
      yield assertTrue(
        failure.left.exists:
          case ProtocolError.MalformedResponse("/complete", detail) => detail.contains("another-users-state")
          case _ => false,
        // The point of the check: /complete is never called with the wrong state.
        !hops.contains("GET /complete"),
      )
    },
    test("a failing hop is reported with its error before the flow gives up") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (_, routes) = stub
        recorder <- observer
        broken = routes.transform[Any](route =>
          route.contramapZIO(request =>
            if request.url.path.toString == "/complete" then ZIO.fail(Response.status(Status.InternalServerError))
            else ZIO.succeed(request),
          ),
        )
        flows <- flowsFor(broken, recorder)
        failure <- flows.webOtp(request, credentials).either
        failures <- recorder.failures
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.SeeOther), Status.InternalServerError, "/complete")),
        failures.map(_._1) == Vector(StepName.EdgeComplete),
        steps.last == "edge-complete",
        // The flow is still reported, so its latency lands in the histogram with the failure.
        flowNames == Vector("web-otp"),
      )
    },
  ).provide(TestClient.layer)
