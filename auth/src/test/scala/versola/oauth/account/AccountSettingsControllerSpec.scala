package versola.oauth.account

import org.scalamock.stubs.Stub
import versola.auth.TestEnvConfig
import versola.auth.model.{CredentialId, PasskeyName, PasskeyRecord}
import versola.oauth.challenge.passkey.{PasskeyCeremony, PasskeyRepository, WebAuthnError, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, PasskeySettings}
import versola.oauth.conversation.ConversationRenderService
import versola.oauth.session.SessionService
import versola.oauth.session.model.{PublicSessionId, SessionUnderUserAgent}
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.http.{NoopTracing, Observability}
import versola.util.{Base64, Secret, UnitSpecBase}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

object AccountSettingsControllerSpec extends UnitSpecBase:

  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val otherUserId = UserId(UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001"))
  private val clientId = ClientId("test-client-1")

  private val ownSessionId = PublicSessionId("session-of-the-caller")
  private val foreignSessionId = PublicSessionId("session-of-someone-else")

  private val ownSession = SessionUnderUserAgent(
    publicId = ownSessionId,
    clients = Nil,
    createdAt = Instant.EPOCH,
    platform = Some("desktop"),
    os = Some("Linux"),
    browser = Some("Firefox"),
    version = Some("140"),
    expiresAt = Instant.EPOCH,
  )

  private val otherDeviceSession = ownSession.copy(publicId = PublicSessionId("session-on-another-device"))

  private val passkeyId = CredentialId(Array.fill(16)(3.toByte))

  private val passkey = PasskeyRecord(
    id = passkeyId,
    userId = userId,
    publicKey = Array.fill(16)(7.toByte),
    signatureCounter = 0,
    deviceType = versola.auth.model.CredentialDeviceType.MultiDevice,
    backedUp = true,
    backupEligible = true,
    transports = Nil,
    attestationObject = None,
    clientDataJson = None,
    aaguid = None,
    name = Some(PasskeyName("My Key")),
    lastUsedAt = None,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

  /** The plaintext secret [[OAuthConfigurationService]] is stubbed to return for auth's own
    * resource, as if it had been decrypted from central's registry sync. */
  private val accountResourceSecret = Secret(Array.fill(32)(5.toByte))

  /** The secret being rotated out, which central keeps reporting alongside the current one
    * until an administrator removes it. */
  private val previousAccountResourceSecret = Secret(Array.fill(32)(6.toByte))

  private val passkeySettings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:8080"),
    userVerification = "preferred",
  )

  private def basic(
      resourceId: String = "auth",
      secret: Secret = accountResourceSecret,
  ): Header.Authorization = Header.Authorization.Basic(resourceId, Base64.urlEncode(secret))

  /** Mirrors how edge injects the caller identity: query parameters for GET, merged into
    * the JSON body for mutations - matching what the controller now reads per endpoint. */
  private def withCaller(
      request: Request,
      callerUserId: UserId,
      callerSessionId: PublicSessionId,
      callerSecret: Secret,
  ): Task[Request] =
    val authenticated = request.removeHeader(Header.Authorization).addHeader(basic(secret = callerSecret))
    if authenticated.method == Method.GET then
      ZIO.succeed(authenticated.copy(url = authenticated.url
        .removeQueryParam("userId")
        .removeQueryParam("clientId")
        .removeQueryParam("sessionId")
        .addQueryParam("userId", callerUserId.toString)
        .addQueryParam("clientId", clientId.toString)
        .addQueryParam("sessionId", callerSessionId.toString)))
    else
      for
        bodyString <- authenticated.body.asString
        base = bodyString.fromJson[Json.Obj].getOrElse(Json.Obj(Chunk.empty))
        callerFields = Chunk(
          "userId" -> Json.Str(callerUserId.toString),
          "clientId" -> Json.Str(clientId.toString),
          "sessionId" -> Json.Str(callerSessionId.toString),
        )
      yield authenticated.copy(body = Body.fromString(Json.Obj(base.fields ++ callerFields).toJson))

  /** Expiry is relative to the test clock, which starts at the epoch. */
  private def ticketFor(user: UserId, expiresAt: Instant = Instant.EPOCH.plusSeconds(300)): String =
    PasskeyEnrollmentTicket.serialize(
      PasskeyEnrollmentTicket(user, expiresAt, """{"challenge":"x"}"""),
      TestEnvConfig.coreConfig.security.conversationCookieSecret,
    )

  private type Stubs = (
      Stub[OAuthConfigurationService],
      Stub[SessionService],
      Stub[PasskeyRepository],
      Stub[WebAuthnService],
      Stub[UserRepository],
      Stub[ConversationRenderService],
  )

  private def controllerTestCase(
      description: String,
      request: Request,
      expectedStatus: Status,
      setup: Stubs => UIO[Unit] = _ => ZIO.unit,
      verify: (Response, Stubs) => Task[TestResult] = (_, _) => ZIO.succeed(assertTrue(true)),
      authenticate: Boolean = true,
      callerSessionId: PublicSessionId = ownSessionId,
      resourceSecrets: List[Secret] = List(accountResourceSecret),
      callerSecret: Secret = accountResourceSecret,
  ) =
    test(description) {
      for
        httpClient <- ZIO.service[Client]
        configuration = stub[OAuthConfigurationService]
        sessionService = stub[SessionService]
        passkeyRepository = stub[PasskeyRepository]
        webAuthnService = stub[WebAuthnService]
        userRepository = stub[UserRepository]
        renderService = stub[ConversationRenderService]
        stubs = (configuration, sessionService, passkeyRepository, webAuthnService, userRepository, renderService)
        tracing <- NoopTracing.layer.build

        _ <- TestClient.addRoutes(
          Observability.handleErrors(
            AccountSettingsController.routes
              .provideEnvironment(
                ZEnvironment(configuration) ++
                  ZEnvironment(sessionService) ++
                  ZEnvironment(passkeyRepository) ++
                  ZEnvironment(webAuthnService) ++
                  ZEnvironment(userRepository) ++
                  ZEnvironment(renderService) ++
                  ZEnvironment(TestEnvConfig.coreConfig) ++
                  tracing,
              ),
          ),
        )
        _ <- configuration.accountResourceSecrets.succeedsWith(resourceSecrets)
        _ <- setup(stubs)

        routedRequest <- if authenticate then withCaller(request, userId, callerSessionId, callerSecret) else ZIO.succeed(request)
        response <- httpClient.batched(routedRequest)
        verifyResult <- verify(response, stubs)
      yield assertTrue(response.status == expectedStatus) && verifyResult
    }.provideSomeLayer(TestClient.layer) @@ TestAspect.silentLogging

  val spec = suite("AccountSettingsController")(
    suite("internal resource authentication")(
      controllerTestCase(
        description = "rejects a request without resource credentials",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
      ),
      controllerTestCase(
        description = "rejects credentials for another resource",
        request = Request.get(URL.empty / "settings").addHeader(basic(resourceId = "other")),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
      ),
      // Edge switches to the new secret when the previous one is removed, and refreshes its
      // cache independently of auth, so both halves of a rotation have to be accepted.
      controllerTestCase(
        description = "accepts either secret while the resource secret is being rotated",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Ok,
        resourceSecrets = List(accountResourceSecret, previousAccountResourceSecret),
        callerSecret = previousAccountResourceSecret,
        setup = (_, sessionService, passkeyRepository, _, _, renderService) =>
          sessionService.listByUser.succeedsWith(List(ownSession)) *>
            passkeyRepository.listByUser.succeedsWith(Vector(passkey)) *>
            renderService.renderAccount.succeedsWith(Response.text("<html>account</html>")),
      ),
      controllerTestCase(
        description = "rejects a secret central no longer reports",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Unauthorized,
        callerSecret = previousAccountResourceSecret,
      ),
      controllerTestCase(
        description = "rejects every request when no resource secret has synced yet",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Unauthorized,
        resourceSecrets = Nil,
      ),
      controllerTestCase(
        description = "rejects an invalid resource secret",
        request = Request.get(URL.empty / "settings").addHeader(basic(secret = Secret(Array.fill(32)(9.toByte)))),
        expectedStatus = Status.Unauthorized,
        authenticate = false,
      ),
      controllerTestCase(
        description = "rejects a request without edge-injected caller context",
        request = Request.get(URL.empty / "settings").addHeader(basic()),
        expectedStatus = Status.BadRequest,
        authenticate = false,
      ),
    ),
    suite("GET /settings")(
      controllerTestCase(
        description = "renders the page with the caller's own sessions and passkeys",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Ok,
        setup = (_, sessionService, passkeyRepository, _, _, renderService) =>
          sessionService.listByUser.succeedsWith(List(ownSession)) *>
            passkeyRepository.listByUser.succeedsWith(Vector(passkey)) *>
            renderService.renderAccount.succeedsWith(Response.text("<html>account</html>")),
        verify = (_, stubs) =>
          val (_, sessionService, passkeyRepository, _, _, renderService) = stubs
          ZIO.succeed(assertTrue(
            sessionService.listByUser.calls == List(userId),
            passkeyRepository.listByUser.calls == List(userId),
            renderService.renderAccount.calls.map(_._2.sessions.map(_.id)) == List(List(ownSessionId)),
          )),
      ),
      controllerTestCase(
        description = "marks the session the page is viewed from using edge's injected sid",
        request = Request.get(URL.empty / "settings"),
        expectedStatus = Status.Ok,
        setup = (_, sessionService, passkeyRepository, _, _, renderService) =>
          sessionService.listByUser.succeedsWith(List(ownSession, otherDeviceSession)) *>
            passkeyRepository.listByUser.succeedsWith(Vector.empty) *>
            renderService.renderAccount.succeedsWith(Response.text("<html>account</html>")),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(
            stubs._6.renderAccount.calls.map(_._2.sessions.map(session => (session.id, session.current))) ==
              List(List((ownSessionId, true), (otherDeviceSession.publicId, false))),
          )),
      ),
    ),
    suite("DELETE /settings/sessions")(
      controllerTestCase(
        description = "revokes another session the caller owns",
        request = Request(
          method = Method.DELETE,
          url = URL.empty / "settings" / "sessions",
          body = Body.fromString(s"""{"targetSessionId":"${otherDeviceSession.publicId}"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.NoContent,
        setup = (_, sessionService, _, _, _, _) =>
          sessionService.invalidateForUser.succeedsWith(true),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(
            stubs._2.invalidateForUser.calls == List((otherDeviceSession.publicId, userId)),
            stubs._2.listByUser.calls.isEmpty,
          )),
      ),
      // `sid` leaks into RP logs and front-channel logout URLs, so knowing one must not be
      // enough to end that session.
      controllerTestCase(
        description = "does not revoke a session that belongs to someone else",
        request = Request(
          method = Method.DELETE,
          url = URL.empty / "settings" / "sessions",
          body = Body.fromString(s"""{"targetSessionId":"$foreignSessionId"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.NoContent,
        setup = (_, sessionService, _, _, _, _) =>
          sessionService.invalidateForUser.succeedsWith(false),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(
            stubs._2.invalidateForUser.calls == List((foreignSessionId, userId)),
            stubs._2.listByUser.calls.isEmpty,
          )),
      ),
    ),
    suite("POST /settings/passkeys/register/start")(
      controllerTestCase(
        description = "hands back the ceremony and a signed ticket",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "start",
          Body.fromString("{}"),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.Ok,
        setup = (configuration, _, _, webAuthnService, userRepository, _) =>
          configuration.getPasskeySettings.succeedsWith(Some(passkeySettings)) *>
            userRepository.find.succeedsWith(None) *>
            webAuthnService.startRegistration.succeedsWith(PasskeyCeremony("req-state", """{"publicKey":{}}""")),
        verify = (response, stubs) =>
          for
            body <- response.body.asString
            obj = body.fromJson[Json.Obj].toOption.get
          yield assertTrue(
            stubs._4.startRegistration.calls.map((settings, user, _) => (settings, user)) ==
              List((passkeySettings, userId)),
            obj.fields.exists((key, value) => key == "publicKeyOptions" && value == Json.Str("""{"publicKey":{}}""")),
            obj.fields.exists((key, _) => key == "ticket"),
          ),
      ),
      controllerTestCase(
        description = "rejects enrollment when passkeys are not enabled for the client",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "start",
          Body.fromString("{}"),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.BadRequest,
        setup = (configuration, _, _, _, _, _) => configuration.getPasskeySettings.succeedsWith(None),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.startRegistration.calls.isEmpty)),
      ),
    ),
    suite("passkeys")(
      controllerTestCase(
        description = "scopes a rename to the caller",
        request = Request.patch(
          URL.empty / "settings" / "passkeys",
          Body.fromString(s"""{"credentialId":"${Base64.urlEncode(passkeyId)}","name":"Renamed"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.NoContent,
        setup = (_, _, passkeyRepository, _, _, _) => passkeyRepository.rename.succeedsWith(()),
        // CredentialId is a byte array, so the calls are compared by encoded value.
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(
            stubs._3.rename.calls.map((id, user, name) => (Base64.urlEncode(id), user, name)) ==
              List((Base64.urlEncode(passkeyId), userId, Some(PasskeyName("Renamed")))),
          )),
      ),
      controllerTestCase(
        description = "scopes a deletion to the caller",
        request = Request(
          method = Method.DELETE,
          url = URL.empty / "settings" / "passkeys",
          body = Body.fromString(s"""{"credentialId":"${Base64.urlEncode(passkeyId)}"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.NoContent,
        setup = (_, _, passkeyRepository, _, _, _) => passkeyRepository.deleteByUser.succeedsWith(()),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(
            stubs._3.deleteByUser.calls.map((id, user) => (Base64.urlEncode(id), user)) ==
              List((Base64.urlEncode(passkeyId), userId)),
          )),
      ),
      controllerTestCase(
        description = "completes enrollment and returns the created passkey",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(s"""{"ticket":"${ticketFor(userId)}","response":{},"name":"My device"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.Ok,
        setup = (_, _, _, webAuthnService, _, _) => webAuthnService.finishRegistration.succeedsWith(passkey),
        verify = (response, stubs) =>
          for
            body <- response.body.asString
            obj = body.fromJson[Json.Obj].toOption.get
          yield assertTrue(
            stubs._4.finishRegistration.calls.map((cid, uid, _, _, name) => (cid, uid, name)) ==
              List((clientId, userId, Some(PasskeyName("My device")))),
            obj.fields.exists((key, value) => key == "id" && value == Json.Str(Base64.urlEncode(passkeyId))),
          ),
      ),
      controllerTestCase(
        description = "maps a failed enrollment ceremony to BadRequest",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(s"""{"ticket":"${ticketFor(userId)}","response":{},"name":"My device"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.BadRequest,
        setup = (_, _, _, webAuthnService, _, _) =>
          webAuthnService.finishRegistration.failsWith(WebAuthnError.CeremonyFailed("verification failed")),
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.finishRegistration.calls.nonEmpty)),
      ),
      controllerTestCase(
        description = "requires a non-empty passkey name for enrollment",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(s"""{"ticket":"${ticketFor(userId)}","response":{},"name":"   "}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.BadRequest,
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.finishRegistration.calls.isEmpty)),
      ),
      controllerTestCase(
        description = "rejects an enrollment ticket that was issued to another user",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(s"""{"ticket":"${ticketFor(otherUserId)}","response":{},"name":"Other device"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.Unauthorized,
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.finishRegistration.calls.isEmpty)),
      ),
      controllerTestCase(
        description = "rejects a tampered enrollment ticket",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(s"""{"ticket":"${ticketFor(userId).dropRight(4)}AAAA","response":{},"name":"Tampered device"}"""),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.BadRequest,
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.finishRegistration.calls.isEmpty)),
      ),
      controllerTestCase(
        description = "rejects an expired enrollment ticket",
        request = Request.post(
          URL.empty / "settings" / "passkeys" / "register" / "finish",
          Body.fromString(
            s"""{"ticket":"${ticketFor(userId, Instant.EPOCH.minusSeconds(1))}","response":{},"name":"Expired device"}""",
          ),
        ).addHeader(Header.ContentType(MediaType.application.json)),
        expectedStatus = Status.BadRequest,
        verify = (_, stubs) =>
          ZIO.succeed(assertTrue(stubs._4.finishRegistration.calls.isEmpty)),
      ),
    ),
    suite("PasskeyEnrollmentTicket")(
      test("round-trips a ceremony") {
        val ticket = PasskeyEnrollmentTicket(userId, Instant.EPOCH.plusSeconds(600), """{"challenge":"abc"}""")
        val secret = TestEnvConfig.coreConfig.security.conversationCookieSecret
        val parsed = PasskeyEnrollmentTicket.parse(
          PasskeyEnrollmentTicket.serialize(ticket, secret),
          secret,
          Instant.EPOCH,
        )
        assertTrue(parsed == Right(ticket))
      },
      test("rejects a ticket signed with a different key") {
        val ticket = PasskeyEnrollmentTicket(userId, Instant.EPOCH.plusSeconds(600), "{}")
        val serialized = PasskeyEnrollmentTicket.serialize(
          ticket,
          versola.util.Secret.Bytes32(Array.fill(32)(1.toByte)),
        )
        val parsed = PasskeyEnrollmentTicket.parse(
          serialized,
          TestEnvConfig.coreConfig.security.conversationCookieSecret,
          Instant.EPOCH,
        )
        assertTrue(parsed == Left("invalid signature"))
      },
      test("rejects a ticket whose payload was edited") {
        val secret = TestEnvConfig.coreConfig.security.conversationCookieSecret
        val serialized = PasskeyEnrollmentTicket.serialize(
          PasskeyEnrollmentTicket(userId, Instant.EPOCH.plusSeconds(600), "{}"),
          secret,
        )
        val forged = PasskeyEnrollmentTicket.serialize(
          PasskeyEnrollmentTicket(otherUserId, Instant.EPOCH.plusSeconds(600), "{}"),
          secret,
        )
        // Payload of one ticket, signature of the other.
        val spliced = s"${forged.takeWhile(_ != '.')}.${serialized.dropWhile(_ != '.').drop(1)}"
        assertTrue(PasskeyEnrollmentTicket.parse(spliced, secret, Instant.EPOCH) == Left("invalid signature"))
      },
    ),
  )
