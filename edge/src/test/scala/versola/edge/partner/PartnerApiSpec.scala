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
      userInfo: Json.Obj = Json.Obj("phone_number" -> Json.Str(phone)),
      reachable: Boolean = true,
      answer: (Method, List[String]) => IO[Failure, Answer] = (_, _) => reply(Status.Ok, "{}"),
  ): ZIO[Any, Nothing, Harness] =
    for
      repository <- StaffMemoryRepository.make
      staff = StaffService.Impl(repository, identifiers, 7.days)
      _ <- ZIO.foreachDiscard(level)(level => staff.invite("config", phone, level, None).mapError(error => RuntimeException(error.toString))).orDie
      _ <- ZIO.foreachDiscard(level)(_ => staff.admit("sub-1", ZIO.succeed(Json.Obj("phone_number" -> Json.Str(phone))))).orDie
      edge = stub[EdgeService]
      _ <- edge.consoleSession.succeedsWith(current)
      _ <- edge.consoleUserInfo.succeedsWith(userInfo)
      central = FakeCentral(answer)
      api = PartnerApi(edge, staff, PartnerState.fixed(me, reachable), central, config)
    yield Harness(api, central, edge, staff)

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
      test("is a call to central by user or by client, scoped to the named clients, with the refresh tokens revoked: nothing is ended here") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Accepted, ""))
          response <- send(h, write(Method.POST, "/revocations", """{"by":"user","value":"u-1","clients":["acme-shop"],"edges":[]}"""))
          calls <- h.central.calls.get
        yield assertTrue(
          response.status == Status.Accepted,
          calls.map(c => (c._1, c._2)) == List((Method.POST, List("revocations"))),
          calls.head._4.exists(_.toJson == """{"by":"user","value":"u-1","clients":["acme-shop"],"revokeRefresh":true}"""),
          calls.head._5 == Some("staff-access-token"),
        )
      },
      test("with central unavailable it fails, and does not succeed edge-locally") {
        for
          h <- harness(Some(StaffLevel.admin), answer = (_, _) => ZIO.fail(Failure.Unavailable))
          response <- send(h, write(Method.POST, "/revocations", """{"by":"client","value":"acme-shop","clients":["acme-shop"],"edges":[]}"""))
          (code, _) <- errorOf(response)
        yield assertTrue(response.status == Status.ServiceUnavailable, code == "central_unavailable")
      },
      test("by session or by refresh family is refused, as central does not take them, and central is not asked") {
        for
          h <- harness(Some(StaffLevel.admin))
          bySid <- send(h, write(Method.POST, "/revocations", """{"by":"sid","value":"s","clients":["acme-shop"],"edges":[]}"""))
          byFam <- send(h, write(Method.POST, "/revocations", """{"by":"fam","value":"f","clients":["acme-shop"],"edges":[]}"""))
          calls <- h.central.calls.get
        yield assertTrue(bySid.status == Status.BadRequest, byFam.status == Status.BadRequest, calls.isEmpty)
      },
      test("with no client named it is every approved client of the partner, and none approved is refused") {
        for
          h <- harness(
            Some(StaffLevel.admin),
            answer = (method, path) => if method == Method.GET then reply(Status.Ok, s"""{"clients":[$clientJson]}""") else reply(Status.Accepted, ""),
          )
          response <- send(h, write(Method.POST, "/revocations", """{"by":"user","value":"u-1","clients":[],"edges":[]}"""))
          calls <- h.central.calls.get
          none <- harness(Some(StaffLevel.admin), answer = (_, _) => reply(Status.Ok, """{"clients":[]}"""))
          refused <- send(none, write(Method.POST, "/revocations", """{"by":"user","value":"u-1","clients":[],"edges":[]}"""))
        yield assertTrue(
          response.status == Status.Accepted,
          calls.last._4.exists(_.toJson.contains("""["acme-shop"]""")),
          refused.status == Status.BadRequest,
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
        )
        val operator = PartnerApi.auditEventDto(
          """{"id":"2","at":"2026-11-12T10:00:00Z","kind":"admin.partner.suspend","outcome":"success","actorType":"admin","actorId":null,"targetType":"partner","targetId":"acme","details":{}}"""
            .fromJson[Json.Obj].toOption.get,
        )
        assertTrue(
          staff.toJson.contains(""""outcome":"refused""""),
          staff.toJson.contains(""""from":"edge-1""""),
          staff.toJson.contains(""""actor":{"sub":"sub-1"}"""),
          operator.toJson.contains(""""byOperator":true"""),
          operator.toJson.contains(""""outcome":"ok""""),
        )
      },
    ),
  ).provide(TestClient.layer, Scope.default) @@ TestAspect.silentLogging
