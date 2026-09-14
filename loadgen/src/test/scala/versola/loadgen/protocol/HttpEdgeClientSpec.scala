package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.test.*
import zio.{Ref, ZIO, durationInt}

/** [[HttpEdgeClient]] hop by hop: the request shapes §8.4 prescribes, and the typed failure each
  * response the edge can actually answer with maps to.
  */
object HttpEdgeClientSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val preset = PresetId(StubSut.preset)

  private def edgeFor(routes: Routes[Any, Nothing]): ZIO[TestClient & Client, ProtocolError, EdgeClient] =
    for
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
      edge <- HttpEdgeClient.make(client, targets, actions, StubSut.requestTimeout)
    yield edge

  private def edgeForStub: ZIO[TestClient & Client, ProtocolError, (StubSut.Recorder, EdgeClient)] =
    for
      stub <- StubSut.makeWeb(List("credential", "otp"))
      (recorder, routes) = stub
      edge <- edgeFor(routes)
    yield (recorder, edge)

  /** A single-route stub, for the responses `StubSut` deliberately does not answer with. */
  private def edgeAnswering(response: Response): ZIO[TestClient & Client, ProtocolError, EdgeClient] =
    edgeFor(Routes.singleton(Handler.fromFunctionZIO[(Path, Request)]((_, _) => ZIO.succeed(response))))

  def spec = suite("HttpEdgeClient")(
    test("login reports where edge sent the browser and the state edge recorded") {
      for
        both <- edgeForStub
        (recorder, edge) = both
        started <- edge.login(preset, None)
        paths <- recorder.paths
      yield assertTrue(
        started == EdgeLoginStarted(StubSut.authorizeUrl, StubSut.edgeState),
        paths == Vector("GET /login/" + StubSut.preset),
      )
    },
    test("login forwards acr_values, the only way a web login can ask for an assurance level") {
      for
        both <- edgeForStub
        (_, edge) = both
        started <- edge.login(preset, Some(List(Acr.OtpLevel, Acr.PasswordLevel)))
      yield assertTrue(started.authorizeUrl.contains("acr_values=" + Acr.OtpLevel + "+" + Acr.PasswordLevel))
    },
    test("a preset edge does not know is the emulator's own misconfiguration, not an outcome") {
      for
        both <- edgeForStub
        (_, edge) = both
        failure <- edge.login(PresetId("no-such-preset"), None).either
      yield assertTrue(failure.left.exists:
        case ProtocolError.Misconfigured(detail) => detail.contains("no-such-preset")
        case _ => false)
    },
    test("a login that does not redirect is an unexpected status, not a started login") {
      for
        edge <- edgeAnswering(Response.ok)
        failure <- edge.login(preset, None).either
      yield assertTrue(failure == Left(ProtocolError.UnexpectedStatus(Set(Status.SeeOther), Status.Ok, "/login/{presetId}")))
    },
    test("a login redirect without a state is malformed: nothing later could complete it") {
      for
        edge <- edgeAnswering(Response.seeOther(URL.decode(StubSut.authUrl + "/authorize?client_id=web-otp").toOption.get))
        failure <- edge.login(preset, None).either
      yield assertTrue(failure.left.exists:
        case ProtocolError.MalformedResponse("/login/{presetId}", _) => true
        case _ => false)
    },
    test("startConversation follows edge's URL to auth and keeps the conversation cookie") {
      for
        both <- edgeForStub
        (recorder, edge) = both
        conversation <- edge.startConversation(EdgeLoginStarted(StubSut.authorizeUrl, StubSut.edgeState))
        paths <- recorder.paths
      yield assertTrue(
        conversation == ConversationCookie(StubSut.conversation),
        paths == Vector("GET /authorize"),
      )
    },
    test("an authorize response that starts no conversation is malformed") {
      for
        edge <- edgeAnswering(Response.seeOther(URL.decode("/challenge").toOption.get))
        failure <- edge.startConversation(EdgeLoginStarted(StubSut.authorizeUrl, StubSut.edgeState)).either
      yield assertTrue(failure.left.exists:
        case ProtocolError.MalformedResponse("/authorize", detail) => detail.contains("SSO_CONVERSATION")
        case _ => false)
    },
    test("complete returns the cookie with the Max-Age edge set on it") {
      for
        both <- edgeForStub
        (recorder, edge) = both
        cookie <- edge.complete(StubSut.edgeState, AuthCode(StubSut.code))
        paths <- recorder.paths
      yield assertTrue(
        cookie == EdgeCookie(EdgeSession(StubSut.edgeSession), Some(StubSut.edgeCookieTtl)),
        paths == Vector("GET /complete"),
      )
    },
    test("a state edge has no pending login for is a 400, and stays an unexpected status") {
      for
        both <- edgeForStub
        (_, edge) = both
        failure <- edge.complete("someone-elses-state", AuthCode(StubSut.code)).either
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.SeeOther), Status.BadRequest, "/complete")),
      )
    },
    test("a complete that redirects without the cookie is malformed: there is no session to use") {
      for
        edge <- edgeAnswering(Response.seeOther(URL.decode(StubSut.postLoginRedirect).toOption.get))
        failure <- edge.complete(StubSut.edgeState, AuthCode(StubSut.code)).either
      yield assertTrue(failure.left.exists:
        case ProtocolError.MalformedResponse("/complete", detail) => detail.contains("EDGE_SESSION")
        case _ => false)
    },
    test("completeError names the pending login edge must drop, and a second call is tolerated") {
      for
        both <- edgeForStub
        (recorder, edge) = both
        _ <- edge.completeError(StubSut.edgeState, StubSut.refusalError)
        // Edge answers the second one 400 (`AuthConversationNotFound`). Nothing is left to
        // consume, which is the only thing this hop is for, so it is not a failure.
        _ <- edge.completeError(StubSut.edgeState, StubSut.refusalError)
        paths <- recorder.paths
        params <- recorder.queryOf("/complete")
      yield assertTrue(
        paths == Vector("GET /complete", "GET /complete"),
        params.flatMap(_.get("error")) == Some(StubSut.refusalError),
        params.flatMap(_.get("state")) == Some(StubSut.edgeState),
      )
    },
    test("a completeError answered with neither a redirect nor a 400 is an unexpected status") {
      for
        edge <- edgeAnswering(Response.status(Status.InternalServerError))
        failure <- edge.completeError(StubSut.edgeState, StubSut.refusalError).either
      yield assertTrue(
        failure == Left(
          ProtocolError.UnexpectedStatus(Set(Status.SeeOther, Status.BadRequest), Status.InternalServerError, "/complete"),
        ),
      )
    },
    test("logout reports the auth logout edge redirected to, cookie sent as a browser sends it") {
      for
        both <- edgeForStub
        (recorder, edge) = both
        target <- edge.logout(preset, EdgeSession(StubSut.edgeSession))
        _ <- edge.endSession(EdgeSession(StubSut.edgeSession))
        paths <- recorder.paths
        onLogout <- recorder.headerOf("/logout/" + StubSut.preset, "cookie")
        onEndSession <- recorder.headerOf("/logout/frontchannel", "cookie")
      yield assertTrue(
        // The URL is edge's, not one rebuilt from the auth base: it carries the preset's
        // post-logout URI, which auth binds its confirmation token to.
        target == StubSut.authLogoutUrl,
        paths == Vector("GET /logout/" + StubSut.preset, "GET /logout/frontchannel"),
        onLogout == Some("EDGE_SESSION=" + StubSut.edgeSession),
        onEndSession == Some("EDGE_SESSION=" + StubSut.edgeSession),
      )
    },
    test("a front-channel logout that does not answer 200 is an unexpected status") {
      for
        edge <- edgeAnswering(Response.status(Status.InternalServerError))
        failure <- edge.endSession(EdgeSession(StubSut.edgeSession)).either
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.Ok), Status.InternalServerError, "/logout/frontchannel")),
      )
    },
    test("an edge URL that does not parse fails when the client is built, not inside a fiber") {
      for
        client <- ZIO.service[Client]
        actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
        failure <- HttpEdgeClient.make(client, targets.copy(edgeUrl = "not a url"), actions, StubSut.requestTimeout).either
      yield assertTrue(failure.left.exists:
        case ProtocolError.Misconfigured(_) => true
        case _ => false)
    },
    test("call is the ActionClient's, cookie rotation included") {
      for
        both <- edgeForStub
        (_, edge) = both
        outcome <- edge.call(
          EdgeCredential.Cookie(EdgeSession(StubSut.edgeSession)),
          ActionCall(Method.GET, "/resources/core/accounts", None),
        )
      yield assertTrue(
        outcome.status == Status.Ok,
        outcome.rotatedSession == Some(EdgeCookie(EdgeSession(StubSut.rotatedEdgeSession), Some(StubSut.edgeCookieTtl))),
      )
    },
  ).provide(TestClient.layer)
