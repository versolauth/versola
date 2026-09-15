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
  private val request = WebLoginRequest(PresetId(StubSut.preset), None, None)
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
    test("adopting the rotation is what keeps the session alive, and replaying a superseded cookie is planned") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, _) <- flows.webOtp(request, credentials)
        (_, next) <- flows.businessAction(cookie.session, accounts)
        // Sufficient: the stub honours only the value it last issued, so this call succeeding is
        // the adoption working rather than the stub being permissive.
        (second, afterSecond) <- flows.businessAction(next, accounts)
        // Necessary: the same call a driver that dropped the rotation would have made. Edge's
        // cookie is the access token it rotated away from, and the refresh behind it is spent.
        replayed <- flows.businessAction(cookie.session, accounts).either
        sent <- sut.headerOf("/resources/core/accounts", "cookie")
      yield assertTrue(
        second.status == Status.Ok,
        afterSecond == EdgeSession(StubSut.rotatedEdgeSession),
        sent == Some("EDGE_SESSION=" + StubSut.edgeSession),
        // A dead cookie is the session ending, not the SUT failing: it stays out of the error
        // budget and the scenario renews with a fresh §8.4.
        replayed == Left(ProtocolError.Unauthorized(accounts.path)),
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
    test("a web logout is all four hops, and it is the confirmation that ends the SSO session") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (cookie, ssoSession) <- flows.webOtp(request, credentials)
        sso <- ZIO.fromOption(ssoSession).orElseFail(new AssertionError("login left no SSO_SESSION"))
        liveBefore <- sut.ssoLive
        _ <- flows.logout(request, cookie.session, sso)
        liveAfter <- sut.ssoLive
        hops <- sut.paths
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
        submitted <- sut.formOf("/logout")
        sent <- sut.headerOf("/logout", "cookie")
      yield assertTrue(
        liveBefore,
        // The session is actually gone at auth, not merely navigated away from. Edge's redirect
        // carries no `id_token_hint`, so auth renders rather than acts and only the submission
        // ends it -- the hop a two-hop logout skipped, leaving a session that would have
        // silently satisfied the next login. The test below is the other half: without a valid
        // confirmation the session survives.
        !liveAfter,
        hops.takeRight(4) == Vector(
          "GET /logout/" + StubSut.preset,
          "GET /logout",
          "POST /logout",
          "GET /logout/frontchannel",
        ),
        steps.takeRight(4) == Vector("edge-logout", "auth-logout", "auth-logout-confirm", "edge-end-session"),
        flowNames == Vector("web-otp", "web-logout"),
        // The token is bound to the parameters edge put on the redirect, so they are carried
        // through the page and posted back rather than rebuilt.
        submitted.flatMap(_.get("csrf_token")) == Some(StubSut.logoutCsrf),
        submitted.flatMap(_.get("post_logout_redirect_uri")) == Some(StubSut.postLogoutRedirect),
        sent == Some("SSO_SESSION=" + StubSut.ssoSession),
      )
    },
    test("a confirmation that lost the parameters it was bound to fails instead of reporting a logout") {
      for
        stub <- StubSut.makeWeb(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        // A driver that posted the token back without the `post_logout_redirect_uri` it was
        // minted against: auth answers 403 and the session stays live.
        stripped = routes.transform[Any](route =>
          route.contramapZIO(request =>
            if request.method == Method.POST && request.url.path.toString == "/logout" then
              request.body.asString.orDie.map(body =>
                request.withBody(Body.fromString(body.split('&').filterNot(_.startsWith("post_logout_redirect_uri")).mkString("&"))),
              )
            else ZIO.succeed(request),
          ),
        )
        flows <- flowsFor(stripped, recorder)
        (cookie, ssoSession) <- flows.webOtp(request, credentials)
        sso <- ZIO.fromOption(ssoSession).orElseFail(new AssertionError("login left no SSO_SESSION"))
        failure <- flows.logout(request, cookie.session, sso).either
        live <- sut.ssoLive
      yield assertTrue(
        failure.isLeft,
        // The session really is still there, so the failure is the truth and not a false alarm:
        // a logout reported as done here would be a session the campaign thinks it closed.
        live,
      )
    },
    test("a refused authorization still consumes edge's pending login, and reports the SUT's error") {
      for
        stub <- StubSut.makeWebRefused(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        pendingBefore <- sut.pendingLogin
        failure <- flows.webOtp(request, credentials).either
        pendingAfter <- sut.pendingLogin
        hops <- sut.paths
        steps <- recorder.stepNames
      yield assertTrue(
        pendingBefore,
        // What the report needs is the SUT's own error code, unchanged by the cleanup hop.
        failure == Left(ProtocolError.MalformedResponse("/authorize", StubSut.refusalError)),
        // Edge is not left holding the record it wrote when the login started: a driver that
        // stopped at the refusal would add one row to the SUT per refused login.
        !pendingAfter,
        hops.takeRight(1) == Vector("GET /complete"),
        // The refusal is its own step, and the success step it replaces was never measured.
        steps.takeRight(1) == Vector("edge-complete-error"),
        !steps.contains("edge-complete"),
      )
    },
    test("a cleanup hop that fails does not replace the refusal the SUT reported") {
      for
        stub <- StubSut.makeWebRefused(List("credential", "otp"))
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
      yield assertTrue(failure == Left(ProtocolError.MalformedResponse("/authorize", StubSut.refusalError)))
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
