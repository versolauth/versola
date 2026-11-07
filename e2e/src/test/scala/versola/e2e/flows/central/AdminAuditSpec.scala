package versola.e2e.flows.central

import versola.e2e.support.*
import zio.*
import zio.http.{Body, Client, Header, MediaType, Method, Path, QueryParams, Request, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.util.UUID

/** What an administrator does through central's API is in its audit log (#469), with who did it as edge told central.
  *
  * The first cases go through edge, which is the only way the actor headers get into a call: the provisioning client's DPoP
  * session is what the stack has for an authenticated caller of the `central` resource. The caller of such a session is a client,
  * not a user (its token's subject is the client), so the row says `client`; a person signing in as an administrator is an
  * `admin` by the same headers, which the unit specs of the hook cover. The rows are read from central's table: no endpoint lists
  * what was done to an object that is not a user.
  *
  * The last cases read the per-user report, as an administrator whom edge identified (the header, with no edge in between).
  */
object AdminAuditSpec extends ZIOSpec[Client & E2EConfig & EdgeApi & OAuthClient & CentralApi]:

  override val bootstrap: ZLayer[Any, Any, Client & E2EConfig & EdgeApi & OAuthClient & CentralApi] =
    (E2EConfig.live ++ Client.default) >+> (EdgeApi.live ++ OAuthClient.live ++ CentralApi.live)

  // The writer of the audit log rests up to a second between batches, so what a test reads back has to be waited for.
  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] = Chunk(TestAspect.withLiveClock)

  private def serviceBody(id: String) =
    Json.Obj(
      "tenantId" -> Json.Str(Fixtures.suiteTenant),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Audit service"),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "permissions" -> Json.Arr(),
      "accessTokenTtl" -> Json.Num(3600),
      "dpopBoundAccessTokens" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
      "authMethod" -> Json.Str("client_secret"),
      "certificateBoundAccessTokens" -> Json.Bool(false),
      "estEnrollment" -> Json.Bool(false),
    )

  private def serviceUpdate(id: String) =
    Json.Obj(
      "clientId" -> Json.Str(id),
      "scope" -> Fixtures.patch(),
      "permissions" -> Fixtures.patch(),
      "accessTokenTtl" -> Json.Num(600),
    )

  /** The rows about `targetId` once `ready` holds of them (the last read if it never does, so that the assertion says what was there). */
  private def rows(targetId: String)(ready: List[CentralDatabase.AuditRow] => Boolean) =
    CentralDatabase.auditEvents(targetId)
      .repeat(Schedule.spaced(200.millis) *> Schedule.recurUntil[List[CentralDatabase.AuditRow]](ready))
      .timeout(20.seconds)
      .someOrElseZIO(CentralDatabase.auditEvents(targetId))

  /** What Postgres prints for an `inet` without its mask: an IPv4 address or an IPv6 one. */
  private val forgedAddress = "198.51.100.77"

  private val addressText = raw"([0-9]{1,3}(\.[0-9]{1,3}){3}|[0-9a-f:]+)".r

  private def detail(row: CentralDatabase.AuditRow): Json.Obj = row.details.fromJson[Json.Obj].getOrElse(Json.Obj())

  private def report(central: CentralApi, userId: String, query: (String, String)*) =
    central.get("/users/audit", (("id" -> userId) +: query)*)

  private def adminActions(result: ApiResult): Task[Chunk[Json.Obj]] =
    result.obj.map(_.obj("summary").fold(Chunk.empty[Json.Obj])(_.objs("adminActions")))

  /** Polls the report until `ready` holds of it (the last answer if it never does). */
  private def reportWhere(central: CentralApi, userId: String)(ready: ApiResult => Task[Boolean]) =
    report(central, userId)
      .repeat(Schedule.spaced(250.millis) *> Schedule.recurUntilZIO[Any, ApiResult](result => ready(result).orElseSucceed(false)))
      .timeout(20.seconds)
      .someOrElseZIO(report(central, userId))

  def spec = suite("Admin audit")(
    test("a change made through edge is recorded with who edge says the caller is, the names of the fields it carried and no secret") {
      for
        c <- ZIO.service[E2EConfig]
        auth <- ZIO.service[OAuthClient]
        edge <- ZIO.service[EdgeApi]
        session <- ProvisionerCredential.session(auth, c, List("resource://central"))
        id <- CentralApi.id("audit-client")
        created <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/service", session, Some(serviceBody(id)))
        secret = created.obj.map(_.str("secret")).orElseSucceed(None)
        updated <- edge.proxyDpop(Method.PUT, "central", "/configuration/clients/service", session, Some(serviceUpdate(id)))
        again <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/service", session, Some(serviceBody(id)))
        deleted <- edge.proxyDpop(Method.DELETE, "central", "/configuration/clients", session, query = List("clientId" -> id))
        issued <- secret
        stored <- rows(id)(rows => rows.map(_.kind).count(_ == "admin.client.create") >= 2 && rows.exists(_.kind == "admin.client.delete"))
      yield
        def of(kind: String, outcome: String) = stored.filter(row => row.kind == kind && row.outcome == outcome)
        val update = of("admin.client.update", "success").headOption
        assertTrue(
          created.status == Status.Created,
          updated.status.isSuccess,
          !again.status.isSuccess,
          deleted.status.isSuccess,
          of("admin.client.create", "success").size == 1,
          of("admin.client.update", "success").size == 1,
          of("admin.client.delete", "success").size == 1,
          // the second registration of the id is a failure, with the status central answered it with
          of("admin.client.create", "failure").size == 1,
          of("admin.client.create", "failure").flatMap(row => detail(row).get("status")).headOption.contains(Json.Num(again.status.code)),
          // the secret the creation handed back is an event of its own
          of("admin.secret.revealed", "success").size == 1,
          // who: the client the token was issued to, as edge verified it, and its tenant
          stored.forall(row => row.actorType == "client" && row.actorId.contains(c.provisionerClientId)),
          stored.forall(_.tenantId.exists(_.nonEmpty)),
          // from where: the address edge found for the caller (its `client-ip` setting), which central keeps in `ip`
          stored.forall(_.ip.exists(address => addressText.matches(address))),
          stored.forall(row => row.targetType.contains("client") && row.targetId.contains(id)),
          // what changed: the names of the fields of the request, never their values; the one that names the client is not a change
          update.flatMap(row => detail(row).get("changed")).contains(Json.Arr(
            Json.Str("accessTokenTtl"),
            Json.Str("permissions"),
            Json.Str("scope"),
          )),
          update.exists(row => detail(row).get("path").contains(Json.Str("/configuration/clients/service"))),
          // and what must never be there
          issued.nonEmpty,
          !stored.exists(row => issued.exists(row.details.contains) || issued.exists(s => row.reason.exists(_.contains(s)))),
          stored.map(_.severity).forall(Set("info", "notice").contains),
        )
    },
    test("a request that carries the actor's own parameters is recorded with the token's actor: edge replaces them") {
      for
        c <- ZIO.service[E2EConfig]
        auth <- ZIO.service[OAuthClient]
        edge <- ZIO.service[EdgeApi]
        session <- ProvisionerCredential.session(auth, c, List("resource://central"))
        id <- CentralApi.id("audit-spoof")
        spoof = "00000000-0000-0000-0000-00000000dead"
        forgedIp = "198.51.100.66"
        created <- edge.proxyDpop(
          Method.POST,
          "central",
          "/configuration/clients/service",
          session,
          Some(serviceBody(id)),
          query = List(
            "actorId" -> spoof,
            "actorTenantId" -> "someone-elses",
            "actorClientId" -> "someone-else",
            "actorSessionId" -> "forged",
            "actorIp" -> forgedIp,
          ),
        )
        _ <- edge.proxyDpop(Method.DELETE, "central", "/configuration/clients", session, query = List("clientId" -> id))
        stored <- rows(id)(_.exists(_.kind == "admin.client.delete"))
      yield assertTrue(
        created.status == Status.Created,
        stored.nonEmpty,
        // the token's actor, not the one the caller wrote
        stored.forall(row => row.actorType == "client" && row.actorId.contains(c.provisionerClientId)),
        stored.forall(row => !row.actorId.contains(spoof) && !row.tenantId.contains("someone-elses")),
        stored.forall(row => !row.ip.contains(forgedIp)),
      )
    },
    test("a call that reaches central around edge and states an actor is recorded as a service's: only what an edge signed is believed") {
      for
        c <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        id <- CentralApi.id("audit-forged")
        forged = "00000000-0000-0000-0000-00000000f0f0"
        base <- ZIO.fromEither(URL.decode(s"${c.centralUrl}/configuration/clients/service")).mapError(RuntimeException(_))
        request = Request
          .post(
            base.addQueryParams(QueryParams("actorId" -> forged, "actorTenantId" -> "someone-elses", "actorIp" -> forgedAddress)),
            Body.fromString(serviceBody(id).toJson),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Authorization.Basic("central", c.resourceSecret))
        created <- client.batched(request)
        _ <- client.batched(
          Request.delete(base.copy(path = Path.decode("/configuration/clients")).addQueryParams(QueryParams("clientId" -> id, "actorId" -> forged)))
            .addHeader(Header.Authorization.Basic("central", c.resourceSecret)),
        )
        stored <- rows(id)(_.exists(_.kind == "admin.client.delete"))
      yield assertTrue(
        // the call is served: nothing is refused unless the deployment asks for it (`audit.require-actor`)
        created.status == Status.Created,
        stored.nonEmpty,
        // but whoever it says it is, it is a service with no id, no tenant and no address
        stored.forall(row => row.actorType == "service" && row.actorId.isEmpty && !row.tenantId.contains("someone-elses")),
        stored.forall(row => !row.ip.contains(forgedAddress)),
      )
    },
    test("rotating a client secret is of high severity and reveals a secret once more") {
      for
        c <- ZIO.service[E2EConfig]
        auth <- ZIO.service[OAuthClient]
        edge <- ZIO.service[EdgeApi]
        session <- ProvisionerCredential.session(auth, c, List("resource://central"))
        id <- CentralApi.id("audit-secret")
        _ <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/service", session, Some(serviceBody(id)))
        rotated <- edge.proxyDpop(Method.POST, "central", "/configuration/clients/rotate-secret", session, query = List("clientId" -> id))
        _ <- edge.proxyDpop(Method.DELETE, "central", "/configuration/clients", session, query = List("clientId" -> id))
        stored <- rows(id)(rows => rows.exists(_.kind == "admin.client-secret.action:rotate-secret") && rows.exists(_.kind == "admin.client.delete"))
      yield assertTrue(
        rotated.status.isSuccess,
        stored.filter(_.kind == "admin.client-secret.action:rotate-secret").map(row => (row.outcome, row.severity)) == List(("success", "high")),
        // the new secret is revealed once for the creation and once for the rotation
        stored.count(_.kind == "admin.secret.revealed") == 2,
      )
    },
    test("what bootstrap created on the first start of the deployment is in the log, as the system's") {
      for stored <- rows(Fixtures.defaultTenant)(_.exists(_.kind == "system.bootstrap"))
      yield assertTrue(
        stored.exists(row => row.kind == "system.bootstrap" && row.actorType == "system" && row.actorId.isEmpty && row.outcome == "success"),
        stored.filter(_.kind == "system.bootstrap").forall(_.targetType.contains("tenant")),
      )
    },
    test("the report of a user holds what administrators did to them, and asking for it is recorded") {
      for
        central <- ZIO.service[CentralApi]
        admin = UUID.randomUUID().toString
        asAdmin = central.withQuery("actorId", admin)
        login <- CentralApi.login("audit")
        renamed <- CentralApi.login("audit-renamed")
        created <- asAdmin.post("/users", Fixtures.user(login = Some(login)))
        id <- created.stringAt("id")
        patched <- asAdmin.patch("/users", Json.Obj("id" -> Json.Str(id), "login" -> Json.Str(renamed)))
        first <- reportWhere(asAdmin, id)(result =>
          adminActions(result).map(actions =>
            actions.exists(_.str("kind").contains("admin.user.update")) && actions.exists(_.str("kind").contains("admin.user.create")),
          ),
        )
        body <- first.obj
        actions <- adminActions(first)
        events = body.objs("events")
        // a second look: the first one is in it, among the rows and not among the administrators' changes
        second <- reportWhere(asAdmin, id)(result => result.obj.map(_.objs("events").exists(_.str("kind").contains("admin.audit.queried"))))
        asked <- second.obj.map(_.objs("events"))
        changes <- adminActions(second)
      yield assertTrue(
        created.status == Status.Created,
        patched.status.isSuccess,
        first.status == Status.Ok,
        body.str("userId").contains(id),
        // auth's part is in it when auth serves the endpoint, and the report says so when it does not
        body.get("partial").forall:
          case Json.Arr(parts) => parts.forall(_ == Json.Str("auth"))
          case _ => false
        ,
        actions.filter(_.str("kind").contains("admin.user.update")).headOption.exists(a =>
          a.str("actorId").contains(admin) && a.get("changed").contains(Json.Arr(Json.Str("login"))),
        ),
        actions.exists(a => a.str("kind").contains("admin.user.create") && a.str("actorId").contains(admin) && a.str("targetId").contains(id)),
        events.filter(_.str("source").contains("central")).flatMap(_.str("kind")).toSet.contains("admin.user.update"),
        // newest first
        events.flatMap(_.str("at")) == events.flatMap(_.str("at")).sorted(using Ordering.String.reverse),
        body.obj("window").exists(w => w.str("from").nonEmpty && w.str("to").nonEmpty && w.str("retentionFrom").nonEmpty),
        asked.exists(a => a.str("kind").contains("admin.audit.queried") && a.str("actorId").contains(admin) && a.str("targetId").contains(id)),
        changes.nonEmpty && changes.forall(a =>
          a.str("kind").exists(_.startsWith("admin.")) && !a.str("kind").exists(kind => kind.endsWith(".queried") || kind.endsWith(".viewed")),
        ),
      )
    },
    test("a report is refused for a user that is no UUID, a page size out of range and a caller without the central credential") {
      for
        central <- ZIO.service[CentralApi]
        user <- central.get("/users/audit", "id" -> "someone")
        size <- central.get("/users/audit", "id" -> UUID.randomUUID().toString, "limit" -> "0")
        cursor <- central.get("/users/audit", "id" -> UUID.randomUUID().toString, "cursor" -> "not-a-cursor")
        anonymous <- central.anonymous.get("/users/audit", "id" -> UUID.randomUUID().toString)
      yield assertTrue(
        user.status == Status.BadRequest,
        size.status == Status.BadRequest,
        cursor.status == Status.BadRequest,
        anonymous.status == Status.Unauthorized,
      )
    },
  )
