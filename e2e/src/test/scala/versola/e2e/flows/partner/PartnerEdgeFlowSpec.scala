package versola.e2e.flows.partner

import versola.e2e.support.*
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The partner edge (#549, #564) as a partner's staff and the operator meet it, on the staged stack: a second edge started with
  * `mode = "partner"`, its console client signed into through the operator's auth with the certificate of the edge, its staff matched to
  * the phone numbers they signed in with, and its management calls made to central with the staff access token.
  *
  * What only this level shows: the mutual TLS to central's partner surface and the sync scoped to the partner by the certificate; the
  * console client authenticating to auth through the terminator with the group certificate; the `/userinfo` of the operator carrying the
  * phone number an invite is matched to; and central's refusals (a suspended partner) reaching the console as what they are.
  */
object PartnerEdgeFlowSpec extends ZIOSpec[PartnerEdgeFixture & OAuthClient & CentralApi & E2EConfig & Client]:

  override val bootstrap: ZLayer[Any, Any, PartnerEdgeFixture & OAuthClient & CentralApi & E2EConfig & Client] =
    val base = (E2EConfig.live ++ Client.default) >+> (OAuthClient.live ++ CentralApi.live)
    // the staff's accounts and the console's tenant are the ones the shared bootstrap provisions
    (base >+> Flows.layer.map(_ => ZEnvironment.empty)) >+> PartnerEdgeFixture.layer

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock, TestAspect.sequential, TestAspect.timeout(600.seconds))

  private val fixture = ZIO.service[PartnerEdgeFixture]

  /** The partner API as the console calls it: with the session cookie, JSON in and out. */
  private def call(
      f: PartnerEdgeFixture,
      session: Option[EdgeSession],
      method: Method,
      path: String,
      body: Option[Json] = None,
  ): ZIO[Client, Throwable, (Status, Json.Obj)] =
    for
      url <- ZIO.fromEither(URL.decode(s"${f.baseUrl}$path")).mapError(RuntimeException(_))
      base = Request(method = method, url = url, body = body.fold(Body.empty)(json => Body.fromString(json.toJson)))
        .addHeader(Header.ContentType(MediaType.application.json))
      request = session.fold(base)(s => base.addHeader(Header.Cookie(NonEmptyChunk(Cookie.Request(EdgeApi.sessionCookieName, s.cookie)))))
      response <- Client.batched(request)
      text <- response.body.asString
    yield response.status -> text.fromJson[Json.Obj].getOrElse(Json.Obj())

  private def error(result: (Status, Json.Obj)): Option[String] = result._2.str("error")

  extension (json: Json.Obj)
    def str(name: String): Option[String] = json.fields.collectFirst { case (`name`, Json.Str(value)) => value }
    def obj(name: String): Option[Json.Obj] = json.fields.collectFirst { case (`name`, value: Json.Obj) => value }
    def arr(name: String): Chunk[Json.Obj] =
      json.fields.collectFirst { case (`name`, Json.Arr(elements)) => elements.collect { case o: Json.Obj => o } }.getOrElse(Chunk.empty)

  private def signIn(f: PartnerEdgeFixture, person: PartnerEdgeFixture.Person): ZIO[OAuthClient, Throwable, EdgeSession] =
    ZIO.serviceWithZIO[OAuthClient](auth =>
      f.api.browserLogin(auth, f.consoleClientId, person.login, person.password, consent = Some(Set("openid", "phone", "email"))),
    )

  /** What the operator sees of the partner's approvals, waiting. */
  private def waitingApprovals(central: CentralApi, f: PartnerEdgeFixture) =
    central.get(
      "/configuration/partners/approvals",
      "tenantId" -> f.tenantId,
      "status" -> "waiting",
      "partnerId" -> f.partnerId,
    ).flatMap(_.items("approvals"))

  def spec = suite("partner edge")(
    test("the edge is enrolled and told by central who it is: the console's sign-in and sign-out paths lead to its preset") {
      for
        f <- fixture
        client <- ZIO.service[Client]
        login <-
          ZIO.fromEither(URL.decode(s"${f.baseUrl}/partner/login")).mapError(RuntimeException(_)).flatMap(url => Client.batched(Request.get(url)))
        logout <-
          ZIO.fromEither(URL.decode(s"${f.baseUrl}/partner/logout")).mapError(RuntimeException(_)).flatMap(url => Client.batched(Request.get(url)))
        page <- ZIO.fromEither(URL.decode(s"${f.baseUrl}/partner/")).mapError(RuntimeException(_)).flatMap(url => Client.batched(Request.get(url)))
        body <- page.body.asString
      yield assertTrue(
        login.status == Status.SeeOther,
        login.header(Header.Location).exists(_.url.encode == s"/login/${f.consoleClientId}"),
        logout.header(Header.Location).exists(_.url.encode == s"/logout/${f.consoleClientId}"),
        page.status == Status.Ok,
        body.contains("partner console"),
      )
    },
    test("it syncs only its own partner's data: its console's preset is served, another partner's is not") {
      for
        f <- fixture
        central <- ZIO.service[CentralApi]
        otherId <- CentralApi.id("e2e-partner-other")
        _ <- PartnerEdgeFixture.createPartner(central, f.tenantId, otherId, s"$otherId-edge", s"https://login.$otherId.example")
        ours <- f.api.login(f.consoleClientId)
        // given the time the edge takes to sync (every two seconds), which it would take were it to carry another partner's presets
        _ <- ZIO.sleep(5.seconds)
        theirs <- f.api.login(s"$otherId-console")
      yield assertTrue(ours.status == Status.SeeOther, theirs.status == Status.NotFound)
    },
    test("no session is 401 with the path to sign in at, and a cookie of another client's session is not a console session") {
      for
        f <- fixture
        anonymous <- call(f, None, Method.GET, "/partner/api/me")
        forged <- call(f, Some(EdgeSession(s"${f.consoleClientId}:not.a.token", Response.ok)), Method.GET, "/partner/api/me")
      yield assertTrue(
        anonymous._1 == Status.Unauthorized,
        error(anonymous).contains("unauthenticated"),
        anonymous._2.str("loginPath").contains("/partner/login"),
        forged._1 == Status.Unauthorized,
      )
    },
    test("the first administrator named in the configuration signs in through the console client and is admitted as admin") {
      for
        f <- fixture
        session <- signIn(f, f.admin)
        me <- call(f, Some(session), Method.GET, "/partner/api/me")
      yield assertTrue(
        me._1 == Status.Ok,
        me._2.obj("staff").flatMap(_.str("level")).contains("admin"),
        me._2.obj("partner").flatMap(_.str("id")).contains(f.partnerId),
        me._2.obj("edge").flatMap(_.str("central")).contains("ok"),
        me._2.obj("console").flatMap(_.str("clientId")).contains(f.consoleClientId),
      )
    },
    test("a person who signs in and was never invited is 403 not_a_member, told the number they used masked") {
      for
        f <- fixture
        auth <- ZIO.service[OAuthClient]
        stranger <- PartnerEdgeFixture.person(auth, 2)
        session <- signIn(f, stranger)
        me <- call(f, Some(session), Method.GET, "/partner/api/me")
        clients <- call(f, Some(session), Method.GET, "/partner/api/clients")
      yield assertTrue(
        me._1 == Status.Forbidden,
        error(me).contains("not_a_member"),
        me._2.str("identifier").exists(masked => masked.contains("•") && !masked.contains(stranger.phone)),
        clients._1 == Status.Forbidden,
      )
    },
    test("an administrator invites a viewer by phone number, who signs in, reads, and may not write") {
      for
        f <- fixture
        auth <- ZIO.service[OAuthClient]
        admin <- signIn(f, f.admin)
        viewer <- PartnerEdgeFixture.person(auth, 3)
        invited <- call(
          f,
          Some(admin),
          Method.POST,
          "/partner/api/staff",
          Some(Json.Obj("identifier" -> Json.Str(viewer.phone), "level" -> Json.Str("viewer"))),
        )
        session <- signIn(f, viewer)
        me <- call(f, Some(session), Method.GET, "/partner/api/me")
        read <- call(f, Some(session), Method.GET, "/partner/api/clients")
        write <- call(f, Some(session), Method.POST, "/partner/api/clients", Some(Json.Obj()))
        team <- call(f, Some(session), Method.GET, "/partner/api/staff")
      yield assertTrue(
        invited._1 == Status.Created,
        !invited._2.toJson.contains(viewer.phone),
        me._2.obj("staff").flatMap(_.str("level")).contains("viewer"),
        read._1 == Status.Ok,
        write._1 == Status.Forbidden,
        error(write).contains("insufficient_level"),
        team._1 == Status.Forbidden,
      )
    },
    test("a client created in the console is pending, the operator sees the request, and its approval reaches the console and the edge") {
      for
        f <- fixture
        central <- ZIO.service[CentralApi]
        admin <- signIn(f, f.admin)
        clientId = s"${f.partnerId}-shop"
        created <- call(
          f,
          Some(admin),
          Method.POST,
          "/partner/api/clients",
          Some(
            Json.Obj(
              "id" -> Json.Str(clientId),
              "kind" -> Json.Str("web"),
              "clientName" -> Json.Str("Partner Shop"),
              "redirectUris" -> Json.Arr(Json.Str(s"https://shop.${f.partnerId}.example/callback")),
              "scope" -> Json.Arr(Json.Str("openid")),
              "accessTokenTtl" -> Json.Num(600),
              "refreshTokenTtl" -> Json.Num(3600),
              "authMethod" -> Json.Str("tls_client_auth"),
            ),
          ),
        )
        waiting <- PartnerEdgeFixture.eventually(waitingApprovals(central, f))(_.exists(_.str("targetId").contains(clientId)))
        approval = waiting.find(_.str("targetId").contains(clientId))
        listed <- call(f, Some(admin), Method.GET, "/partner/api/clients")
        before = listed._2.arr("clients").find(_.str("id").contains(clientId))
        approved <- approval.flatMap(_.str("id")).fold(ZIO.fail(RuntimeException(s"no approval for $clientId: $waiting")))(id =>
          central.post("/configuration/partners/approvals/approve", Json.Obj("id" -> Json.Str(id), "note" -> Json.Str("e2e"))),
        )
        after <- PartnerEdgeFixture.eventually(call(f, Some(admin), Method.GET, "/partner/api/clients"))(r =>
          r._2.arr("clients").exists(c => c.str("id").contains(clientId) && c.obj("approval").flatMap(_.str("status")).contains("approved")),
        )
        // the preset the approval created is synced to the partner's edge, and auth, which is asked to reload (non-prod) at once, knows the
        // client: the login ends in auth's `/authorize` only when the edge pushed the request for it with the group's certificate
        preset <-
          PartnerEdgeFixture.eventually(central.postEmpty("/service/configuration/sync") *> f.api.login(clientId))(_.status == Status.SeeOther)
      yield assertTrue(
        created._1 == Status.Accepted,
        before.flatMap(_.obj("approval")).flatMap(_.str("status")).contains("pending"),
        approval.isDefined,
        approved.status.isSuccess,
        after._2.arr("clients").exists(c => c.str("id").contains(clientId) && c.obj("approval").flatMap(_.str("status")).contains("approved")),
        after._2.arr("clients").find(_.str("id").contains(clientId)).flatMap(_.str("loginPath")).contains(s"/login/$clientId"),
        after._2.arr("clients").find(_.str("id").contains(clientId)).flatMap(_.obj("fixed")).flatMap(_.str("authMethod")).contains("tls_client_auth"),
        preset.status == Status.SeeOther,
      )
    },
    test("a revocation is made through central with the staff member's token, and shows in the partner's audit trail") {
      for
        f <- fixture
        admin <- signIn(f, f.admin)
        revoked <- call(
          f,
          Some(admin),
          Method.POST,
          "/partner/api/revocations",
          Some(Json.Obj(
            "by" -> Json.Str("client"),
            "value" -> Json.Str(s"${f.partnerId}-shop"),
            "clients" -> Json.Arr(Json.Str(s"${f.partnerId}-shop")),
            "edges" -> Json.Arr(),
          )),
        )
        unsupported <- call(
          f,
          Some(admin),
          Method.POST,
          "/partner/api/revocations",
          Some(Json.Obj("by" -> Json.Str("sid"), "value" -> Json.Str("s"), "clients" -> Json.Arr(Json.Str("x")), "edges" -> Json.Arr())),
        )
        audit <- PartnerEdgeFixture.eventually(call(f, Some(admin), Method.GET, "/partner/api/audit"))(r =>
          r._2.arr("events").exists(_.str("action").exists(_.contains("partner-revocation"))),
        )
        me <- call(f, Some(admin), Method.GET, "/partner/api/me")
      yield assertTrue(
        revoked._1 == Status.Accepted,
        unsupported._1 == Status.BadRequest,
        audit._2.arr("events").exists(event =>
          event.str(
            "action",
          ).exists(_.contains("partner-revocation")) && event.obj("actor").flatMap(_.str("sub")) == me._2.obj("staff").flatMap(_.str("sub")),
        ),
      )
    },
    test("a suspended partner is 403 partner_suspended on a management call, and its edge serves again once it is resumed") {
      for
        f <- fixture
        central <- ZIO.service[CentralApi]
        admin <- signIn(f, f.admin)
        suspended <- central.post("/configuration/partners/suspend", Json.Obj("id" -> Json.Str(f.partnerId), "note" -> Json.Str("e2e")))
        refused <- PartnerEdgeFixture.eventually(call(f, Some(admin), Method.GET, "/partner/api/edges"))(_._1 == Status.Forbidden)
        resumed <- central.post("/configuration/partners/resume", Json.Obj("id" -> Json.Str(f.partnerId)))
        served <- PartnerEdgeFixture.eventually(call(f, Some(admin), Method.GET, "/partner/api/edges"))(_._1 == Status.Ok)
      yield assertTrue(
        suspended.status == Status.NoContent,
        refused._1 == Status.Forbidden,
        error(refused).contains("partner_suspended"),
        resumed.status == Status.NoContent,
        served._1 == Status.Ok,
        served._2.arr("edges").exists(_.str("id").contains(f.edgeId)),
      )
    },
  )
