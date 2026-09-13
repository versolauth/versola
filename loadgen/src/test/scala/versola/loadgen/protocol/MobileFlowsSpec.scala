package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.test.*
import zio.{Ref, UIO, ZIO}

import java.util.UUID

object MobileFlowsSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val sutUserId = UUID.fromString("99999999-8888-7777-6666-555555555555")
  private val expectedTokens = Tokens(AccessToken("at-1"), Some(RefreshToken("rt-1")), Some(IdToken("it-1")), 900L)

  /** Collects what a [[FlowObserver]] would have fed the histograms. */
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

  private def flowsFor(
      routes: Routes[Any, Nothing],
      recorder: RecordingObserver,
  ): ZIO[TestClient & Client, ProtocolError, MobileFlows] =
    for
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
      actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
    yield MobileFlows(auth, actions, StubSut.registry, recorder, Otp.nonProd(6), StubSut.origin)

  private def request(clientId: String): LoginRequest = LoginRequest(Some(clientId), "openid phone", None, None)

  def spec = suite("MobileFlows")(
    test("§8.1 phone + OTP walks authorize, two challenge pages, two submits and the code exchange") {
      for
        stub <- StubSut.make(List("credential", "otp"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (tokens, ssoSession) <- flows.mobileOtp(request("mobile-otp"), "+70000000001")
        hops <- sut.paths
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        tokens == expectedTokens,
        // The newly-issued SSO_SESSION cookie (§7.4) must reach the caller, not just get
        // consumed and dropped somewhere in `converse`/`follow`.
        ssoSession == Some(SsoSession(StubSut.ssoSession)),
        hops == Vector(
          "GET /authorize",
          "GET /challenge",
          "POST /challenge/phone",
          "GET /challenge",
          "POST /challenge/otp",
          "POST /token",
        ),
        steps == Vector("authorize", "challenge", "submit-phone", "challenge", "submit-otp", "token-code"),
        flowNames == Vector("mobile-otp"),
      )
    },
    test("§8.2 adds the password submit the SUT asks for, and reports as its own flow") {
      for
        stub <- StubSut.make(List("credential", "otp", "password"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        (tokens, _) <- flows.mobileOtpPassword(request("mobile-otp-password"), "+70000000002", "hunter2")
        hops <- sut.paths
        form <- sut.formOf("/challenge/password")
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        tokens == expectedTokens,
        hops.count(_ == "GET /challenge") == 3,
        hops.last == "POST /token",
        form == Some(Map("password" -> "hunter2", "csrf" -> StubSut.csrf)),
        steps == Vector(
          "authorize",
          "challenge",
          "submit-phone",
          "challenge",
          "submit-otp",
          "challenge",
          "submit-password",
          "token-code",
        ),
        flowNames == Vector("mobile-otp-password"),
      )
    },
    test("§8.3 fetches the options, signs them locally and posts the assertion") {
      for
        stub <- StubSut.make(List("credential"))
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        credential <- SoftAuthenticator.create(StubSut.creationOptions("Y2hhbGxlbmdl"), StubSut.origin)
        (tokens, _) <- flows.mobilePasskey(request("mobile-passkey"), credential, sutUserId)
        hops <- sut.paths
        form <- sut.formOf("/challenge/passkey")
        steps <- recorder.stepNames
      yield assertTrue(
        tokens == expectedTokens,
        hops == Vector(
          "GET /authorize",
          "GET /challenge",
          "GET /challenge/passkey/options",
          "POST /challenge/passkey",
          "POST /token",
        ),
        // The signing itself is not a hop: no step sits between the two measured ones.
        steps == Vector("authorize", "challenge", "passkey-options", "submit-passkey", "token-code"),
        form.exists(_.get("response").exists(_.contains("\"signature\""))),
        form.exists(_.get("csrf") == Some(StubSut.csrf)),
      )
    },
    test("§8.5 refresh is one hop and one flow") {
      for
        stub <- StubSut.make(Nil)
        (sut, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        tokens <- flows.refresh(RefreshToken("rt-0"), StubSut.publicClient.creds)
        form <- sut.formOf("/token")
        steps <- recorder.stepNames
        flowNames <- recorder.flowNames
      yield assertTrue(
        tokens == expectedTokens,
        form.exists(_.get("grant_type") == Some("refresh_token")),
        form.exists(_.get("refresh_token") == Some("rt-0")),
        steps == Vector("token-refresh"),
        flowNames == Vector("refresh"),
      )
    },
    test("a failing hop is still reported, with the error, before the flow gives up") {
      for
        stub <- StubSut.make(List("credential", "otp"))
        (_, routes) = stub
        recorder <- observer
        flows <- flowsFor(
          routes.transform(route =>
            route.contramapZIO(request =>
              if request.url.path.toString == "/challenge/otp" then ZIO.fail(Response.status(Status.InternalServerError))
              else ZIO.succeed(request),
            ),
          ),
          recorder,
        )
        failure <- flows.mobileOtp(request("mobile-otp"), "+70000000003").either
        failures <- recorder.failures
        steps <- recorder.stepNames
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.Ok, Status.SeeOther), Status.InternalServerError, "/challenge/otp")),
        failures.map(_._1) == Vector(StepName.SubmitOtp),
        steps == Vector("authorize", "challenge", "submit-phone", "challenge", "submit-otp"),
      )
    },
    test("a step the virtual user has no credential for fails typed instead of looping") {
      for
        stub <- StubSut.make(List("credential", "password"))
        (_, routes) = stub
        recorder <- observer
        flows <- flowsFor(routes, recorder)
        failure <- flows.mobileOtp(request("mobile-otp"), "+70000000004").either
      yield assertTrue(failure.left.exists:
        case ProtocolError.Misconfigured(_) => true
        case _ => false)
    },
    test("an error redirect out of the conversation ends the flow instead of re-fetching forever") {
      for
        stub <- StubSut.make(List("credential"))
        (_, routes) = stub
        recorder <- observer
        flows <- flowsFor(
          routes.transform(route =>
            route.contramapZIO: request =>
              if request.url.path.toString == "/challenge/phone" then
                ZIO.fail(Response.seeOther(URL.decode(StubSut.redirectUri + "?error=access_denied").toOption.get))
              else ZIO.succeed(request),
          ),
          recorder,
        )
        failure <- flows.mobileOtp(request("mobile-otp"), "+70000000005").either
      yield assertTrue(failure == Left(ProtocolError.MalformedResponse("/authorize", "access_denied")))
    },
  ).provide(TestClient.layer)
