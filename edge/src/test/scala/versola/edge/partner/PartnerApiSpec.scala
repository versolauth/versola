package versola.edge.partner

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.edge.model.{AccessToken, AccessTokenClaims, AccessTokenId, ClientId, EdgeId, PresetId, TenantId}
import versola.edge.partner.PartnerCentral.{Answer, Failure}
import versola.edge.{EdgeConfig, EdgeService, SSOClient}
import versola.util.Secret
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator

/** `/partner/api`, on the real routes with the real team over a repository in memory: who is let in, at what level, and that a
  * call for the console is a call to central with the staff member's token, never something the edge does itself. */
object PartnerApiSpec extends ZIOSpecDefault, ZIOStubs:

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    generator.generateKeyPair()

  private val config = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "edge-key",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(1.toByte))),
      EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(2.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(URL.decode("https://central.example").toOption.get),
    versolaUrl = URL.decode("https://idp.example").toOption.get,
    edgeUrl = URL.decode("https://login.acme.example").toOption.get,
    configurationCacheRefreshInterval = 5.minutes,
  )

  private val me = PartnerMe(
    partner = PartnerMe.Partner("acme", "Acme Pay", None, "active", Json.Obj("maxClients" -> Json.Num(5)), None, "t"),
    operator = PartnerMe.Operator("Bank", Some("https://idp.example"), None, None),
    console = PartnerMe.Console("acme-console", "acme-console"),
    edge = PartnerMe.Edge("edge-1", Some("https://login.acme.example")),
  )

  private val identifiers = Identifiers.Impl(Array.fill[Byte](32)(7))
  private val phone = "+77011234511"

  /** A central that answers what the test says and remembers what it was asked. */
  private class FakeCentral(answer: (Method, List[String]) => IO[Failure, Answer]) extends PartnerCentral:
    val calls: Ref[List[(Method, List[String], Map[String, String], Option[Json], Option[String])]] =
      Unsafe.unsafe(unsafe => Ref.unsafe.make(List.empty[(Method, List[String], Map[String, String], Option[Json], Option[String])])(using unsafe))
    override def call(method: Method, path: List[String], query: Map[String, String], body: Option[Json], principal: Option[String]) =
      calls.update(_ :+ ((method, path, query, body, principal))) *> answer(method, path)

  private def reply(status: Status, body: String): IO[Failure, Answer] = ZIO.succeed(Answer(status, body))

  private val clientJson =
    """{"id":"acme-shop","kind":"web","clientName":{"en":"Acme Shop"},"logoUri":null,"policyUri":null,"tosUri":null,
      |"redirectUris":["https://shop.acme.example/cb"],"scope":["openid"],"authMethod":"tls_client_auth","jwks":null,"accessTokenTtl":600,
      |"refreshTokenTtl":3600,"backChannelLogoutUri":null,"consentFlow":null,"dpopBoundAccessTokens":false,"createdAt":"2026-11-12T10:00:00Z",
      |"loginPath":"/login/acme-shop","approval":{"kind":"create","status":"approved","waitingFields":[],"requestedAt":null,"note":null}}""".stripMargin

  private def claims(clientId: String = "acme-console", subject: String = "sub-1") =
    AccessTokenClaims(
      jti = AccessTokenId("jti-1"),
      subject = subject,
      clientId = ClientId(clientId),
      issuer = "https://idp.example",
      audience = Nil,
      expiresAt = 4102444800L,
      issuedAt = 1767225600L,
      tenantId = TenantId("t"),
      roles = Nil,
      acr = None,
      authTime = None,
      sid = None,
      family = None,
      confirmation = None,
    )

  private def session(clientId: String = "acme-console", preset: String = "acme-console", rotated: Option[Cookie.Response] = None) =
    EdgeService.ConsoleSession(AccessToken("staff-access-token"), claims(clientId), PresetId(preset), rotated)

  private case class Harness(api: PartnerApi, central: FakeCentral, edge: Stub[EdgeService], staff: StaffService)

  /** The console of `acme`, with a team of one person at `level` signed in as `sub-1`, unless `member` is false. */
  private def harness(
      level: Option[StaffLevel],
      current: EdgeService.ConsoleSession = session(),
      userInfo: Json.Obj = Json.Obj("phone_number" -> Json.Str(phone), "phone_number_verified" -> Json.Bool(true)),
      reachable: Boolean = true,
      suspended: Boolean = false,
      liveState: Boolean = false,
      answer: (Method, List[String]) => IO[Failure, Answer] = (_, _) => reply(Status.Ok, "{}"),
  ): ZIO[Any, Nothing, Harness] =
    for
      repository <- StaffMemoryRepository.make
      staff = StaffService.Impl(repository, identifiers, 7.days)
      _ <- ZIO.foreachDiscard(level)(level => staff.invite("config", phone, level, None).mapError(error => RuntimeException(error.toString))).orDie
      _ <- ZIO.foreachDiscard(level)(_ =>
        staff.admit("sub-1", ZIO.succeed(Json.Obj("phone_number" -> Json.Str(phone), "phone_number_verified" -> Json.Bool(true)))),
      ).orDie
      edge = stub[EdgeService]
      _ <- edge.consoleSession.succeedsWith(current)
      _ <- edge.consoleUserInfo.succeedsWith((current, userInfo))
      central = FakeCentral(answer)
      partnerState <- if liveState then liveStateOf
      else ZIO.succeed(PartnerState.fixed(if suspended then me.copy(partner = me.partner.copy(status = "suspended")) else me, reachable))
      api = PartnerApi(edge, staff, partnerState, central, config)
    yield Harness(api, central, edge, staff)

  /** The real state, over a source that answers `me`, to see what a refusal does to it. */
  private val liveStateOf: UIO[PartnerState] =
    ZIO.scoped(
      PartnerState.live.build.map(_.get[PartnerState]).provideLayer(
        ZLayer.succeed[PartnerMeSyncClient](new PartnerMeSyncClient { override def getAll = ZIO.succeed(me) }) ++ ZLayer.succeed(
          config,
        ) ++ ZLayer.succeed(Scope.global),
      ),
    ).orDie

  private def done[A](effect: IO[StaffService.Error | Throwable, A]): UIO[A] = effect.mapError(error => RuntimeException(error.toString)).orDie

  private def send(h: Harness, request: Request): ZIO[Scope & TestClient & Client, Throwable, Response] =
    TestClient.addRoutes(h.api.routes.handleError(_ => Response.internalServerError)) *> ZIO.serviceWithZIO[Client](_.batched(request))

  private def url(path: String, query: String = "") = URL.decode(s"https://login.acme.example/partner/api$path$query").toOption.get

  private def get(path: String, query: String = "") = Request.get(url(path, query))

  private def write(method: Method, path: String, body: String, origin: Option[String] = None) =
    val request = Request(method = method, url = url(path), body = Body.fromString(body)).addHeader(Header.ContentType(MediaType.application.json))
    origin.fold(request)(value => request.addHeader(Header.Custom("Origin", value)))

  private def errorOf(response: Response): Task[(String, Json.Obj)] =
    response.body.asString.map(_.fromJson[Json.Obj].toOption.get).map(body =>
      body.fields.collectFirst { case ("error", Json.Str(code)) => code }.getOrElse("") -> body,
    )

  def spec = suite("PartnerApi")(
    suite("who is asking")(
      test("no session is 401 unauthenticated, with the path to sign in at, and central is not asked") {
        for
          h <- harness(Some(StaffLevel.admin))
          _ <- h.edge.consoleSession.failsWith(EdgeService.NoSession(None))
          response <- send(h, get("/clients"))
          (code, body) <- errorOf(response)
          calls <- h.central.calls.get
        yield assertTrue(response.status == Status.Unauthorized, code == "unauthenticated", body.toJson.contains("/partner/login"), calls.isEmpty)
      },
      test(
        "a session of one of the partner's own applications is not a staff session: another client's token, and another preset's cookie, are refused",
      ) {
        for
          other <- harness(Some(StaffLevel.admin), current = session(clientId = "acme-shop"))
          otherPreset <- harness(Some(StaffLevel.admin), current = session(preset = "acme-shop"))
          byClient <- send(other, get("/clients"))
          byPreset <- send(otherPreset, get("/clients"))
          callsClient <- other.central.calls.get
          callsPreset <- otherPreset.central.calls.get
        yield assertTrue(
          byClient.status == Status.Unauthorized,
          byPreset.status == Status.Unauthorized,
          // not even the team was consulted: /userinfo was not read for it
          other.edge.consoleUserInfo.calls.isEmpty,
          callsClient.isEmpty,
          callsPreset.isEmpty,
        )
      },
      test("a person who is not on the team is 403 not_a_member, told the masked identifier they signed in with and whose team it is") {
        for
          h <- harness(None)
          response <- send(h, get("/me"))
          (code, body) <- errorOf(response)
          calls <- h.central.calls.get
        yield assertTrue(
          response.status == Status.Forbidden,
          code == "not_a_member",
          body.toJson.contains("+7••••••••11"),
          body.toJson.contains("Acme Pay"),
          !body.toJson.contains(phone),
          calls.isEmpty,
        )
      },
      test("the operator no longer honouring the token ends the session instead of failing it") {
        for
          h <- harness(None)
          _ <- h.edge.consoleUserInfo.failsWith(SSOClient.UserInfoUnauthorized)
          response <- send(h, get("/me"))
        yield assertTrue(response.status == Status.Unauthorized)
      },
      test("a first administrator named in the configuration is admitted by the sign-in itself, and is who /me says") {
        for
          h <- harness(None)
          _ <- h.staff.applyAdmins(List(phone))
          response <- send(h, get("/me"))
          body <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains("\"level\":\"admin\""), body.contains("\"sub\":\"sub-1\""))
      },
      test("a state-changing request from another origin is refused, and one from the edge's own is not") {
        for
          h <- harness(Some(StaffLevel.developer))
          foreign <- send(h, write(Method.POST, "/clients", "{}", origin = Some("https://evil.example")))
          own <- send(h, write(Method.POST, "/clients", "{}", origin = Some("https://login.acme.example")))
          (code, _) <- errorOf(foreign)
        yield assertTrue(foreign.status == Status.Forbidden, code == "origin_mismatch", own.status == Status.BadRequest)
      },
      test("the cookie a refresh produced is set on the answer") {
        for
          h <- harness(Some(StaffLevel.viewer), current = session(rotated = Some(Cookie.Response("EDGE_SESSION", "acme-console:next"))))
          response <- send(h, get("/me"))
        yield assertTrue(response.status == Status.Ok, response.headers.get("set-cookie").exists(_.contains("acme-console:next")))
      },
    ),
    suite("levels")(
      test("a viewer reads clients and cannot create one, and central is not asked to") {
        for
          h <- harness(Some(StaffLevel.viewer), answer = (_, _) => reply(Status.Ok, s"""{"clients":[$clientJson]}"""))
          read <- send(h, get("/clients"))
          create <- send(h, write(Method.POST, "/clients", "{}"))
          (code, body) <- errorOf(create)
          calls <- h.central.calls.get
        yield assertTrue(
          read.status == Status.Ok,
          create.status == Status.Forbidden,
          code == "insufficient_level",
          body.toJson.contains("developer"),
          calls.map(_._1) == List(Method.GET),
        )
      },
      test("a developer writes clients, resources and edges, and is refused the team, the settings, the profile and revocations") {
        for
          h <- harness(Some(StaffLevel.developer))
          staff <- send(h, get("/staff"))
          settings <- send(h, get("/settings"))
          profile <- send(h, write(Method.PUT, "/profile", """{"displayName":"x","logoUri":null}"""))
          revoke <- send(h, write(Method.POST, "/revocations", """{"by":"client","value":"acme-shop","clients":["acme-shop"],"edges":[]}"""))
          invite <- send(h, write(Method.POST, "/staff", """{"identifier":"+77010000001","level":"admin"}"""))
          calls <- h.central.calls.get
        yield assertTrue(List(staff, settings, profile, revoke, invite).forall(_.status == Status.Forbidden), calls.isEmpty)
      },
      test("an administrator reaches all of it") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, "{}"))
          staff <- send(h, get("/staff"))
          settings <- send(h, get("/settings"))
        yield assertTrue(staff.status == Status.Ok, settings.status == Status.Ok)
      },
    ),
    suite("calls to central")(
      test("a management call carries the staff member's own access token as the principal, and the team and /me carry nothing to central") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, s"""{"clients":[$clientJson]}"""))
          _ <- send(h, get("/me"))
          _ <- send(h, get("/staff"))
          afterLocal <- h.central.calls.get
          _ <- send(h, get("/clients"))
          calls <- h.central.calls.get
        yield assertTrue(afterLocal.isEmpty, calls.size == 1, calls.head._5 == Some("staff-access-token"), calls.head._2 == List("clients"))
      },
      test("a client is read in the console's shape: one name, what is fixed for a partner's client written out, and the limits for its hints") {
        for
          h <- harness(Some(StaffLevel.viewer), answer = (_, _) => reply(Status.Ok, s"""{"clients":[$clientJson]}"""))
          response <- send(h, get("/clients"))
          body <- response.body.asString.map(_.fromJson[Json.Obj].toOption.get)
          client = body.fields.collectFirst { case ("clients", Json.Arr(elements)) => elements.head.asInstanceOf[Json.Obj] }.get
          field = (name: String) => client.fields.collectFirst { case (`name`, value) => value }
        yield assertTrue(
          field("clientName") == Some(Json.Str("Acme Shop")),
          field("fixed").exists(_.toJson.contains("code+pkce+par")),
          field("fixed").exists(_.toJson.contains("tls_client_auth")),
          field("limits").exists(_.toJson.contains("maxClients")),
          field("jwks").isEmpty,
        )
      },
      test("the settings carry the version central answered to the console, and a save sends it back as a number and nothing else is added") {
        for
          h <- harness(
            Some(StaffLevel.admin),
            answer = (method, _) =>
              if method == Method.GET then
                reply(Status.Ok, """{"contact":{"email":"ops@acme.example","phone":null},"defaults":{"accessTokenTtl":300},"version":4}""")
              else reply(Status.NoContent, ""),
          )
          read <- send(h, get("/settings"))
          readBody <- read.body.asString.map(_.fromJson[Json.Obj].toOption.get)
          saved <- send(
            h,
            write(
              Method.PUT,
              "/settings",
              """{"version":4,"contact":{"email":"incident@acme.example","phone":null,"extra":1},"defaults":{"accessTokenTtl":600},"groupId":"operator"}""",
            ),
          )
          stringVersion <- send(h, write(Method.PUT, "/settings", """{"version":"4","contact":{"email":"a@acme.example"}}"""))
          calls <- h.central.calls.get
          sent = calls.filter(_._1 == Method.PUT).map(_._4)
        yield assertTrue(
          readBody.fields.collectFirst { case ("version", Json.Num(number)) => number.intValue } == Some(4),
          saved.status == Status.NoContent,
          sent.head.exists(_.toJson.contains("\"version\":4")),
          // only the allow-listed keys go on: nothing the console should not set reaches central
          sent.head.exists(body => !body.toJson.contains("groupId") && !body.toJson.contains("extra")),
          // a version that is not a number is not passed on: central refuses the save for the missing one
          sent(1).exists(!_.toJson.contains("version")),
        )
      },
      test("central's 409 on a settings save reaches the console as a conflict with the reason, and a 400 for a missing version as invalid_request") {
        for
          stale <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Conflict, "the settings changed since you read them"))
          missing <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.BadRequest, "version is required"))
          conflict <- send(stale, write(Method.PUT, "/settings", """{"version":1,"contact":{"email":"a@acme.example"}}"""))
          (conflictCode, conflictBody) <- errorOf(conflict)
          invalid <- send(missing, write(Method.PUT, "/settings", """{"contact":{"email":"a@acme.example"}}"""))
          (invalidCode, _) <- errorOf(invalid)
        yield assertTrue(
          conflict.status == Status.Conflict,
          conflictCode == "conflict",
          conflictBody.toJson.contains("the settings changed since you read them"),
          invalid.status == Status.BadRequest,
          invalidCode == "invalid_request",
        )
      },
      test("central unavailable is 503 central_unavailable on a write, and /me still answers, saying so") {
        for
          h <- harness(Some(StaffLevel.admin), reachable = false, answer = (_, _) => ZIO.fail(Failure.Unavailable))
          write <- send(
            h,
            write(Method.POST, "/clients", """{"id":"c","kind":"web","clientName":"C","redirectUris":[],"scope":["openid"],"accessTokenTtl":600}"""),
          )
          read <- send(h, get("/clients"))
          local <- send(h, get("/me"))
          (code, _) <- errorOf(write)
          body <- local.body.asString
        yield assertTrue(
          write.status == Status.ServiceUnavailable,
          code == "central_unavailable",
          read.status == Status.ServiceUnavailable,
          local.status == Status.Ok,
          body.contains("\"central\":\"unavailable\""),
        )
      },
      test("a suspended partner is 403 partner_suspended, and a staff token central does not accept is 403 partner_principal_mismatch") {
        for
          suspended <- harness(Some(StaffLevel.admin), answer = (_, _) => ZIO.fail(Failure.Suspended))
          mismatched <- harness(Some(StaffLevel.admin), answer = (_, _) => ZIO.fail(Failure.PrincipalMismatch))
          first <- send(suspended, get("/edges"))
          second <- send(mismatched, get("/edges"))
          (suspendedCode, _) <- errorOf(first)
          (mismatchCode, _) <- errorOf(second)
        yield assertTrue(
          first.status == Status.Forbidden,
          suspendedCode == "partner_suspended",
          second.status == Status.Forbidden,
          mismatchCode == "partner_principal_mismatch",
        )
      },
      test(
        "central answering 404 to the creation of a client it has just registered (it reads it back from a cache) is read until the client is there",
      ) {
        for
          h <- harness(
            Some(StaffLevel.developer),
            answer = (method, _) =>
              if method == Method.POST then reply(Status.NotFound, "client 'acme-shop' does not exist") else reply(Status.Ok, clientJson),
          )
          response <- send(
            h,
            write(
              Method.POST,
              "/clients",
              """{"id":"acme-shop","kind":"web","clientName":"Acme Shop","redirectUris":["https://shop.acme.example/cb"],"scope":["openid"],"accessTokenTtl":600}""",
            ),
          )
          body <- response.body.asString
        yield assertTrue(response.status == Status.Accepted, body.contains("acme-shop"))
      },
      test("a refusal of central's own validation is passed on as 400, and what is not there as 404") {
        for
          bad <- harness(Some(StaffLevel.developer), answer = (_, _) => reply(Status.BadRequest, "redirect uri is not under the partner's domains"))
          missing <- harness(Some(StaffLevel.developer), answer = (_, _) => reply(Status.NotFound, "client 'x' does not exist"))
          invalid <- send(
            bad,
            write(
              Method.POST,
              "/clients",
              """{"id":"c","kind":"web","clientName":"C","redirectUris":["http://x"],"scope":["openid"],"accessTokenTtl":600}""",
            ),
          )
          gone <- send(missing, Request.delete(url("/clients/x")))
          (code, body) <- errorOf(invalid)
        yield assertTrue(
          invalid.status == Status.BadRequest,
          code == "invalid_request",
          body.toJson.contains("partner's domains"),
          gone.status == Status.NotFound,
        )
      },
    ),
    suite("revocation")(
      test("ends the sessions on this edge by user, sid or fam and answers how many, without asking central or auth") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => ZIO.fail(Failure.Unavailable))
          _ <- h.edge.revokeSessions.succeedsWith(3)
          user <- send(h, write(Method.POST, "/revocations", """{"by":"user","value":"u-1","clients":[],"edges":[]}"""))
          sid <- send(h, write(Method.POST, "/revocations", """{"by":"sid","value":"s-1"}"""))
          fam <- send(h, write(Method.POST, "/revocations", """{"by":"fam","value":"f-1"}"""))
          body <- user.body.asString
          calls <- h.central.calls.get
          asked = h.edge.revokeSessions.calls
        yield assertTrue(
          user.status == Status.Ok,
          sid.status == Status.Ok,
          fam.status == Status.Ok,
          body == """{"ended":3}""",
          // central is down and nothing was asked of it
          calls.isEmpty,
          asked == List(
            EdgeService.SessionRevocation.User("u-1"),
            EdgeService.SessionRevocation.Session(versola.edge.model.SessionId("s-1")),
            EdgeService.SessionRevocation.Family(versola.edge.model.RefreshTokenFamilyId("f-1")),
          ),
        )
      },
      test("is refused to a developer and a viewer, and nothing is ended") {
        for
          developer <- harness(Some(StaffLevel.developer))
          viewer <- harness(Some(StaffLevel.viewer))
          a <- send(developer, write(Method.POST, "/revocations", """{"by":"user","value":"u-1"}"""))
          b <- send(viewer, write(Method.POST, "/revocations", """{"by":"user","value":"u-1"}"""))
          list <- send(viewer, get("/revocations"))
        yield assertTrue(
          a.status == Status.Forbidden,
          b.status == Status.Forbidden,
          list.status == Status.Forbidden,
          developer.edge.revokeSessions.calls.isEmpty,
          viewer.edge.revokeSessions.calls.isEmpty,
        )
      },
      test("by client, or by anything else, and a missing value are 400, and nothing is ended") {
        for
          h <- harness(Some(StaffLevel.admin))
          client <- send(h, write(Method.POST, "/revocations", """{"by":"client","value":"acme-shop","clients":["acme-shop"]}"""))
          nothing <- send(h, write(Method.POST, "/revocations", """{"by":"user"}"""))
          unknown <- send(h, write(Method.POST, "/revocations", """{"value":"x"}"""))
        yield assertTrue(
          client.status == Status.BadRequest,
          nothing.status == Status.BadRequest,
          unknown.status == Status.BadRequest,
          h.edge.revokeSessions.calls.isEmpty,
        )
      },
      test("what an administrator ended is listed, newest first, with who ended it and how many, and is in the audit trail") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, """{"events":[],"next":null}"""))
          _ <- h.edge.revokeSessions.succeedsWith(2)
          _ <- send(h, write(Method.POST, "/revocations", """{"by":"user","value":"u-1"}"""))
          listed <- send(h, get("/revocations")).flatMap(_.body.asString)
          trail <- send(h, get("/audit", "?kind=revocations")).flatMap(_.body.asString)
          team <- send(h, get("/audit", "?kind=team")).flatMap(_.body.asString)
        yield assertTrue(
          listed.contains("\"by_staff\":\"sub-1\""),
          listed.contains("\"by\":\"user\""),
          listed.contains("\"value\":\"u-1\""),
          listed.contains("\"ended\":2"),
          trail.contains("Ended 2 session(s) by user u-1"),
          !team.contains("Ended 2"),
        )
      },
    ),
    suite("the team")(
      test("an administrator invites by a phone number, and is told it by the masked form only; removing themself is refused") {
        for
          h <- harness(Some(StaffLevel.admin))
          invited <- send(h, write(Method.POST, "/staff", """{"identifier":"+77010000001","level":"developer"}"""))
          body <- invited.body.asString
          self <- send(h, Request.delete(url("/staff/sub-1")))
          (code, _) <- errorOf(self)
          listed <- send(h, get("/staff")).flatMap(_.body.asString)
        yield assertTrue(
          invited.status == Status.Created,
          body.contains("\"state\":\"invited\""),
          !body.contains("+77010000001"),
          self.status == Status.Conflict,
          code == "conflict",
          listed.contains("\"state\":\"member\""),
        )
      },
      test("an invite for what is not an identifier is 400") {
        for
          h <- harness(Some(StaffLevel.admin))
          response <- send(h, write(Method.POST, "/staff", """{"identifier":"somebody","level":"viewer"}"""))
        yield assertTrue(response.status == Status.BadRequest)
      },
    ),
    suite("hardening")(
      test("every route refuses a level below its own, by a table: viewer, developer and admin against all of them") {
        val routes: List[(Method, String, StaffLevel)] = List(
          (Method.GET, "/me", StaffLevel.viewer),
          (Method.GET, "/clients", StaffLevel.viewer),
          (Method.POST, "/clients", StaffLevel.developer),
          (Method.PUT, "/clients/x", StaffLevel.developer),
          (Method.DELETE, "/clients/x", StaffLevel.developer),
          (Method.POST, "/clients/x/withdraw", StaffLevel.developer),
          (Method.POST, "/clients/x/rotate-secret", StaffLevel.developer),
          (Method.GET, "/resources", StaffLevel.viewer),
          (Method.POST, "/resources", StaffLevel.developer),
          (Method.PUT, "/resources/x", StaffLevel.developer),
          (Method.DELETE, "/resources/x", StaffLevel.developer),
          (Method.POST, "/resources/x/rotate-secret", StaffLevel.developer),
          (Method.GET, "/edges", StaffLevel.viewer),
          (Method.POST, "/edges", StaffLevel.developer),
          (Method.DELETE, "/edges/x", StaffLevel.developer),
          (Method.POST, "/edges/x/enrollment-tokens", StaffLevel.developer),
          (Method.DELETE, "/edges/x/enrollment-tokens", StaffLevel.developer),
          (Method.POST, "/revocations", StaffLevel.admin),
          (Method.GET, "/revocations", StaffLevel.admin),
          (Method.GET, "/audit", StaffLevel.viewer),
          (Method.GET, "/staff", StaffLevel.admin),
          (Method.POST, "/staff", StaffLevel.admin),
          (Method.PUT, "/staff/x", StaffLevel.admin),
          (Method.DELETE, "/staff/x", StaffLevel.admin),
          (Method.GET, "/settings", StaffLevel.admin),
          (Method.PUT, "/settings", StaffLevel.admin),
          (Method.GET, "/profile", StaffLevel.admin),
          (Method.PUT, "/profile", StaffLevel.admin),
        )
        for
          results <- ZIO.foreach(StaffLevel.values.toList)(have =>
            harness(Some(have)).flatMap(h =>
              ZIO.foreach(routes)((method, path, needs) =>
                send(
                  h,
                  Request(method = method, url = url(path), body = Body.fromString("{}")).addHeader(Header.ContentType(MediaType.application.json)),
                )
                  .map(response => (have, method, path, needs, response.status == Status.Forbidden)),
              ),
            ),
          ).map(_.flatten)
          wrong = results.filter((have, _, _, needs, refused) => refused == have.atLeast(needs))
        yield assertTrue(wrong.isEmpty, results.size == 3 * routes.size)
      },
      test(
        "a partner cannot name its group, its kind or its party in any write: what central is sent never carries those fields, by a table over every write route",
      ) {
        // what only the operator decides, or what the edge's certificate decides: a request that names them gets nothing out of them
        val attacker = "attacker-first-party"
        val forbidden =
          List("groupId", "groupKind", "partnerId", "edgeId", "party", "partyKind", "group", "tenantId", "mtlsAuth", "approval", "status")
        val extra = forbidden.map(name => s""""$name":"$attacker"""").mkString(
          ",",
        ) + s""","mtlsAuth":{"subjectDn":"$attacker"},"approval":{"status":"approved"}"""
        def withExtra(body: String): String = body.stripSuffix("}") + (if body == "{}" then "" else ",") + extra + "}"
        val writes: List[(Method, String, String, Boolean)] = List(
          (
            Method.POST,
            "/clients",
            """{"id":"c","kind":"web","clientName":"C","redirectUris":["https://x.acme.example/cb"],"scope":["openid"],"accessTokenTtl":600}""",
            true,
          ),
          (Method.PUT, "/clients/acme-shop", """{"id":"acme-shop","clientName":"Shop"}""", true),
          (Method.DELETE, "/clients/acme-shop", "{}", true),
          (Method.POST, "/clients/acme-shop/withdraw", "{}", true),
          (Method.POST, "/clients/acme-shop/rotate-secret", "{}", false),
          (Method.POST, "/resources", """{"resourceId":"r","resource":"https://api.acme.example","endpoints":[]}""", true),
          (Method.PUT, "/resources/r", """{"resourceId":"r","resource":"https://api.acme.example","endpoints":[]}""", false),
          (Method.DELETE, "/resources/r", "{}", true),
          (Method.POST, "/resources/r/rotate-secret", "{}", true),
          (Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}""", true),
          (Method.DELETE, "/edges/e", "{}", true),
          (Method.POST, "/edges/e/enrollment-tokens", "{}", true),
          (Method.DELETE, "/edges/e/enrollment-tokens", "{}", true),
          (Method.POST, "/revocations", """{"by":"user","value":"u-1"}""", false),
          (Method.POST, "/staff", """{"identifier":"+77010000001","level":"viewer"}""", false),
          (Method.PUT, "/staff/sub-1", """{"level":"admin"}""", false),
          (Method.DELETE, "/staff/sub-2", "{}", false),
          (Method.PUT, "/settings", """{"defaults":{}}""", true),
          (Method.PUT, "/profile", """{"displayName":"Acme","logoUri":null}""", true),
        )
        def answer(method: Method, path: List[String]): IO[Failure, Answer] =
          path match
            case List("clients") if method == Method.GET => reply(Status.Ok, s"""{"clients":[$clientJson]}""")
            case List("resources") if method == Method.GET =>
              reply(
                Status.Ok,
                """{"resources":[{"resourceId":"r","resource":"https://api.acme.example","endpoints":[],"secret":null,"audienceRateLimits":[]}]}""",
              )
            case _ => reply(Status.Ok, s"""{"edge":{"id":"e","publicUrl":"https://x.example"},"token":"t","client":$clientJson}""")
        for
          results <- ZIO.foreach(writes): (method, path, body, reachesCentral) =>
            for
              h <- harness(Some(StaffLevel.admin), answer = answer)
              _ <- h.edge.revokeSessions.succeedsWith(0)
              response <- send(h, write(method, path, withExtra(body)))
              calls <- h.central.calls.get
              sent = calls.collect { case (_, _, query, Some(payload), _) => payload.toJson + query.toString }
            yield (method, path, response.status, sent, calls.nonEmpty, reachesCentral)
          leaked = results.filter((_, _, _, sent, _, _) => sent.exists(_.contains(attacker)))
          fields = results.filter((_, _, _, sent, _, _) =>
            sent.exists(payload => forbidden.exists(name => payload.contains(s""""$name":"""))),
          )
          // not served is a refusal, never a server fault
          faults = results.filter((_, _, status, _, _, _) => status.code >= 500)
          unreached = results.filter((_, _, _, _, reached, expected) => expected && !reached)
        yield assert(
          (
            leaked.map(r => (r._1.toString, r._2)),
            fields.map(r => (r._1.toString, r._2, r._4)),
            faults.map(r => (r._1.toString, r._2, r._3.code)),
            unreached.map(r => (r._1.toString, r._2, r._3.code)),
            results.size,
          ),
        )(Assertion.equalTo((Nil, Nil, Nil, Nil, writes.size)))
      },
      test(
        "what a partner edge serves has no first-party surface: no /service, /central or /configuration path in any route, and the first-party edge does have /service",
      ) {
        def paths(routes: Routes[?, ?]): List[String] = routes.routes.toList.map(_.routePattern.render.split(" ", 2).last)
        val forbidden = List("/service", "/central", "/configuration")
        for
          h <- harness(Some(StaffLevel.admin))
          partnerEdge = paths(
            versola.edge.EdgeController.routes ++ versola.edge.EdgeController.resourceRoutes ++ versola.edge.nativeapp.NativeController.routes ++
              versola.edge.InternalSurface.routes(versola.edge.EdgeMode.Partner) ++ h.api.routes ++
              PartnerConsole(PartnerState.fixed(me, true), None).routes,
          )
          firstParty = paths(versola.edge.InternalSurface.routes(versola.edge.EdgeMode.FirstParty))
        yield assert(
          (
            partnerEdge.exists(_.startsWith("/partner/api")),
            partnerEdge.filter(path => forbidden.exists(prefix => path == prefix || path.startsWith(prefix + "/"))),
            firstParty.exists(_.startsWith("/service")),
            partnerEdge.take(80),
          ),
        )(Assertion.assertion("only its own surface")(summary => summary._1 && summary._2.isEmpty && summary._3))
      },
      test("a state-changing request of any method from another origin is refused: PUT and DELETE too, before anything is read") {
        for
          h <- harness(Some(StaffLevel.admin))
          put <- send(h, write(Method.PUT, "/clients/acme-shop", "{}", origin = Some("https://evil.example")))
          delete <- send(h, Request.delete(url("/clients/acme-shop")).addHeader(Header.Custom("Origin", "https://evil.example")))
          post <- send(h, write(Method.POST, "/staff", """{"identifier":"+77010000001","level":"admin"}""", origin = Some("https://evil.example")))
          calls <- h.central.calls.get
        yield assertTrue(put.status == Status.Forbidden, delete.status == Status.Forbidden, post.status == Status.Forbidden, calls.isEmpty)
      },
      test("a member who is removed, or whose level is lowered, loses what they could do at their next request") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, s"""{"clients":[$clientJson]}"""))
          second <- done(h.staff.invite("sub-1", "+77010000001", StaffLevel.admin, None))
          _ <- h.staff.admit(
            "sub-2",
            ZIO.succeed(Json.Obj("phone_number" -> Json.Str("+77010000001"), "phone_number_verified" -> Json.Bool(true))),
          ).orDie
          before <- send(h, write(Method.POST, "/clients", "{}"))
          _ <- done(h.staff.setLevel("sub-2", "sub-1", StaffLevel.viewer))
          demoted <- send(h, write(Method.POST, "/clients", "{}"))
          read <- send(h, get("/clients"))
          _ <- done(h.staff.setLevel("sub-2", "sub-1", StaffLevel.admin))
          _ <- done(h.staff.remove("sub-2", "sub-1"))
          removed <- send(h, get("/clients"))
        yield assertTrue(
          before.status != Status.Forbidden,
          demoted.status == Status.Forbidden,
          read.status == Status.Ok,
          removed.status == Status.Forbidden,
          second.id.nonEmpty,
        )
      },
      test("the cookie a refresh produced is set on a refusal of the person too: not a member, wrong client") {
        val rotated = Some(Cookie.Response("EDGE_SESSION", "acme-console:next"))
        for
          stranger <- harness(None, current = session(rotated = rotated))
          other <- harness(Some(StaffLevel.admin), current = session(clientId = "acme-shop", rotated = rotated))
          first <- send(stranger, get("/me"))
          second <- send(other, get("/me"))
        yield assertTrue(
          first.status == Status.Forbidden,
          first.headers.get("set-cookie").exists(_.contains("acme-console:next")),
          second.status == Status.Unauthorized,
        )
      },
      test("a suspended partner is refused a change at once by the edge, central is not asked, and /me says so") {
        for
          h <- harness(Some(StaffLevel.admin), suspended = true)
          write <- send(h, write(Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}"""))
          read <- send(h, get("/me")).flatMap(_.body.asString)
          calls <- h.central.calls.get
          (code, _) <- errorOf(write)
        yield assertTrue(write.status == Status.Forbidden, code == "partner_suspended", read.contains("\"status\":\"suspended\""), calls.isEmpty)
      },
      test("what is local keeps working while the partner is suspended: ending sessions and the team; what is central's is refused") {
        for
          h <- harness(Some(StaffLevel.admin), suspended = true)
          _ <- h.edge.revokeSessions.succeedsWith(1)
          revoke <- send(h, write(Method.POST, "/revocations", """{"by":"user","value":"u-1"}"""))
          invite <- send(h, write(Method.POST, "/staff", """{"identifier":"+77010000001","level":"viewer"}"""))
          edge <- send(h, write(Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}"""))
          calls <- h.central.calls.get
        yield assertTrue(revoke.status == Status.Ok, invite.status == Status.Created, edge.status == Status.Forbidden, calls.isEmpty)
      },
      test("the cookie of a refresh made by /userinfo is set on the answer, a refusal of the person included, and a fault of any kind") {
        val refreshed = EdgeService.ConsoleSession(
          AccessToken("staff-access-token-2"),
          claims(),
          PresetId("acme-console"),
          Some(Cookie.Response("EDGE_SESSION", "acme-console:after-userinfo")),
        )
        for
          stranger <- harness(None)
          _ <- stranger.edge.consoleUserInfo.succeedsWith((
            refreshed,
            Json.Obj("phone_number" -> Json.Str("+70000000000"), "phone_number_verified" -> Json.Bool(true)),
          ))
          refused <- send(stranger, get("/clients"))
          fault <- harness(
            Some(StaffLevel.viewer),
            current = session(rotated = Some(Cookie.Response("EDGE_SESSION", "acme-console:next"))),
            answer = (_, _) => ZIO.die(new RuntimeException("boom")),
          )
          faulted <- send(fault, get("/clients"))
        yield assertTrue(
          refused.status == Status.Forbidden,
          refused.headers.get("set-cookie").exists(_.contains("acme-console:after-userinfo")),
          faulted.status == Status.InternalServerError,
          faulted.headers.get("set-cookie").exists(_.contains("acme-console:next")),
        )
      },
      test("what central says in an error of its own is not the person's to read: a 5xx is a code, a refusal of the request keeps central's text") {
        for
          server <- harness(
            Some(StaffLevel.admin),
            answer = (_, _) => reply(Status.InternalServerError, "java.sql.SQLException: password authentication failed for user central"),
          )
          refused <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.BadRequest, "redirect uri is outside the limits of the partner"))
          serverBody <- send(server, write(Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}""")).flatMap(_.body.asString)
          refusedBody <- send(refused, write(Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}""")).flatMap(_.body.asString)
        yield assertTrue(
          !serverBody.contains("SQLException"),
          serverBody.contains("central_answered_500"),
          refusedBody.contains("outside the limits"),
        )
      },
      test("a refusal of central that says suspended is remembered by the state, which then reports it") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => ZIO.fail(Failure.Suspended), liveState = true)
          first <- send(h, get("/edges"))
          me <- send(h, get("/me")).flatMap(_.body.asString)
        yield assertTrue(first.status == Status.Forbidden, me.contains("\"status\":\"suspended\""))
      },
      test("the codes the console has no text for carry one, and the null URLs of /me are empty strings") {
        for
          h <- harness(Some(StaffLevel.viewer))
          refused <- send(h, write(Method.POST, "/clients", "{}"))
          me <- send(h, get("/me")).flatMap(_.body.asString.map(_.fromJson[Json.Obj].toOption.get))
          (_, body) <- errorOf(refused)
        yield assertTrue(
          body.toJson.contains("\"message\":\"Your level"),
          me.toJson.contains("\"mtlsUrl\":\"\""),
          me.toJson.contains("\"centralMtlsUrl\":\"\""),
        )
      },
    ),
    suite("enrollment")(
      test("an edge is created with its token and nothing else to copy: the edge, the token and when it expires") {
        for
          h <- harness(
            Some(StaffLevel.developer),
            answer = (_, _) =>
              reply(
                Status.Created,
                """{"edge":{"id":"e","publicUrl":"https://x.example"},"token":"vtk1.a.b","expiresAt":"2026-11-13T10:00:00Z","signingKey":{"keyId":"k","privateKey":"p"},"bundle":"{}"}""",
              ),
          )
          created <- send(
            h,
            write(Method.POST, "/edges", """{"id":"e","publicUrl":"https://x.example"}"""),
          ).flatMap(_.body.asString.map(_.fromJson[Json.Obj].toOption.get))
        yield assertTrue(
          created.keys.toSet == Set("edge", "token", "expiresAt"),
          created.get("token").flatMap(_.asString).contains("vtk1.a.b"),
          created.get("expiresAt").flatMap(_.asString).contains("2026-11-13T10:00:00Z"),
        )
      },
      test("a new token for an edge is the token and when it expires") {
        for
          h <- harness(
            Some(StaffLevel.developer),
            answer = (_, _) => reply(Status.Created, """{"token":"vtk1.a.c","expiresAt":"2026-11-13T10:00:00Z","bundle":"{}"}"""),
          )
          issued <- send(h, write(Method.POST, "/edges/e/enrollment-tokens", "{}")).flatMap(_.body.asString.map(_.fromJson[Json.Obj].toOption.get))
        yield assertTrue(issued.keys.toSet == Set("token", "expiresAt"), issued.get("token").flatMap(_.asString).contains("vtk1.a.c"))
      },
    ),
    suite("the audit trail")(
      test("the console's groups are central's kinds: clients are the events of clients, and the team's own events are laid among them") {
        val events =
          """{"events":[
            |{"id":"1","at":"2026-11-12T10:03:00Z","kind":"admin.partner-client.create","outcome":"success","actorType":"partner-staff","actorId":"sub-1","targetType":"partner-client","targetId":"c","details":{}},
            |{"id":"2","at":"2026-11-12T10:02:00Z","kind":"admin.partner-resource.create","outcome":"success","actorType":"partner-staff","actorId":"sub-1","targetType":"partner-resource","targetId":"r","details":{}},
            |{"id":"3","at":"2026-11-12T10:01:00Z","kind":"admin.partner.suspend","outcome":"success","actorType":"admin","actorId":null,"targetType":"partner","targetId":"acme","details":{}}
            |],"next":null}""".stripMargin
        for
          h <- harness(Some(StaffLevel.viewer), answer = (_, _) => reply(Status.Ok, events))
          clients <- send(h, get("/audit", "?kind=clients")).flatMap(_.body.asString)
          resources <- send(h, get("/audit", "?kind=resources")).flatMap(_.body.asString)
          security <- send(h, get("/audit", "?kind=security")).flatMap(_.body.asString)
          operator <- send(h, get("/audit", "?actor=operator")).flatMap(_.body.asString)
          team <- send(h, get("/audit", "?actor=team")).flatMap(_.body.asString)
          calls <- h.central.calls.get
        yield assertTrue(
          clients.contains("Created client"),
          !clients.contains("Created resource"),
          !clients.contains("Suspended"),
          resources.contains("Created resource"),
          !resources.contains("Created client"),
          security.contains("Suspended organisation"),
          !security.contains("Created client"),
          operator.contains("Suspended organisation"),
          operator.contains("\"byOperator\":true"),
          !operator.contains("Created client"),
          team.contains("Created client"),
          !team.contains("Suspended organisation"),
          // what central is asked is never the console's group name, which it would match against nothing
          calls.forall(call => !call._3.contains("kind") && !call._3.get("actor").exists(Set("team", "operator", "edges"))),
        )
      },
      test("the operator's reveal of a secret is not in the partner's trail") {
        val events =
          """{"events":[
            |{"id":"1","at":"2026-11-12T10:03:00Z","kind":"admin.secret.revealed","outcome":"success","actorType":"admin","actorId":"op","targetType":"partner","targetId":"acme","details":{}},
            |{"id":"2","at":"2026-11-12T10:02:00Z","kind":"admin.partner-client.create","outcome":"success","actorType":"partner-staff","actorId":"sub-1","targetType":"partner-client","targetId":"c","details":{}}
            |],"next":null}""".stripMargin
        for
          h <- harness(Some(StaffLevel.viewer), answer = (_, _) => reply(Status.Ok, events))
          trail <- send(h, get("/audit")).flatMap(_.body.asString)
        yield assertTrue(!trail.contains("Revealed a secret"), trail.contains("Created client"))
      },
      test("what the team did is in the trail with who did it, and the group `team` is that alone, without asking central") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, """{"events":[],"next":null}"""))
          _ <- send(h, write(Method.POST, "/staff", """{"identifier":"+77010000001","level":"developer"}"""))
          all <- send(h, get("/audit")).flatMap(_.body.asString)
          team <- send(h, get("/audit", "?kind=team")).flatMap(_.body.asString)
          clients <- send(h, get("/audit", "?kind=clients")).flatMap(_.body.asString)
          callsForTeam <- h.central.calls.get.map(_.count(_._2 == List("audit")))
        yield assertTrue(
          all.contains("Invited"),
          team.contains("Invited"),
          !clients.contains("Invited"),
          !all.contains("+77010000001"),
          callsForTeam == 2,
        )
      },
    ),
    suite("shapes")(
      test("an update sends central only what changed, so that renaming nothing is not an approval") {
        val current = clientJson.fromJson[Json.Obj].toOption.get
        val unchanged = Json.Obj(
          "id" -> Json.Str("acme-shop"),
          "clientName" -> Json.Str("Acme Shop"),
          "logoUri" -> Json.Null,
          "redirectUris" -> Json.Arr(Json.Str("https://shop.acme.example/cb")),
          "scope" -> Json.Arr(Json.Str("openid")),
          "accessTokenTtl" -> Json.Num(600),
          "refreshTokenTtl" -> Json.Num(3600),
        )
        val renamed = Json.Obj(unchanged.fields.map { case ("clientName", _) => "clientName" -> Json.Str("Acme Store"); case other => other })
        val moved = Json.Obj(unchanged.fields.map {
          case ("redirectUris", _) => "redirectUris" -> Json.Arr(Json.Str("https://shop.acme.example/other")); case other => other
        })
        assertTrue(
          PartnerApi.clientUpdateForCentral("acme-shop", unchanged, current).toJson == """{"id":"acme-shop"}""",
          PartnerApi.clientUpdateForCentral("acme-shop", renamed, current).toJson.contains("Acme Store"),
          PartnerApi.clientUpdateForCentral("acme-shop", moved, current).toJson.contains("other"),
          !PartnerApi.clientUpdateForCentral("acme-shop", moved, current).toJson.contains("clientName"),
        )
      },
      test(
        "a new client is sent to central with exactly the fields it takes, the name as a locale map and the method defaulting to the group's certificate",
      ) {
        val sent = PartnerApi.clientForCentral(
          """{"id":"c","kind":"native","clientName":"C","redirectUris":["https://a.example/cb"],"scope":["openid"],"accessTokenTtl":3600,"postLogoutRedirectUris":["x"]}"""
            .fromJson[Json.Obj].toOption.get,
        ).toOption.get
        assertTrue(
          sent.toJson.contains("""{"en":"C"}"""),
          sent.toJson.contains("tls_client_auth"),
          !sent.toJson.contains("postLogoutRedirectUris"),
          PartnerApi.clientForCentral(Json.Obj("id" -> Json.Str("c"))).isLeft,
        )
      },
      test("a resource is replaced as a whole in the console and patched in central: the audience and the endpoints that differ") {
        val current =
          """{"resourceId":"r","resource":"https://api.acme.example","audience":["a","b"],"endpoints":[{"id":"e1","path":"/x","method":"GET","fetchUserInfo":false,"allow":"true","inject":[]},{"id":"e2","path":"/y","method":"GET","fetchUserInfo":false,"allow":null,"inject":[]}]}"""
            .fromJson[Json.Obj].toOption.get
        val wanted =
          """{"resourceId":"r","resource":"https://api.acme.example","audience":["b","c"],"endpoints":[{"id":"e1","path":"/x","method":"GET","fetchUserInfo":false,"allow":"claims.scope.contains('x')","inject":[]},{"path":"/z","method":"POST","fetchUserInfo":false,"allow":"true","inject":[]}]}"""
            .fromJson[Json.Obj].toOption.get
        val patch = PartnerApi.resourceUpdateForCentral("r", wanted, current)
        val text = patch.toJson
        assertTrue(
          text.contains(""""add":["c"]"""),
          text.contains(""""remove":["a"]"""),
          // e1 changed (deleted and created again), e2 is gone, the new one is created with an id made for it
          text.contains(""""deleteEndpoints":["e1","e2"]"""),
          text.contains("claims.scope.contains('x')"),
          text.contains("/z"),
        )
      },
      test("an event of the audit log is the console's: the staff member and the edge, or the operator, and an outcome in its words") {
        val staff = PartnerApi.auditEventDto(
          """{"id":"1","at":"2026-11-12T10:00:00Z","kind":"admin.partner-client.create","outcome":"denied","actorType":"partner-staff","actorId":"sub-1","targetType":"partner-client","targetId":"acme-shop","details":{"edgeId":"edge-1"}}"""
            .fromJson[Json.Obj].toOption.get,
          Map("sub-1" -> "Ann"),
        )
        val operator = PartnerApi.auditEventDto(
          """{"id":"2","at":"2026-11-12T10:00:00Z","kind":"admin.partner.suspend","outcome":"success","actorType":"admin","actorId":null,"targetType":"partner","targetId":"acme","details":{}}"""
            .fromJson[Json.Obj].toOption.get,
          Map.empty,
        )
        assertTrue(
          staff.toJson.contains(""""outcome":"refused""""),
          staff.toJson.contains(""""from":"edge-1""""),
          staff.toJson.contains(""""actor":{"sub":"sub-1","display":"Ann"}"""),
          staff.toJson.contains(""""action":"Created client""""),
          operator.toJson.contains(""""byOperator":true"""),
          operator.toJson.contains(""""outcome":"ok""""),
        )
      },
    ),
  ).provide(TestClient.layer, Scope.default) @@ TestAspect.silentLogging
