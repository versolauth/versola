package versola.oauth.logout

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.oauth.client.model.ClientId
import versola.oauth.conversation.ConversationRenderService
import versola.oauth.model.SessionCookie
import versola.oauth.session.SessionService
import versola.oauth.session.model.{ClientEntry, PublicSessionId, SessionId, SessionInfo, SessionRecord, UserAgentInfo}
import versola.user.model.UserId
import versola.util.{Base64, MAC}
import versola.util.UnitSpecBase
import versola.util.http.{NoopTracing, Observability}
import zio.*
import zio.http.*
import zio.prelude.{Equal, EqualOps}
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object LogoutControllerSpec extends UnitSpecBase:

  private given Equal[URL] = Equal.default

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val clientId1 = ClientId("client-1")
  private val rawSessionId = SessionId(Array.fill(32)(9.toByte))
  private val publicSessionId = PublicSessionId("public-session-1")
  private val sessionMac = MAC(Array.fill(32)(1.toByte))
  private val redirectUri = URL.decode("https://example.com/callback").toOption.get
  private val logoutUri = URL.decode("https://rp.example/logout").toOption.get

  private val sessionRecord = SessionRecord(
    userId = userId,
    clients = List(ClientEntry(clientId1, Instant.EPOCH)),
    userAgent = UserAgentInfo("desktop", None, None, None),
    createdAt = Instant.EPOCH,
    amr = Map.empty,
    publicId = publicSessionId,
  )
  private val sessionInfo = SessionInfo(sessionMac, sessionRecord)

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
      .audience("test-client")
      .jwtID(UUID.randomUUID().toString)
      .expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
      .build()
    val jwt = new SignedJWT(header, claims)
    jwt.sign(new RSASSASigner(TestEnvConfig.privateKey))
    jwt.serialize()

  /** Mirrors `LogoutController.csrfToken`, so tests can produce a token that the
   *  controller under test will accept without exposing its internals. */
  private def csrfToken(
      id: SessionId,
      redirectUri: Option[String] = None,
      state: Option[String] = None,
  ): String =
    val canonical = List("logout-confirm", Base64.urlEncode(id), redirectUri.getOrElse(""), state.getOrElse(""))
      .map(part => s"${part.length}:$part")
      .mkString
    val mac = Array.ofDim[Byte](32)
    org.apache.commons.codec.digest.Blake3
      .initKeyedHash(TestEnvConfig.coreConfig.security.sessionCookieSecret)
      .update(canonical.getBytes(StandardCharsets.UTF_8))
      .doFinalize(mac)
    Base64.urlEncode(mac)

  private def postWithCsrf(csrfTokenValue: String): Request =
    Request.post(URL.root / "logout", Body.fromURLEncodedForm(Form.fromStrings("csrf_token" -> csrfTokenValue)))
      .addHeader(sessionCookieHeader())

  def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: (Stub[LogoutService], Stub[ConversationRenderService], Stub[SessionService]) => UIO[Unit] = (_, _, _) => ZIO.unit,
      verify: (Response, Stub[LogoutService], Stub[ConversationRenderService], Stub[SessionService]) => Task[TestResult] =
        (_, _, _, _) => ZIO.succeed(assertTrue(true)),
  ) =
    test(description) {
      for
        client <- ZIO.service[Client]
        logoutService = stub[LogoutService]
        renderService = stub[ConversationRenderService]
        sessionService = stub[SessionService]
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            LogoutController.routes
              .provideEnvironment(
                ZEnvironment(logoutService) ++
                  ZEnvironment(renderService) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(TestEnvConfig.coreConfig) ++
                  ZEnvironment(TestEnvConfig.jwksService) ++
                  tracing
              )
          )
        )
        _ <- setup(logoutService, renderService, sessionService)

        response <- client.batched(request)
        verifyResult <- verify(response, logoutService, renderService, sessionService)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("LogoutController")(
    controllerTestCase(
      description = "GET /logout with only a session cookie renders the confirmation page instead of logging out",
      request = Request.get(URL.root / "logout").addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, sessionService) =>
        sessionService.find.succeedsWith(Some(sessionInfo)) *>
          renderService.renderLogoutConfirm.succeedsWith(Response.text("<html>confirm</html>")),
      verify = (response, logoutService, renderService, sessionService) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          sessionService.find.calls === List(rawSessionId),
          renderService.renderLogoutConfirm.calls == List((sessionInfo, csrfToken(rawSessionId), None, None, None)),
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "GET /logout with a session cookie for an unknown session renders the generic logout page",
      request = Request.get(URL.root / "logout").addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, sessionService) =>
        sessionService.find.succeedsWith(None) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, renderService, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          renderService.renderLogout.calls === List((Nil, None, None)),
        )),
    ),
    controllerTestCase(
      description = "POST /logout with a valid csrf_token logs out the session and clears the cookie",
      request = postWithCsrf(csrfToken(rawSessionId)),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, _) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Right(rawSessionId), None, None)),
          response.headers.get(Header.SetCookie).exists { h =>
            h.renderedValue.contains(SessionCookie.name) && h.renderedValue.contains("Max-Age=0")
          },
        )),
    ),
    controllerTestCase(
      description = "POST /logout without a csrf_token does not log out or clear the cookie",
      request = Request.post(URL.root / "logout", Body.empty).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "POST /logout with an invalid csrf_token does not log out or clear the cookie",
      request = postWithCsrf("not-the-right-token"),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "resolves identifier from id_token_hint when no session cookie is present",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint())),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, _) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls === List((Left(publicSessionId), None, None)))),
    ),
    controllerTestCase(
      description = "GET /logout with a session cookie and a matching id_token_hint logs out immediately",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint(sid = publicSessionId)))
        .addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, sessionService) =>
        sessionService.find.succeedsWith(Some(sessionInfo)) *>
          logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, sessionService) =>
        ZIO.succeed(assertTrue(
          sessionService.find.calls === List(rawSessionId),
          logoutService.logout.calls === List((Right(rawSessionId), None, None)),
        )),
    ),
    controllerTestCase(
      description = "GET /logout with a session cookie and a mismatched id_token_hint does not log out immediately",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint(sid = "other-sid")))
        .addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, sessionService) =>
        sessionService.find.succeedsWith(Some(sessionInfo)) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, renderService, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          renderService.renderLogout.calls === List((Nil, None, None)),
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "returns 200 OK without calling LogoutService or clearing the cookie when no identifier is present",
      request = Request.get(URL.root / "logout"),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, renderService, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          renderService.renderLogout.calls == List((Nil, None, None)),
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "returns 200 OK without calling LogoutService and drops the redirect when no identifier is present",
      request = Request.get((URL.root / "logout").addQueryParam("post_logout_redirect_uri", redirectUri.encode)),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, renderService, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          renderService.renderLogout.calls == List((Nil, None, None)),
        )),
    ),
    controllerTestCase(
      description = "falls back to id_token_hint when the session cookie is present but malformed",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", idTokenHint()))
        .addHeader(malformedSessionCookieHeader),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, _) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, sessionService) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Left(publicSessionId), None, None)),
          sessionService.find.calls.isEmpty,
        )),
    ),
    controllerTestCase(
      description = "treats a malformed id_token_hint as no identifier",
      request = Request.get((URL.root / "logout").addQueryParam("id_token_hint", "not-a-jwt")),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, _) =>
        ZIO.succeed(assertTrue(logoutService.logout.calls.isEmpty)),
    ),
    controllerTestCase(
      description = "GET /logout binds post_logout_redirect_uri and state into the confirmation token",
      request = Request.get(
        (URL.root / "logout")
          .addQueryParam("post_logout_redirect_uri", redirectUri.encode)
          .addQueryParam("state", "st-9"),
      ).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, sessionService) =>
        sessionService.find.succeedsWith(Some(sessionInfo)) *>
          renderService.renderLogoutConfirm.succeedsWith(Response.text("<html>confirm</html>")),
      verify = (_, _, renderService, _) =>
        ZIO.succeed(assertTrue(
          renderService.renderLogoutConfirm.calls == List((
            sessionInfo,
            csrfToken(rawSessionId, Some(redirectUri.encode), Some("st-9")),
            Some(redirectUri.encode),
            Some("st-9"),
            None,
          )),
        )),
    ),
    controllerTestCase(
      description = "forwards post_logout_redirect_uri and state form fields to LogoutService after confirmation",
      request = Request.post(
        URL.root / "logout",
        Body.fromURLEncodedForm(Form.fromStrings(
          "csrf_token" -> csrfToken(rawSessionId, Some(redirectUri.encode), Some("st-9")),
          "post_logout_redirect_uri" -> redirectUri.encode,
          "state" -> "st-9",
        )),
      ).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, _) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Right(rawSessionId), Some(redirectUri), Some("st-9"))),
        )),
    ),
    controllerTestCase(
      description = "POST /logout rejects a token bound to a different post_logout_redirect_uri",
      request = Request.post(
        URL.root / "logout",
        Body.fromURLEncodedForm(Form.fromStrings(
          "csrf_token" -> csrfToken(rawSessionId, Some(redirectUri.encode), Some("st-9")),
          "post_logout_redirect_uri" -> "https://attacker.example/callback",
          "state" -> "st-9",
        )),
      ).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (_, renderService, _) => renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (response, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls.isEmpty,
          response.headers.get(Header.SetCookie).isEmpty,
        )),
    ),
    controllerTestCase(
      description = "POST /logout ignores post_logout_redirect_uri passed only as a query param",
      request = Request.post(
        (URL.root / "logout").addQueryParam("post_logout_redirect_uri", redirectUri.encode),
        Body.fromURLEncodedForm(Form.fromStrings("csrf_token" -> csrfToken(rawSessionId))),
      ).addHeader(sessionCookieHeader()),
      expectedStatus = Status.Ok,
      setup = (logoutService, renderService, _) =>
        logoutService.logout.succeedsWith(logoutResult) *>
          renderService.renderLogout.succeedsWith(Response.text("<html>logout</html>")),
      verify = (_, logoutService, _, _) =>
        ZIO.succeed(assertTrue(
          logoutService.logout.calls === List((Right(rawSessionId), None, None)),
        )),
    ),
  )
