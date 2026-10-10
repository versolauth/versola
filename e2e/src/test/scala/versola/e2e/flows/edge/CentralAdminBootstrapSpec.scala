package versola.e2e.flows.edge

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, Header, Status, URL}
import zio.test.*

/** #353: the bootstrapped `central-admin` client is FAPI 2.0-conformant on the stack
  * `gen-env.scala`'s `local` target writes -- RFC 8705 §2.1 `tls_client_auth`, with the
  * certificate central hands the edge fronting it, behind PAR.
  *
  * The `/login` case is what proves the wiring rather than the registration: a client that
  * requires PAR is pushed to auth's `/par` by edge before the browser is redirected, and `/par`
  * authenticates the client -- so a redirect carrying a `request_uri` means edge presented the
  * certificate through the TLS terminator and auth recognised it by its subject.
  */
object CentralAdminBootstrapSpec extends ZIOSpec[CentralApi & EdgeApi]:
  override val bootstrap: ZLayer[Any, Any, CentralApi & EdgeApi] =
    (E2EConfig.live ++ Client.default) >>> (CentralApi.live ++ EdgeApi.live)

  private val presetId = "central-admin"

  def spec = suite("central-admin bootstrap")(
    test("central-admin is registered as tls_client_auth, behind PAR, holding no secret") {
      for
        central <- ZIO.service[CentralApi]
        // Central lists from a cache a Postgres notification refreshes; bootstrap re-patches the
        // client on every start, so give the listing time to settle.
        read = central.get("/configuration/clients", "tenantId" -> Fixtures.defaultTenant).flatMap(_.items("clients"))
          .map(_.find(_.str("id").contains("central-admin")))
        admin <- read
          .repeat(Schedule.spaced(
            200.millis,
          ) *> Schedule.recurUntil[Option[zio.json.ast.Json.Obj]](_.exists(_.str("authMethod").contains("tls_client_auth"))))
          .timeout(10.seconds)
          .someOrElseZIO(read)
      yield assertTrue(
        admin.flatMap(_.str("authMethod")).contains("tls_client_auth"),
        admin.flatMap(_.bool("requirePushedAuthorizationRequests")).contains(true),
        admin.flatMap(_.bool("secretRotation")).contains(false),
        admin.flatMap(_.obj("mtlsAuth")).flatMap(_.str("subjectType")).contains("subject_dn"),
      )
    },
    test("/login/central-admin pushes the request to /par as that client and redirects with a request_uri") {
      for
        edgeApi <- ZIO.service[EdgeApi]
        started <- edgeApi.login(presetId)
        url <- ZIO.fromEither(URL.decode(started.header(Header.Location).map(_.url.encode).getOrElse("")))
      yield assertTrue(
        started.status == Status.SeeOther,
        url.queryParams.getAll("request_uri").headOption.exists(_.startsWith("urn:")),
        url.queryParams.getAll("client_id").contains("central-admin"),
      )
    },
  ) @@ TestAspect.withLiveClock
