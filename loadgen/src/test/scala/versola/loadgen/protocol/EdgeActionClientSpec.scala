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

  private def dpopCall(action: ActionCall, response: Response, seen: Ref[Option[Request]]) =
    for
      actions <- clientFor(response, seen)
      key <- DpopKeyPool.derive("edge-action-spec", 1).map(_.keyFor(0L))
      outcome <- actions.call(EdgeCredential.Dpop(AccessToken("at-1"), key), action).either
      request <- seen.get
    yield (key, outcome, request)

  private def stepUp(acrValues: String): Response =
    Response
      .status(Status.Unauthorized)
      .addHeader("WWW-Authenticate", """Bearer error="insufficient_user_authentication", acr_values="""" + acrValues + """"""")

  private val dpopSuite = suite("a DPoP action")(
    // Edge dispatches on the `Authorization` scheme (`DpopVerifier.Scheme`), so this is not a
    // bearer call with an extra header -- sending `Bearer` would have edge skip the proof and
    // refuse the sender-constrained token it was given.
    test("replaces the Bearer scheme rather than accompanying it") {
      for
        seen <- Ref.make(Option.empty[Request])
        result <- dpopCall(accounts, Response.json("{}"), seen)
        (_, _, request) = result
      yield assertTrue(
        request.flatMap(_.rawHeader("authorization")) == Some("DPoP at-1"),
        request.flatMap(_.rawHeader("DPoP")).isDefined,
      )
    },
    // Checked with edge's own verifier rather than by reading claims back, and with the `htu`
    // edge reconstructs from its configured `edgeUrl` plus the request path.
    test("carries a proof bound to this request and this token") {
      for
        seen <- Ref.make(Option.empty[Request])
        result <- dpopCall(accounts, Response.json("{}"), seen)
        (key, _, request) = result
        now <- zio.Clock.instant
        proof <- versola.util.Dpop
          .verify(
            request.flatMap(_.rawHeader("DPoP")).getOrElse(""),
            versola.util.Dpop.Algorithm.Default,
            Method.GET,
            StubSut.edgeUrl + "/resources/core/accounts",
            now,
            zio.Duration.fromSeconds(30),
          )
          .mapError(error => RuntimeException(error.toString))
      yield assertTrue(
        proof.jkt == key.jkt,
        // §7 makes `ath` mandatory on a resource call; edge refuses a proof without it.
        proof.ath.contains(versola.util.Dpop.ath("at-1")),
      )
    },
    // The proof is minted per call, so a DPoP session has no cookie and nothing to adopt -- the
    // same as the bearer path, and asserted because the `rotatedSession` match had to grow a case.
    test("rotates no session") {
      for
        seen <- Ref.make(Option.empty[Request])
        result <- dpopCall(accounts, Response.json("{}").addCookie(Cookie.Response("EDGE_SESSION", "rotated")), seen)
        (_, outcome, _) = result
      yield assertTrue(outcome.exists(_.rotatedSession.isEmpty))
    },
    // `htm` and `htu` are covered by the signature, so a proof reused across two calls is refused
    // by a correct server. This is the driver-side half of that: every call signs its own.
    test("signs each call separately") {
      for
        seen <- Ref.make(Option.empty[Request])
        actions <- clientFor(Response.json("{}"), seen)
        key <- DpopKeyPool.derive("edge-action-spec", 1).map(_.keyFor(0L))
        credential = EdgeCredential.Dpop(AccessToken("at-1"), key)
        _ <- actions.call(credential, accounts)
        first <- seen.get.map(_.flatMap(_.rawHeader("DPoP")))
        _ <- actions.call(credential, ActionCall(Method.POST, "/resources/pay/transfers", Some("{}")))
        second <- seen.get.map(_.flatMap(_.rawHeader("DPoP")))
      yield assertTrue(first.isDefined, second.isDefined, first != second)
    },
  )

  def spec = suite("EdgeActionClient")(
    dpopSuite,
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
        outcome.rotatedSession == Some(EdgeCookie(EdgeSession("rotated"), None)),
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
