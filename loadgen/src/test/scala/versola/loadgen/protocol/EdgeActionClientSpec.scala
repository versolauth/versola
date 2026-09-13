package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.test.*
import zio.{Ref, ZIO}

object EdgeActionClientSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)
  private val accounts = ActionCall(Method.GET, "/resources/core/accounts", None)

  private def clientFor(response: Response, seen: Ref[Option[Request]]): ZIO[TestClient & Client, ProtocolError, ActionClient] =
    for
      _ <- TestClient.addRoutes(
        Routes.singleton(
          Handler.fromFunctionZIO[(Path, Request)]((_, request) => seen.set(Some(request)).as(response)),
        ),
      )
      client <- ZIO.service[Client]
      actions <- EdgeActionClient.make(client, targets, StubSut.requestTimeout)
    yield actions

  private def stepUp(acrValues: String): Response =
    Response
      .status(Status.Unauthorized)
      .addHeader("WWW-Authenticate", """Bearer error="insufficient_user_authentication", acr_values="""" + acrValues + """"""")

  def spec = suite("EdgeActionClient")(
    test("a bearer action is proxied with the token and no cookie to rotate") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.json("""{"ok":true}"""), seen)
        outcome <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts)
        request <- seen.get
      yield assertTrue(
        outcome == ActionOutcome(Status.Ok, """{"ok":true}""", None),
        request.exists(_.url.path.toString == "/resources/core/accounts"),
        request.flatMap(_.rawHeader("authorization")) == Some("Bearer at-1"),
      )
    },
    test("a cookie action adopts the EDGE_SESSION edge rotated onto the response") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.json("{}").addCookie(Cookie.Response("EDGE_SESSION", "rotated")), seen)
        outcome <- actions.call(EdgeCredential.Cookie(EdgeSession("original")), accounts)
        request <- seen.get
      yield assertTrue(
        outcome.rotatedSession == Some(EdgeSession("rotated")),
        request.flatMap(_.rawHeader("cookie")) == Some("EDGE_SESSION=original"),
      )
    },
    test("a 401 naming insufficient_user_authentication is a step-up, with the ACR values it demands") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(stepUp(Acr.PasswordLevel + " " + Acr.PasskeyLevel), seen)
        failure <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts).either
      yield assertTrue(
        failure == Left(ProtocolError.StepUpRequired(List(Acr.PasswordLevel, Acr.PasskeyLevel), accounts.path)),
      )
    },
    test("a plain 401 is an expired token, not a step-up -- the two drive different branches") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.status(Status.Unauthorized), seen)
        failure <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts).either
      yield assertTrue(failure == Left(ProtocolError.Unauthorized(accounts.path)))
    },
    test("a 403 is the expected retail-basic outcome") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.status(Status.Forbidden), seen)
        failure <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts).either
      yield assertTrue(failure == Left(ProtocolError.Forbidden(accounts.path)))
    },
    test("a 404 from edge or the upstream is an UnexpectedStatus, not a successful ActionOutcome") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.status(Status.NotFound), seen)
        failure <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts).either
      yield assertTrue(failure == Left(ProtocolError.UnexpectedStatus(Set(Status.Ok), Status.NotFound, accounts.path)))
    },
    test("a 500 from edge or the upstream is an UnexpectedStatus, not a successful ActionOutcome") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.status(Status.InternalServerError), seen)
        failure <- actions.call(EdgeCredential.Bearer(AccessToken("at-1")), accounts).either
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.Ok), Status.InternalServerError, accounts.path)),
      )
    },
    test("a body is sent as JSON") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.json("{}"), seen)
        _ <- actions.call(
          EdgeCredential.Bearer(AccessToken("at-1")),
          ActionCall(Method.POST, "/resources/pay/payments", Some("""{"amount":100}""")),
        )
        request <- seen.get
        body <- ZIO.foreach(request)(_.body.asString).orDie
      yield assertTrue(
        body == Some("""{"amount":100}"""),
        request.flatMap(_.rawHeader("content-type")) == Some("application/json"),
      )
    },
  ).provide(TestClient.layer)
