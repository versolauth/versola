package versola.oauth.logout

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.conversation.ConversationRenderService
import versola.oauth.model.SessionCookie
import versola.oauth.session.model.{PublicSessionId, SessionId}
import versola.util.UnitSpecBase
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.prelude.{Equal, EqualOps}
import zio.test.*

object LogoutControllerSpec extends UnitSpecBase:

  private given Equal[URL] = Equal.default

  private val rawSessionId = SessionId(Array.fill(32)(9.toByte))
  private val publicSessionId = PublicSessionId("public-session-1")
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val logoutUri = URL.decode("https://rp.example/logout").toOption.get

  private val logoutResult = LogoutService.LogoutResult(
    logoutUris = List(logoutUri),
    postLogoutRedirectUri = Some(redirectUri),
    state = Some("st-1"),
  )

  private def sessionCookieHeader(id: SessionId = rawSessionId): Header.Cookie =
    Header.Cookie(
      NonEmptyChunk(
        Cookie.Request(
          SessionCookie.name,
          SessionCookie(id, 1.hour, TestEnvConfig.coreConfig.security.sessionCookieSecret).content,
        ),
      ),
    )

  private val malformedSessionCookieHeader: Header.Cookie =
    Header.Cookie(NonEmptyChunk(Cookie.Request(SessionCookie.name, "not-a-valid-cookie-payload")))

  private def idTokenHint(sid: String = publicSessionId, sub: String = "user-1"): String =
    val header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key-id").`type`(JOSEObjectType.JWT).build()
    val claims = new JWTClaimsSet.Builder()
      .subject(sub)
      .claim("sid", sid)
      .issuer(TestEnvConfig.coreConfig.jwt.issuer)
      .build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(TestEnvConfig.privateKey))
    jwt.serialize()

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: (Stub[LogoutService], Stub[ConversationRenderService]) => UIO[Unit] = (_, _) => ZIO.unit,
      verify: (Response, Stub[LogoutService], Stub[ConversationRenderService]) => Task[TestResult] =
        (_, _, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        logoutService = stub[LogoutService]
        renderService = stub[ConversationRenderService]
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            LogoutController.routes
              .provideEnvironment(
                ZEnvironment(logoutService) ++
                  ZEnvironment(renderService) ++
                  ZEnvironment(TestEnvConfig.coreConfig) ++
                  ZEnvironment(TestEnvConfig.jwksService) ++
                  tracing
              )
          )
        )
        _ <- setup(logoutService, renderService)

        response <- client.batched(request)
        verifyResult <- verify(response, logoutService, renderService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("LogoutController")(
    controllerTestCase(
      description = "GET /logout resolves identifier from session cookie and clears it",
      request = Request.get(URL.root / "logout").addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, renderService) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Right(rawSessionId), None, None)),
          renderService.renderLogout.calls === List((logoutResult.logoutUris, logoutResult.postLogoutRedirectUri, logoutResult.state)),
          response.headers.get(Header.SetCookie).exists { h =>
            h.renderedValue.contains(SessionCookie.name) && h.renderedValue.contains("Max-Age=0")
          },
        )),
    ),
    controllerTestCase(
      description = "POST /logout also resolves identifier from session cookie",
      request = Request.post(URL.root / "logout", Body.empty).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls === List((Right(rawSessionId), None, None)))),
    ),
    controllerTestCase(
      description = "resolves identifier from id_token_hint when no session cookie is present",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint())),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls === List((Left(publicSessionId), None, None)))),
    ),
    controllerTestCase(
      description = "session cookie takes precedence over id_token_hint when both are present",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint(sid = "other-sid")))
        .addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls === List((Right(rawSessionId), None, None)))),
    ),
    controllerTestCase(
      description = "returns 200 OK without calling LogoutService or clearing the cookie when no identifier is present",
      request = Request.get(URL.root / "logout"),
      expectedStatus = Status.Ok,
      setup = (_, renderService) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, renderService) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          renderService.renderLogout.calls == List((Nil, None, None)),
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "falls back to id_token_hint when the session cookie is present but malformed",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint()))
        .addHeader(malformedSessionCookieHeader),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls === List((Left(publicSessionId), None, None)))),
    ),
    controllerTestCase(
      description = "treats a malformed id_token_hint as no identifier",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", "not-a-jwt")),
      expectedStatus = Status.Ok,
      setup = (_, renderService) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "forwards post_logout_redirect_uri and state query params to LogoutService",
      request = Request.get(
        (URL.root / "logout")
          .addQueryParam("post_logout_redirect_uri", redirectUri.encode)
          .addQueryParam("state", "st-9"),
      ).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Right(rawSessionId), Some(redirectUri), Some("st-9"))),
        )),
    ),
  )
