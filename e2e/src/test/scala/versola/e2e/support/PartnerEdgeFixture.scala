package versola.e2e.support

import zio.*
import zio.http.{Client, Status}
import zio.json.*
import zio.json.ast.Json

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/** A partner's edge as an operator would run it: a second edge of the staged build, started with `mode = "partner"` against the staged
  * central and auth, enrolled as the partner's first edge, with its own console files and first administrator (#564).
  *
  * What the operator did is done through central's API (`POST /configuration/partners` creates the partner, its group, its console client
  * and preset, and the edge with its key); what the staged stack cannot do is the enrollment call itself, which is made to central's
  * plain-TLS name and so needs an `https` enrollment address the stack does not have. So the edge is handed the certificate that
  * enrollment would have given it, issued by the CA central issues from and naming the edge and its group, in the identity directory it
  * would have written it to; everything after that (reading `/edge/partner/me` and the sync over mutual TLS, authenticating the console
  * client to auth with that certificate through the terminator) is the real thing.
  */
final case class PartnerEdgeFixture(
    partnerId: String,
    groupId: String,
    edgeId: String,
    consoleClientId: String,
    baseUrl: String,
    api: EdgeApi,
    /** The first administrator (`partner.admins`), who exists at the operator with this login and password. */
    admin: PartnerEdgeFixture.Person,
    tenantId: String,
)

object PartnerEdgeFixture:
  /** `phone` is the identifier the team knows the person by: an email address here, which the operator's `/userinfo` carries as `email`. */
  case class Person(login: String, password: String, phone: String)

  private val Port = "9121"
  private val DiagnosticsPort = "9122"
  private val MutualTlsPort = "9123"

  def limits(domain: String): Json.Obj =
    Json.Obj(
      "scopes" -> Json.Arr(Json.Str("openid"), Json.Str("email"), Json.Str("phone")),
      "redirectDomains" -> Json.Arr(Json.Str(domain)),
      "maxClients" -> Json.Num(5),
      "maxEdges" -> Json.Num(2),
      "accessTokenTtlMax" -> Json.Num(600),
      "refreshTokenTtlMax" -> Json.Num(86400),
      "clientAuthMethods" -> Json.Arr(Json.Str("tls_client_auth")),
      "rateLimitPolicy" -> Json.Str("partner-default"),
    )

  /** A partner and its first edge, created through the operator API: the response of `POST /configuration/partners`. */
  def createPartner(central: CentralApi, tenantId: String, id: String, edgeId: String, publicUrl: String): Task[Json.Obj] =
    for
      created <- central.post(
        "/configuration/partners",
        Json.Obj(
          "partner" -> Json.Obj(
            "id" -> Json.Str(id),
            "tenantId" -> Json.Str(tenantId),
            "displayName" -> Json.Str(s"Partner $id"),
            "contact" -> Json.Obj("email" -> Json.Str(s"ops@$id.example")),
            "limits" -> limits(s"$id.example"),
          ),
          "consoleClient" -> Json.Obj("redirectUri" -> Json.Str(s"https://console.$id.example/complete")),
          "firstEdge" -> Json.Obj("id" -> Json.Str(edgeId), "publicUrl" -> Json.Str(publicUrl)),
        ),
      )
      _ <- ZIO.fail(RuntimeException(s"partner $id was refused: ${created.status} ${created.body}")).unless(created.status == Status.Created)
      body <- created.obj
    yield body

  /** `read` until `done`, or `attempts` times: what a change reaches the other service through is a cache that refreshes, never at once. */
  def eventually[R, A](read: ZIO[R, Throwable, A], attempts: Int = 40)(done: A => Boolean): ZIO[R, Throwable, A] =
    read.flatMap(value => if done(value) || attempts <= 1 then ZIO.succeed(value) else ZIO.sleep(250.millis) *> eventually(read, attempts - 1)(done))

  def layer: ZLayer[CentralApi & OAuthClient & E2EConfig & Client, Throwable, PartnerEdgeFixture] =
    ZLayer.scoped:
      for
        central <- ZIO.service[CentralApi]
        auth <- ZIO.service[OAuthClient]
        config <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        id <- CentralApi.id("e2e-partner")
        edgeId = s"$id-edge"
        baseUrl = s"http://localhost:$Port"
        created <- createPartner(central, Fixtures.suiteTenant, id, edgeId, s"https://login.$id.example")
        bundle <- ZIO.fromEither(created.str("bundle").getOrElse("").fromJson[Json.Obj]).mapError(RuntimeException(_))
        groupId = bundle.str("group").getOrElse("")
        key <- ZIO.fromOption(bundle.obj("signingKey")).orElseFail(RuntimeException("the bundle carries no signing key"))
        keyId = key.str("keyId").getOrElse("")
        privateKey = key.str("privateKey").getOrElse("")
        // the console client signs its users in with a login and a password in this stack
        _ <- auth.updateClient(Fixtures.clientUpdate(s"$id-console", "authFlow" -> Flows.loginPasswordAuthFlow))
        // auth reads the console client at its next reload, which is asked for (`POST /service/configuration/sync`, non-prod) and awaited:
        // auth redirects a client it knows, and answers 400 for one it does not
        _ <- central.postEmpty("/service/configuration/sync")
        _ <- eventually(
          auth.authorizeRaw(s"$id-console", s"https://console.$id.example/complete").map(_.response.status).orElseSucceed(Status.InternalServerError),
          attempts = 80,
        )(_ == Status.SeeOther)
        // the certificate the enrollment would have issued: by the CA central issues from, naming the edge and its group
        certificate <- EdgeCertificate.make(
          commonName = edgeId,
          caDirectory = "edge/dev/internal-tls",
          uris = List(s"urn:versola:edge:$edgeId", s"urn:versola:edge-group:$groupId"),
        )
        ssl <- certificate.clientSsl(config.centralMutualTlsServerCertificate)
        token <- EdgeToken.sign(edgeId, keyId, privateKey)
        // central authenticates an edge against a cache that a notification refreshes after the registration commits
        known <- eventually(central.onEdgeSurface(Some(token), Some(ssl)).get("/edge/partner/sync/dpop"), attempts = 100)(_.status == Status.Ok)
        _ <- ZIO.fail(RuntimeException(s"central never served the partner's edge")).unless(known.status == Status.Ok)
        admin <- person(auth, 1)
        directories <- ZIO.attemptBlocking:
          val identity = Files.createTempDirectory("e2e-partner-identity").nn
          val generation = Files.createDirectory(identity.resolve("gen-0000000001")).nn
          Files.writeString(generation.resolve("edge.crt"), certificate.certificatePem)
          Files.writeString(generation.resolve("edge.key"), certificate.privateKeyPemValue)
          val console = Files.createTempDirectory("e2e-partner-console").nn
          Files.writeString(console.resolve("partner.html"), "<!doctype html><title>partner console</title>")
          (identity, console)
        (identity, console) = directories
        extra =
          s"""id = "$edgeId"
             |key-id = "$keyId"
             |private-key = "$privateKey"
             |mode = "partner"
             |configuration-cache-refresh-interval = "2 seconds"
             |edge-url = "$baseUrl"
             |central { identity { directory = "$identity", group = "$groupId" } }
             |partner { admins = ["${admin.phone}"], console-directory = "$console" }
             |""".stripMargin
        // the staged first-party edge's configuration, less what a partner's edge refuses or does not hold: the internal secret, and the
        // certificate deployed with the edge (`central.identity` is the partner's)
        partnerEnvConf <- ZIO.attemptBlocking:
          val text = Files.readString(Path.of(config.edgeEnvConf))
            .replaceAll("(?m)^\\s*internal-secret\\s*=.*$", "")
            .replaceAll("(?m)^\\s*(certificate|private-key)\\s*=\\s*\"[^\"]*edge-client\\.(crt|key)\"\\s*$", "")
          val file = Files.createTempFile("e2e-partner-env", ".conf").nn
          file.toFile.nn.deleteOnExit()
          Files.writeString(file, text)
          file.toString
        running <- ExtraService.start(
          launcher = config.edgeLauncher,
          envConf = partnerEnvConf,
          extraConfig = extra,
          ports = Map("PORT" -> Port, "DPORT" -> DiagnosticsPort, "MPORT" -> MutualTlsPort),
        )
        // `E2E_PARTNER_EDGE_LOG=<file>` keeps what the partner's edge logged, which is otherwise removed with it
        _ <- ZIO.foreachDiscard(sys.env.get("E2E_PARTNER_EDGE_LOG"))(file =>
          ZIO.addFinalizer(running.output.flatMap(out => ZIO.attemptBlocking(Files.writeString(Path.of(file), out))).ignore),
        )
        _ <- ExtraService.awaitReady(running).provide(ZLayer.succeed(client))
        api = EdgeApi(client, config.copy(edgeUrl = baseUrl))
      yield PartnerEdgeFixture(id, groupId, edgeId, s"$id-console", baseUrl, api, admin, Fixtures.suiteTenant)

  /** A person at the operator who can sign in with a login and a password and whose `/userinfo` carries a phone number. */
  def person(auth: OAuthClient, index: Int): Task[Person] =
    for
      // not `Random`: a test runs with a seeded one, and every person would be the same
      suffix <- ZIO.attempt(java.util.concurrent.ThreadLocalRandom.current().nn.nextInt(1000000, 9999999))
      login = s"staff-$suffix-$index"
      phone = s"staff-$suffix-$index@partner.example"
      password = s"Pass-$suffix-1!"
      userId <- auth.registerUser(login = Some(login), email = Some(phone))
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
    yield Person(login, password, phone)
