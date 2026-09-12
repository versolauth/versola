package versola.loadgen.protocol

import versola.loadgen.config.TargetsConfig
import zio.http.*
import zio.test.*
import zio.ZIO

object HttpAuthClientSpec extends ZIOSpecDefault:

  private val targets = TargetsConfig(StubSut.authUrl, StubSut.edgeUrl, "http://central.test", "http://mock.test", StubSut.origin)

  private def clientFor(routes: Routes[Any, Nothing]): ZIO[TestClient & Client, ProtocolError, AuthClient] =
    for
      _ <- TestClient.addRoutes(routes)
      client <- ZIO.service[Client]
      auth <- HttpAuthClient.make(client, targets, StubSut.registry, StubSut.requestTimeout)
    yield auth

  private def queryParam(params: QueryParams, name: String): Option[String] = params.getAll(name).headOption

  def spec = suite("HttpAuthClient")(
    test("authorize sends PKCE, state and the provisioned redirect URI, and keeps the conversation cookie") {
      for
        stub <- StubSut.make(List("credential"))
        (recorder, routes) = stub
        auth <- clientFor(routes)
        started <- auth.authorize("openid phone", None, Some(List(Acr.OtpLevel)), None)
        query <- recorder.seen.get.map(_.head.url.queryParams)
      yield assertTrue(
        started.conversation == ConversationCookie(StubSut.conversation),
        started.state.length == 32,
        queryParam(query, "response_type") == Some("code"),
        queryParam(query, "client_id") == Some("mobile-otp"),
        queryParam(query, "redirect_uri") == Some(StubSut.redirectUri),
        queryParam(query, "scope") == Some("openid phone"),
        queryParam(query, "code_challenge_method") == Some("S256"),
        queryParam(query, "acr_values") == Some(Acr.OtpLevel),
        queryParam(query, "state") == Some(started.state),
      )
    },
    test("authorize carries an existing SSO session when one is passed, for a step-up") {
      for
        stub <- StubSut.make(List("credential"))
        (recorder, routes) = stub
        auth <- clientFor(routes)
        _ <- auth.authorize("openid", None, None, Some(SsoSession(StubSut.ssoSession)))
        cookie <- recorder.headerOf("/authorize", "cookie")
      yield assertTrue(cookie == Some("SSO_SESSION=" + StubSut.ssoSession))
    },
    test("an unprovisioned client_id is a typed configuration failure, not a thrown exception") {
      for
        stub <- StubSut.make(List("credential"))
        (_, routes) = stub
        auth <- clientFor(routes)
        failure <- auth.authorize("openid", Some("never-registered"), None, None).either
      yield assertTrue(failure == Left(ProtocolError.Misconfigured("no provisioned client with client_id=never-registered")))
    },
    test("challenge parses the step and the csrf token off the rendered page") {
      for
        stub <- StubSut.make(List("otp"))
        (recorder, routes) = stub
        auth <- clientFor(routes)
        page <- auth.challenge(ConversationCookie(StubSut.conversation))
        cookie <- recorder.headerOf("/challenge", "cookie")
      yield assertTrue(
        page.step == Some(ConversationStep.Otp),
        page.csrf == Some(Csrf(StubSut.csrf)),
        cookie == Some("SSO_CONVERSATION=" + StubSut.conversation),
      )
    },
    test("a submit carries the conversation cookie and the csrf token, and reports the redirect") {
      for
        stub <- StubSut.make(List("otp"))
        (recorder, routes) = stub
        auth <- clientFor(routes)
        outcome <- auth.submitOtp(ConversationCookie(StubSut.conversation), "123456", Csrf(StubSut.csrf))
        form <- recorder.formOf("/challenge/otp")
        cookie <- recorder.headerOf("/challenge/otp", "cookie")
        contentType <- recorder.headerOf("/challenge/otp", "content-type")
        redirected = outcome match
          case SubmitOutcome.Redirected(location, ssoSession) => (HttpExchange.redirectParam(location, "code"), ssoSession)
          case SubmitOutcome.Rendered(_) => (None, None)
      yield assertTrue(
        form == Some(Map("code" -> "123456", "csrf" -> StubSut.csrf)),
        cookie == Some("SSO_CONVERSATION=" + StubSut.conversation),
        contentType == Some("application/x-www-form-urlencoded"),
        redirected == (Some(StubSut.code), Some(SsoSession(StubSut.ssoSession))),
      )
    },
    test("a submit answered with a page reports the next step inline instead of a redirect") {
      for
        stub <- StubSut.make(List("credential", "otp"))
        (_, routes) = stub
        auth <- clientFor(
          routes.transform(_ => handler((_: Request) => ZIO.succeed(Response.text(StubSut.page("otp"))))),
        )
        outcome <- auth.submitPhone(ConversationCookie(StubSut.conversation), "+70000000000", Csrf(StubSut.csrf))
        rendered = outcome match
          case SubmitOutcome.Rendered(page) => page.step
          case SubmitOutcome.Redirected(_, _) => None
      yield assertTrue(rendered == Some(ConversationStep.Otp))
    },
    test("a public client names itself in the body; a confidential one sends HTTP Basic") {
      for
        stub <- StubSut.make(List("otp"))
        (recorder, routes) = stub
        auth <- clientFor(routes)
        _ <- auth.exchangeCode(AuthCode(StubSut.code), CodeVerifier("verifier"), StubSut.publicClient.creds)
        publicForm <- recorder.formOf("/token")
        publicAuthorization <- recorder.headerOf("/token", "authorization")
        tokens <- auth.exchangeCode(AuthCode(StubSut.code), CodeVerifier("verifier"), StubSut.confidentialClient.creds)
        confidentialForm <- recorder.formOf("/token")
        confidentialAuthorization <- recorder.headerOf("/token", "authorization")
      yield assertTrue(
        publicForm.exists(_.get("client_id") == Some("mobile-otp")),
        publicForm.exists(_.get("grant_type") == Some("authorization_code")),
        publicForm.exists(_.get("code_verifier") == Some("verifier")),
        publicForm.exists(_.get("redirect_uri") == Some(StubSut.redirectUri)),
        publicAuthorization == None,
        confidentialForm.exists(_.get("client_id") == None),
        confidentialAuthorization.exists(_.startsWith("Basic ")),
        tokens == Tokens(AccessToken("at-1"), Some(RefreshToken("rt-1")), Some(IdToken("it-1")), 900L),
      )
    },
    test("a refused refresh is a RefreshRejected carrying what the wire actually said") {
      for
        stub <- StubSut.make(List("otp"))
        (_, routes) = stub
        auth <- clientFor(
          routes.transform(_ =>
            handler((_: Request) => ZIO.succeed(Response.json("""{"error":"invalid_grant","error_description":"..."}""").status(Status.BadRequest))),
          ),
        )
        failure <- auth.exchangeRefresh(RefreshToken("rt-1"), StubSut.publicClient.creds).either
      yield assertTrue(failure == Left(ProtocolError.RefreshRejected(RefreshRejection.Unknown("invalid_grant"))))
    },
    test("a token body that is not the expected shape is a MalformedResponse, not a decoding defect") {
      for
        stub <- StubSut.make(List("otp"))
        (_, routes) = stub
        auth <- clientFor(routes.transform(_ => handler((_: Request) => ZIO.succeed(Response.json("""{"nope":true}""")))))
        failure <- auth.exchangeCode(AuthCode(StubSut.code), CodeVerifier("v"), StubSut.publicClient.creds).either
        malformed = failure.left.toOption.exists:
          case ProtocolError.MalformedResponse("/token", _) => true
          case _ => false
      yield assertTrue(malformed)
    },
    test("an unexpected status names the endpoint and what was expected") {
      for
        stub <- StubSut.make(List("otp"))
        (_, routes) = stub
        auth <- clientFor(routes.transform(_ => handler((_: Request) => ZIO.succeed(Response.status(Status.InternalServerError)))))
        failure <- auth.challenge(ConversationCookie(StubSut.conversation)).either
      yield assertTrue(
        failure == Left(ProtocolError.UnexpectedStatus(Set(Status.Ok), Status.InternalServerError, "/challenge")),
      )
    },
  ).provide(TestClient.layer)
