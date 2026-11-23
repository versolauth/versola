package versola.edge

import io.opentelemetry.api
import versola.edge.model.EdgeId
import versola.util.Secret
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import java.security.KeyPairGenerator
import java.util.Base64

/** The pages of a sign-in served by edge (versolauth/versola#1044): which cell a browser's request is sent to by what
  * it carries, what is served and what is not, and what is passed on. */
object IssuerFrontSpec extends ZIOSpecDefault:

  private def url(value: String) = URL.decode(value).toOption.get

  private def cell(id: String, state: EdgeCellState = EdgeCellState.Open, weight: Int = 100) =
    EdgeCell(id, "almaty", "kz", url(s"http://auth.internal:9003/$id"), None, None, state, weight)

  // One open cell, so that placement has one answer; the second is reached only by what names it.
  private val topology = CellTopology(Vector(cell("kz-1"), cell("kz-2", EdgeCellState.Closed, 0)), region = None)

  private def b64(json: String) = Base64.getUrlEncoder.withoutPadding.encodeToString(json.getBytes("UTF-8"))

  private def idTokenOf(sid: String) = s"${b64("""{"alg":"RS256"}""")}.${b64(s"""{"sid":"$sid"}""")}.c2ln"

  private def routing(path: String, cookies: Map[String, String] = Map.empty, query: Map[String, String] = Map.empty) =
    IssuerFront.routingOf(path.split('/').toList, cookies.get, query.get)

  private def where(routing: CellRouting, cells: CellTopology = topology) =
    IssuerFront.cellOf(cells, routing, 0.0).map(_.map(_.id))

  private val keyPair =
    val gen = KeyPairGenerator.getInstance("RSA").nn
    gen.initialize(2048)
    gen.generateKeyPair().nn

  private def config(mode: EdgeMode = EdgeMode.FirstParty) = EdgeConfig(
    id = EdgeId("edge-1"),
    keyId = "kid-1",
    privateKey = keyPair.getPrivate.nn,
    security = EdgeConfig.Security(
      tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
      edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
    ),
    central = EdgeConfig.CentralConfig(url = url("https://central.example")),
    versolaUrl = url("https://idp.example"),
    versolaInternalUrl = Some(url("http://auth.internal:9003/configured")),
    versolaInternalTrustedCertificates = Set.empty,
    edgeUrl = url("https://edge.example"),
    configurationCacheRefreshInterval = 5.minutes,
    mode = mode,
  )

  private val tracingLayer: ULayer[Tracing] =
    ZLayer.make[Tracing](Tracing.live(logAnnotated = false), OpenTelemetry.contextZIO, ZLayer.succeed(api.OpenTelemetry.noop().getTracer("test")))

  private def table(value: CellTopology): CellTable = new CellTable:
    override def current = ZIO.succeed(value)
    override def refreshNow = ZIO.unit

  /** The requests that reached a cell, newest first, and the front as a function of a request. `answer` is what the
    * cell named by the first path segment answers. */
  private def front(cells: CellTopology, mode: EdgeMode = EdgeMode.FirstParty)(answer: String => Response) =
    for
      seen <- Ref.make(List.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](request => seen.update(request :: _).as(answer(request.url.path.segments.headOption.getOrElse("")))).toRoutes,
      )
      client <- ZIO.service[Client]
      tracing <- tracingLayer.build
      environment = ZEnvironment[CellTable](table(cells)) ++ ZEnvironment[IssuerFront.Upstream](
        IssuerFront.Upstream(client),
      ) ++ ZEnvironment[EdgeConfig](config(mode)) ++ tracing
      call = (request: Request) => IssuerFront.routes.sandbox.runZIO(request).provideEnvironment(environment ++ ZEnvironment(Scope.global))
    yield (seen, call)

  private def form(fields: (String, String)*) =
    Body.fromURLEncodedForm(Form(fields.map(FormField.simpleField(_, _))*))

  private def post(path: String, fields: (String, String)*) =
    Request.post(url(s"https://idp.example/$path"), form(fields*)).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))

  def spec = suite("IssuerFront")(
    suite("which cell")(
      test("a step of a sign-in goes to the cell of its conversation cookie") {
        assertTrue(
          where(routing("challenge/otp", cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-2.abc.def"))) == Right(Some("kz-2")),
          // no cookie yet, or one from before cells: a new request, and the configured auth
          where(routing("challenge")) == Right(Some("kz-1")),
          where(routing("challenge", cookies = Map("__Host-SSO_CONVERSATION" -> "abc.def"))) == Right(None),
        )
      },
      test("a sign-in handed to another cell goes to the cell the handoff names, whatever cookie the browser still holds") {
        assertTrue(
          where(routing(
            "challenge/handoff",
            query = Map("handoff" -> "v1.kz-2.sealed"),
            cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-1.abc.def"),
          )) == Right(Some("kz-2")),
          // the parameter means nothing on another page
          where(routing(
            "challenge/otp",
            query = Map("handoff" -> "v1.kz-2.sealed"),
            cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-1.abc.def"),
          )) == Right(Some("kz-1")),
        )
      },
      test("an authorization request goes to the cell of its request_uri, else of the browser's session, else is placed") {
        assertTrue(
          where(routing("authorize", query = Map("request_uri" -> "urn:ietf:params:oauth:request_uri:v1.kz-2.abc"))) == Right(Some("kz-2")),
          where(routing("authorize", cookies = Map("SSO_SESSION" -> "v1.kz-2.abc.def"))) == Right(Some("kz-2")),
          // the request_uri wins: it is where the pushed request is
          where(
            routing(
              "authorize",
              query = Map("request_uri" -> "urn:ietf:params:oauth:request_uri:v1.kz-1.abc"),
              cookies = Map("SSO_SESSION" -> "v1.kz-2.abc.def"),
            ),
          ) == Right(Some("kz-1")),
          where(routing("authorize")) == Right(Some("kz-1")),
        )
      },
      test("a logout goes to the cell of the session cookie, else of the sid of the ID token hint") {
        assertTrue(
          where(routing("logout", cookies = Map("SSO_SESSION" -> "v1.kz-2.abc.def"))) == Right(Some("kz-2")),
          where(routing("logout", query = Map("id_token_hint" -> idTokenOf("v1.kz-2.AAAA")))) == Right(Some("kz-2")),
          where(routing("logout", query = Map("id_token_hint" -> "not-a-token"))) == Right(Some("kz-1")),
        )
      },
      test("a key that names no cell, or does not spell one, is placed like a request with none: auth answers it") {
        assertTrue(
          where(routing("challenge", cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-9.abc.def"))) == Right(Some("kz-1")),
          where(routing("authorize", cookies = Map("SSO_SESSION" -> "v1.kz-1"))) == Right(Some("kz-1")),
          where(routing("challenge", cookies = Map("__Host-SSO_CONVERSATION" -> "v1.KZ.abc.def"))) == Right(Some("kz-1")),
        )
      },
      test("with no cell registered everything goes to the configured auth, and with none open a new request has nowhere to go") {
        val shut = CellTopology(Vector(cell("kz-1", EdgeCellState.Draining)), None)
        assertTrue(
          where(routing("challenge", cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-2.abc.def")), CellTopology.empty) == Right(None),
          where(routing("authorize"), CellTopology.empty) == Right(None),
          where(routing("authorize"), shut) == Left(CellRoutingError.NoOpenCell),
          // what names the draining cell still reaches it
          where(routing("challenge", cookies = Map("__Host-SSO_CONVERSATION" -> "v1.kz-1.abc.def")), shut) == Right(Some("kz-1")),
        )
      },
    ),
    suite("forwarding")(
      test("the request is sent to the cell as it came, with the caller's cookies and body, and the answer is relayed whole") {
        for
          (seen, call) <- front(topology)(_ =>
            Response.text("page").status(Status.Ok).addHeader(Header.Custom("Set-Cookie", "__Host-SSO_CONVERSATION=v1.kz-2.x.y; Path=/challenge")),
          )
          response <- call(
            post(
              "challenge/otp?ui_locales=ru",
              "code" -> "123456",
              "csrf" -> "t",
            ).addHeader(Header.Custom("Cookie", "__Host-SSO_CONVERSATION=v1.kz-2.abc.def")),
          )
          sent <- seen.get.map(_.head)
          body <- sent.body.asString
          answer <- response.body.asString
        yield assertTrue(
          sent.url.path.encode == "/kz-2/challenge/otp",
          sent.url.queryParams.getAll("ui_locales").toList == List("ru"),
          sent.method == Method.POST,
          body.contains("code=123456") && body.contains("csrf=t"),
          sent.headers.get("Cookie").exists(_.contains("v1.kz-2.abc.def")),
          // edge's own session is not auth's to see
          !sent.headers.get("Cookie").exists(_.contains("EDGE_SESSION")),
          response.status == Status.Ok,
          answer == "page",
          response.headers.get("Set-Cookie").exists(_.contains("v1.kz-2.x.y")),
        )
      },
      test(
        "only the pages of a sign-in are served: the issuer's endpoints for clients and auth's internal routes are not found, and nothing is called",
      ) {
        for
          (seen, call) <- front(topology)(_ => Response.ok)
          statuses <- ZIO.foreach(
            List(
              "token",
              "par",
              "userinfo",
              "introspect",
              "revoke",
              ".well-known/openid-configuration",
              "users",
              "antifraud/lists",
              "service/configuration/sync",
              "logout/extra",
              "",
            ),
          )(path => call(Request.get(url(s"https://idp.example/$path"))).map(_.status))
          called <- seen.get
        yield assertTrue(statuses.forall(_ == Status.NotFound), called.isEmpty)
      },
      test("a partner's edge serves none of it") {
        for
          (seen, call) <- front(topology, EdgeMode.Partner)(_ => Response.ok)
          response <- call(Request.get(url("https://idp.example/authorize")))
          called <- seen.get
        yield assertTrue(response.status == Status.NotFound, called.isEmpty)
      },
      test("registered cells that none takes answer a new request 503, and a body over the limit is refused unread by a cell") {
        val shut = CellTopology(Vector(cell("kz-1", EdgeCellState.Draining)), None)
        for
          (seen, call) <- front(shut)(_ => Response.ok)
          placed <- call(Request.get(url("https://idp.example/authorize")))
          large <- call(Request.post(url("https://idp.example/challenge/otp"), Body.fromString("a" * (IssuerFront.BodyLimit + 1))))
          called <- seen.get
        yield assertTrue(placed.status == Status.ServiceUnavailable, large.status == Status.RequestEntityTooLarge, called.isEmpty)
      },
    ).provideLayer(TestClient.layer ++ Scope.default) @@ TestAspect.silentLogging,
  )
