package versola.e2e.support

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
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
  * and preset, and the edge, and answers one enrollment token); what the staged stack cannot do is the enrollment call itself, which is
  * made to central's `https` enrollment address (the token carries it) and the stack has none. So the fixture leaves in the identity
  * directory what an enrollment leaves: the certificate it would have given (issued by the CA central issues from, naming the edge and
  * its group) and `enrolled.json` with a signing key the fixture makes, whose public half it registers for the edge as `POST /edge/enroll`
  * does. The edge is started with no token and none of `id`, `key-id`, `private-key`, `versola-url`, `central.url`, group in its file:
  * a restart after an enrollment. Everything after that (reading `/edge/partner/me` and the sync over mutual TLS, authenticating the
  * console client to auth with that certificate through the terminator) is the real thing; the enrollment itself is covered by
  * `PartnerEnrollmentSpec`.
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
        _ <- ZIO.fail(
          RuntimeException(s"the answer carries no enrollment token: $created"),
        ).unless(created.str("enrollmentToken").exists(_.startsWith("vtk1.")))
        groupId <- ZIO.fromOption(created.obj("edge").flatMap(_.str("groupId"))).orElseFail(RuntimeException(s"no group in $created"))
        // the edge makes its own signing key, and central holds the public half once the edge has enrolled
        key <- ZIO.attemptBlocking(RSAKeyGenerator(2048).keyID(s"$id-key").generate())
        _ <- CentralDatabase.registerEdgeKey(edgeId, key.toPublicJWK.toJSONString)
        // the console client signs its users in with an email and a code: that is what makes the email verified, which a team invite requires
        _ <- auth.updateClient(Fixtures.clientUpdate(s"$id-console", "authFlow" -> Flows.emailOtpAuthFlow))
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
        token <- EdgeToken.sign(edgeId, key.getKeyID, key.toRSAPrivateKey)
        // central authenticates an edge against a cache that a notification refreshes after the registration commits
        known <- eventually(central.onEdgeSurface(Some(token), Some(ssl)).get("/edge/partner/sync/dpop"), attempts = 100)(_.status == Status.Ok)
        _ <- ZIO.fail(RuntimeException(s"central never served the partner's edge")).unless(known.status == Status.Ok)
        admin <- person(auth, 1)
        directories <- ZIO.attemptBlocking:
          val identity = Files.createTempDirectory("e2e-partner-identity").nn
          val generation = Files.createDirectory(identity.resolve("gen-0000000001")).nn
          Files.writeString(generation.resolve("edge.crt"), certificate.certificatePem)
          Files.writeString(generation.resolve("edge.key"), certificate.privateKeyPemValue)
          // what the enrollment answered and the edge kept: who it is, where central and auth are, the signing key it made
          val enrolled = Json.Obj(
            "edgeId" -> Json.Str(edgeId),
            "group" -> Json.Str(groupId),
            "keyId" -> Json.Str(key.getKeyID),
            "privateKey" -> Json.Str(java.util.Base64.getEncoder.encodeToString(key.toRSAPrivateKey.getEncoded)),
            "central" -> Json.Obj("mtlsUrl" -> Json.Str(config.centralMutualTlsUrl)),
            "auth" -> Json.Obj("issuer" -> Json.Str(config.authUrl)),
          )
          Files.writeString(identity.resolve("enrolled.json"), enrolled.toJson)
          // `E2E_PARTNER_CONSOLE_DIR` serves a real console build (central-ui's `dist`) instead of the stub page, for a person to open
          val console = sys.env.get("E2E_PARTNER_CONSOLE_DIR").map(dir => Path.of(dir).nn).getOrElse:
            val stub = Files.createTempDirectory("e2e-partner-console").nn
            Files.writeString(stub.resolve("partner.html"), "<!doctype html><title>partner console</title>")
            stub
          (identity, console)
        (identity, console) = directories
        extra =
          s"""mode = "partner"
             |configuration-cache-refresh-interval = "2 seconds"
             |edge-url = "$baseUrl"
             |central { identity { directory = "$identity" } }
             |partner { admins = ["${admin.phone}"], console-directory = "$console" }
             |""".stripMargin
        // the staged first-party edge's configuration, less what a partner's edge refuses or does not hold: the internal secret, and the
        // certificate deployed with the edge (`central.identity` is the partner's)
        partnerEnvConf <- partnerEnvConf(config)
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

  /** The staged first-party edge's configuration, less what a partner's edge refuses or does not hold: the internal secret, what the
    * enrollment fixes (its id, its signing key, central's and auth's addresses) and the certificate deployed with the edge. */
  private def partnerEnvConf(config: E2EConfig): Task[String] =
    ZIO.attemptBlocking:
      val text = Files.readString(Path.of(config.edgeEnvConf))
        .replaceAll("(?m)^\\s*internal-secret\\s*=.*$", "")
        .replaceAll("(?ms)^private-key\\s*=\\s*\"\"\".*?\"\"\"", "")
        .replaceAll("(?m)^(id|key-id|versola-url)\\s*=.*$", "")
        .replaceAll("(?m)^\\s+url\\s*=\\s*\"https?://[^\"]*\"\\s*$", "")
        .replaceAll("(?m)^\\s*(certificate|private-key)\\s*=\\s*\"[^\"]*edge-client\\.(crt|key)\"\\s*$", "")
      val file = Files.createTempFile("e2e-partner-env", ".conf").nn
      file.toFile.nn.deleteOnExit()
      Files.writeString(file, text)
      file.toString

  /** A second edge of the partner's group, as the partner's console registered it (`POST /partner/api/edges` answered `edgeId`): its own
    * signing key (registered at central as an enrollment would), its own certificate naming it and the group, its own identity directory,
    * its own ports, and the database of the first edge, so that the two share the sessions. Waits until it serves. */
  def startAnotherEdge(f: PartnerEdgeFixture, edgeId: String): ZIO[Scope & CentralApi & E2EConfig & Client, Throwable, EdgeApi] =
    for
      central <- ZIO.service[CentralApi]
      config <- ZIO.service[E2EConfig]
      client <- ZIO.service[Client]
      key <- ZIO.attemptBlocking(RSAKeyGenerator(2048).keyID(s"$edgeId-key").generate())
      _ <- CentralDatabase.registerEdgeKey(edgeId, key.toPublicJWK.toJSONString)
      certificate <- EdgeCertificate.make(
        commonName = edgeId,
        caDirectory = "edge/dev/internal-tls",
        uris = List(s"urn:versola:edge:$edgeId", s"urn:versola:edge-group:${f.groupId}"),
      )
      ssl <- certificate.clientSsl(config.centralMutualTlsServerCertificate)
      token <- EdgeToken.sign(edgeId, key.getKeyID, key.toRSAPrivateKey)
      known <- eventually(central.onEdgeSurface(Some(token), Some(ssl)).get("/edge/partner/sync/dpop"), attempts = 100)(_.status == Status.Ok)
      _ <- ZIO.fail(RuntimeException("central never served the second edge")).unless(known.status == Status.Ok)
      identity <- ZIO.attemptBlocking:
        val directory = Files.createTempDirectory("e2e-partner-identity-2").nn
        val generation = Files.createDirectory(directory.resolve("gen-0000000001")).nn
        Files.writeString(generation.resolve("edge.crt"), certificate.certificatePem)
        Files.writeString(generation.resolve("edge.key"), certificate.privateKeyPemValue)
        Files.writeString(
          directory.resolve("enrolled.json"),
          Json.Obj(
            "edgeId" -> Json.Str(edgeId),
            "group" -> Json.Str(f.groupId),
            "keyId" -> Json.Str(key.getKeyID),
            "privateKey" -> Json.Str(java.util.Base64.getEncoder.encodeToString(key.toRSAPrivateKey.getEncoded)),
            "central" -> Json.Obj("mtlsUrl" -> Json.Str(config.centralMutualTlsUrl)),
            "auth" -> Json.Obj("issuer" -> Json.Str(config.authUrl)),
          ).toJson,
        )
        directory
      stub <- ZIO.attemptBlocking:
        val directory = Files.createTempDirectory("e2e-partner-console-2").nn
        Files.writeString(directory.resolve("partner.html"), "<!doctype html><title>partner console</title>")
        directory
      baseUrl = s"http://localhost:$SecondPort"
      envConf <- partnerEnvConf(config)
      running <- ExtraService.start(
        launcher = config.edgeLauncher,
        envConf = envConf,
        extraConfig =
          s"""mode = "partner"
             |configuration-cache-refresh-interval = "2 seconds"
             |edge-url = "$baseUrl"
             |central { identity { directory = "$identity" } }
             |partner { console-directory = "$stub" }
             |""".stripMargin,
        ports = Map("PORT" -> SecondPort, "DPORT" -> "9132", "MPORT" -> "9133"),
      )
      _ <- ExtraService.awaitReady(running).provide(ZLayer.succeed(client))
    yield EdgeApi(client, config.copy(edgeUrl = baseUrl))

  val SecondPort = "9131"

  /** A person at the operator who signs in with an email and a code, and whose `/userinfo` carries that email with `email_verified`. `phone` is
    * the identifier the team knows them by: the email. */
  def person(auth: OAuthClient, index: Int): Task[Person] =
    for
      // not `Random`: a test runs with a seeded one, and every person would be the same
      suffix <- ZIO.attempt(java.util.concurrent.ThreadLocalRandom.current().nn.nextInt(1000000, 9999999))
      email = s"staff-$suffix-$index@partner.example"
      userId <- auth.registerUser(email = Some(email))
      _ <- auth.flushUserOutbox()
    yield Person(email, "", email)
