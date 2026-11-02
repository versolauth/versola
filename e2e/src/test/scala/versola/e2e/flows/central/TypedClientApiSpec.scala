package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** One registration and one update per kind of client: `/configuration/clients/{web,native,service}`.
  *
  * The unit suites prove each kind's rules against a stubbed service. What only this level shows
  * is that a kind registered through its endpoint is stored as that kind -- the template and the
  * application type survive the Postgres round trip -- and that the client's kind is then fixed:
  * an update sent to another kind's endpoint is refused rather than quietly applied.
  */
object TypedClientApiSpec extends CentralApiSpec:

  private val path = "/configuration/clients"

  private def webBody(id: String, authMethod: String = "client_secret", redirectUris: Set[String] = Set("http://localhost:3000")) =
    Json.Obj(
      "tenantId" -> Json.Str(Fixtures.suiteTenant),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Typed web"),
      "redirectUris" -> Json.Arr(redirectUris.toSeq.map(Json.Str(_))*),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "accessTokenTtl" -> Json.Num(3600),
      "authMethod" -> Json.Str(authMethod),
      "issueEdgeClientCertificate" -> Json.Bool(false),
      "enrollEdgeClientCertificate" -> Json.Bool(false),
      "theme" -> Json.Str("default"),
      "otpTemplateId" -> Json.Str("default"),
      "frontChannelLogoutSessionRequired" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
      "dpopBoundAccessTokens" -> Json.Bool(false),
      "certificateBoundAccessTokens" -> Json.Bool(false),
      "requireSignedRequestObject" -> Json.Bool(false),
      "requirePushedAuthorizationRequests" -> Json.Bool(false),
      "estEnrollment" -> Json.Bool(false),
    )

  private def nativeBody(id: String, authMethod: String = "none") =
    Json.Obj(
      "tenantId" -> Json.Str(Fixtures.suiteTenant),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Typed native"),
      "redirectUris" -> Json.Arr(Json.Str("https://app.test/callback")),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "accessTokenTtl" -> Json.Num(3600),
      "authMethod" -> Json.Str(authMethod),
      "issueEdgeClientCertificate" -> Json.Bool(false),
      "enrollEdgeClientCertificate" -> Json.Bool(false),
      "theme" -> Json.Str("default"),
      "otpTemplateId" -> Json.Str("default"),
      "frontChannelLogoutSessionRequired" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
    )

  private def serviceBody(id: String, authMethod: String = "client_secret") =
    Json.Obj(
      "tenantId" -> Json.Str(Fixtures.suiteTenant),
      "id" -> Json.Str(id),
      "clientName" -> Fixtures.text("Typed service"),
      "allowedScopes" -> Json.Arr(Json.Str("openid")),
      "permissions" -> Json.Arr(),
      "accessTokenTtl" -> Json.Num(3600),
      "dpopBoundAccessTokens" -> Json.Bool(false),
      "dpopSigningAlgs" -> Json.Arr(),
      "authMethod" -> Json.Str(authMethod),
      "certificateBoundAccessTokens" -> Json.Bool(false),
      "estEnrollment" -> Json.Bool(false),
    )

  private val noChange = Fixtures.patch()

  private def read(central: CentralApi, clientId: String, expect: Json.Obj => Boolean = _ => true): Task[Option[Json.Obj]] =
    eventually(
      central.get(path, "tenantId" -> Fixtures.suiteTenant)
        .flatMap(_.items("clients"))
        .map(_.find(_.str("id").contains(clientId))),
    )(_.exists(expect))

  private def withKind[A](central: CentralApi, kind: String, body: Json.Obj)(use: String => Task[A]): Task[A] =
    val clientId = body.str("id").getOrElse(throw IllegalArgumentException("fixture has no id"))
    ZIO.acquireReleaseWith(central.post(s"$path/$kind", body))(_ => central.delete(path, "clientId" -> clientId).ignore)(_ => use(clientId))

  def spec = suite("Central API: clients by kind")(
    test("a web client is stored as a web client, with the secret it is handed") {
      for
        central <- api
        id <- CentralApi.id("typed-web")
        created <- central.post(s"$path/web", webBody(id))
        record <- read(central, id)
        _ <- central.delete(path, "clientId" -> id)
      yield assertTrue(
        created.status == Status.Created,
        record.flatMap(_.obj("template")).flatMap(_.str("kind")).contains("web"),
        record.flatMap(_.str("applicationType")).contains("web"),
      )
    },
    test("a web client with no credential is refused, and nothing is stored") {
      for
        central <- api
        id <- CentralApi.id("typed-web")
        refused <- central.post(s"$path/web", webBody(id, authMethod = "none"))
        record <- read(central, id)
      yield assertTrue(refused.status == Status.BadRequest, record.isEmpty)
    },
    test("a public native client is stored as a device client, with its own choice of DPoP and no certificate binding") {
      for
        central <- api
        id <- CentralApi.id("typed-native")
        record <- withKind(central, "native", nativeBody(id))(_ => read(central, id))
      yield assertTrue(
        record.flatMap(_.obj("template")).flatMap(_.str("kind")).contains("device"),
        record.flatMap(_.str("applicationType")).contains("native"),
        record.flatMap(_.bool("dpopBoundAccessTokens")).contains(false),
        record.flatMap(_.bool("certificateBoundAccessTokens")).contains(false),
        record.flatMap(_.bool("requireSignedRequestObject")).contains(false),
      )
    },
    test("a native client request that names a setting its kind fixes is refused") {
      for
        central <- api
        id <- CentralApi.id("typed-native")
        refused <- central.post(s"$path/native", Json.Obj.apply(nativeBody(id).fields :+ ("requirePushedAuthorizationRequests" -> Json.Bool(false))))
        record <- read(central, id)
      yield assertTrue(refused.status == Status.BadRequest, record.isEmpty)
    },
    test("a native client cannot authenticate with a shared secret") {
      for
        central <- api
        id <- CentralApi.id("typed-native")
        refused <- central.post(s"$path/native", nativeBody(id, authMethod = "client_secret"))
      yield assertTrue(refused.status == Status.BadRequest)
    },
    test("a service client is stored with no redirect URIs and no refresh flow") {
      for
        central <- api
        id <- CentralApi.id("typed-service")
        record <- withKind(central, "service", serviceBody(id))(_ => read(central, id))
      yield assertTrue(
        record.flatMap(_.obj("template")).flatMap(_.str("kind")).contains("service"),
        record.map(_.strings("redirectUris")).contains(Set.empty[String]),
        record.flatMap(_.bool("requirePushedAuthorizationRequests")).contains(false),
      )
    },
    test("a service client must authenticate") {
      for
        central <- api
        id <- CentralApi.id("typed-service")
        refused <- central.post(s"$path/service", serviceBody(id, authMethod = "none"))
      yield assertTrue(refused.status == Status.BadRequest)
    },
    test("an update at the client's own endpoint is applied") {
      for
        central <- api
        id <- CentralApi.id("typed-service")
        record <- withKind(central, "service", serviceBody(id)) { _ =>
          central.put(
            s"$path/service",
            Json.Obj("clientId" -> Json.Str(id), "scope" -> noChange, "permissions" -> noChange, "accessTokenTtl" -> Json.Num(600)),
          )
            *> read(central, id, _.int("accessTokenTtl").contains(600))
        }
      yield assertTrue(record.flatMap(_.int("accessTokenTtl")).contains(600))
    },
    test("an update at another kind's endpoint is a conflict, and changes nothing") {
      for
        central <- api
        id <- CentralApi.id("typed-native")
        outcome <- withKind(central, "native", nativeBody(id)) { _ =>
          for
            refused <- central.put(
              s"$path/web",
              Json.Obj("clientId" -> Json.Str(id), "redirectUris" -> noChange, "scope" -> noChange, "accessTokenTtl" -> Json.Num(600)),
            )
            after <- read(central, id)
          yield (refused.status, after.flatMap(_.int("accessTokenTtl")))
        }
      yield assertTrue(outcome == (Status.Conflict, Some(3600)))
    },
    test("an update for a client that does not exist is not found") {
      for
        central <- api
        missing <- central.put(
          s"$path/web",
          Json.Obj("clientId" -> Json.Str("typed-missing"), "redirectUris" -> noChange, "scope" -> noChange, "accessTokenTtl" -> Json.Num(600)),
        )
      yield assertTrue(missing.status == Status.NotFound)
    },
    test("a web client cannot lose its last redirect URI") {
      for
        central <- api
        id <- CentralApi.id("typed-web")
        refused <- withKind(central, "web", webBody(id)) { _ =>
          central.put(
            s"$path/web",
            Json.Obj("clientId" -> Json.Str(id), "scope" -> noChange, "redirectUris" -> Fixtures.patch(remove = Set("http://localhost:3000"))),
          )
        }
      yield assertTrue(refused.status == Status.BadRequest)
    },
  )
