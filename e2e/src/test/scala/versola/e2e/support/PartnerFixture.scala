package versola.e2e.support

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import zio.*
import zio.http.{Client, ClientSSLConfig, Status}
import zio.json.*
import zio.json.ast.Json

/** What the partner-edge e2e specs share (#549, #962): partners created through the operator API in a tenant of their own with a
  * first-party edge bound to it, what a partner's edge holds (the key it made, a certificate that names its group), a
  * staff member's access token for the partner's console, and the group's certificate as auth is shown it.
  *
  * The tenant is given the setting that tells auth where to read a client certificate from, as the suite tenant is: a partner's
  * clients authenticate with the certificate of their group (`tls_client_auth`, `san_uri`), which in these specs arrives as the
  * header a proxy would forward. */
object PartnerFixture:

  /** A partner created through the operator API, and what its first edge holds: the signing key the edge made (central has only the
    * public half) and a certificate of the CA central's listener trusts, naming the edge and its group. */
  case class Partner(
      id: String,
      tenantId: String,
      groupId: String,
      consoleClientId: String,
      edgeId: String,
      token: String,
      ssl: ClientSSLConfig,
  ):
    def consoleRedirectUri: String = s"https://console.$id.example/callback"

  /** A first-party edge in a tenant of its own: what the partners' data must not appear in. */
  case class FirstParty(edgeId: String, token: String, ssl: ClientSSLConfig, tenantId: String, clientId: String)

  /** Reads until the answer is the wanted one, or the last answer after ten seconds: central answers from caches that a
    * notification refreshes after the commit. */
  def eventually[A](read: Task[A])(condition: A => Boolean): Task[A] =
    read
      .repeat(Schedule.spaced(50.millis) *> Schedule.recurUntil(condition))
      .timeout(10.seconds)
      .someOrElseZIO(read)

  def partnerBody(id: String, tenantId: String, edgeId: String): Json.Obj =
    Json.Obj(
      "partner" -> Json.Obj(
        "id" -> Json.Str(id),
        "tenantId" -> Json.Str(tenantId),
        "displayName" -> Json.Str(s"Partner $id"),
        "contact" -> Json.Obj("email" -> Json.Str(s"ops@$id.example")),
        "limits" -> Json.Obj(
          "scopes" -> Json.Arr(Json.Str("openid"), Json.Str("email"), Json.Str("offline_access")),
          "redirectDomains" -> Json.Arr(Json.Str(s"$id.example")),
          "maxClients" -> Json.Num(5),
          "maxEdges" -> Json.Num(2),
          "accessTokenTtlMax" -> Json.Num(600),
          "refreshTokenTtlMax" -> Json.Num(86400),
          "clientAuthMethods" -> Json.Arr(Json.Str("tls_client_auth")),
          "rateLimitPolicy" -> Json.Str("partner-default"),
        ),
      ),
      "consoleClient" -> Json.Obj("redirectUri" -> Json.Str(s"https://console.$id.example/callback")),
      "firstEdge" -> Json.Obj("id" -> Json.Str(edgeId), "publicUrl" -> Json.Str(s"https://login.$id.example")),
    )

  /** A partner as the operator's creation leaves it: the one token it answers, and an edge that has made its own signing key and not
    * enrolled yet, so central holds no key for it. */
  case class Unenrolled(
      id: String,
      tenantId: String,
      groupId: String,
      edgeId: String,
      enrollmentToken: String,
      key: RSAKey,
      ssl: ClientSSLConfig,
  ):
    def partner(token: String): Partner = Partner(id, tenantId, groupId, s"$id-console", edgeId, token, ssl)

  def registerPartner(central: CentralApi, tenantId: String): Task[Unenrolled] =
    for
      id <- CentralApi.id("e2e-partner")
      edgeId = s"$id-edge"
      created <- central.post("/configuration/partners", partnerBody(id, tenantId, edgeId))
      _ <- ZIO.fail(RuntimeException(s"partner $id was refused: ${created.status} ${created.body}")).unless(created.status == Status.Created)
      body <- created.obj
      token = body.str("enrollmentToken").getOrElse("")
      // one string: vtk1.<base64url of central's enrollment address>.<secret>, and nothing else is handed over (no key of the edge's)
      _ <- ZIO.fail(RuntimeException(s"the answer carries no enrollment token of the one-token form: ${created.body}"))
        .unless(token.startsWith("vtk1.") && token.count(_ == '.') == 2)
      _ <- ZIO.fail(RuntimeException(s"the answer still carries a bundle or a key: ${created.body}"))
        .when(body.fields.exists(field => Set("bundle", "signingKey", "bundleComplete").contains(field._1)))
      groupId <- ZIO.fromOption(body.obj("edge").flatMap(_.str("groupId"))).orElseFail(RuntimeException(s"no group in ${created.body}"))
      // the edge makes its own key, as it makes the key of its TLS certificate
      key <- ZIO.attemptBlocking(RSAKeyGenerator(2048).keyID(s"$id-key").generate())
      certificate <- EdgeCertificate.forCentralListener(Some(edgeId), Some(groupId))
      ssl <- certificate.clientSsl(central.config.centralMutualTlsServerCertificate)
    yield Unenrolled(id, tenantId, groupId, edgeId, token, key, ssl)

  /** What an enrolment does to central's state of the edge: its public key is registered. (The enrolment itself needs central's
    * enrollment listener and a CA to sign edge certificates with, which the staged stack does not have; the unit and Postgres specs
    * cover it, and a certificate of the CA the listener trusts stands in for the one it would be given.) */
  def enrol(central: CentralApi, unenrolled: Unenrolled): Task[Partner] =
    for
      token <- EdgeToken.sign(unenrolled.edgeId, unenrolled.key.getKeyID, unenrolled.key.toRSAPrivateKey)
      partner = unenrolled.partner(token)
      _ <- CentralDatabase.registerEdgeKey(unenrolled.edgeId, unenrolled.key.toPublicJWK.toJSONString)
      // central authenticates an edge against a cache a Postgres notification refreshes after the registration lands; the probe is
      // a read that does not make the partner active, as `/edge/partner/me` does
      known <- eventually(onSurface(central, partner).get("/edge/partner/sync/dpop"))(_.status == Status.Ok)
      _ <- ZIO.fail(RuntimeException(s"central never served the edge of ${unenrolled.id}: ${known.status} ${known.body}"))
        .unless(known.status == Status.Ok)
    yield partner

  /** A token signed with the key the edge made, before it enrolled: central holds nothing to verify it with. */
  def asUnenrolled(central: CentralApi, unenrolled: Unenrolled): Task[CentralApi] =
    EdgeToken.sign(unenrolled.edgeId, unenrolled.key.getKeyID, unenrolled.key.toRSAPrivateKey)
      .map(token => central.onEdgeSurface(Some(token), Some(unenrolled.ssl)))

  def createPartner(central: CentralApi, tenantId: String): Task[Partner] =
    registerPartner(central, tenantId).flatMap(enrol(central, _))

  def createFirstParty(central: CentralApi, tenantId: String): Task[FirstParty] =
    for
      edgeId <- CentralApi.id("e2e-edge")
      clientId <- CentralApi.id("e2e-client")
      registered <- central.post("/configuration/edges", Json.Obj("id" -> Json.Str(edgeId)))
      keyId <- registered.stringAt("keyId")
      privateKey <- registered.stringAt("privateKey")
      token <- EdgeToken.sign(edgeId, keyId, privateKey)
      certificate <- EdgeCertificate.forCentralListener(Some(edgeId))
      ssl <- certificate.clientSsl(central.config.centralMutualTlsServerCertificate)
      known <- eventually(central.onEdgeSurface(Some(token), Some(ssl)).get("/edge/sync/dpop"))(_.status == Status.Ok)
      _ <- ZIO.fail(RuntimeException(s"central never authenticated the first-party edge: ${known.status}")).unless(known.status == Status.Ok)
      _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId, edgeId = Some(edgeId), securityProfile = Some("standard")))
        .flatMap(result => ZIO.fail(RuntimeException(s"tenant refused: ${result.status} ${result.body}")).unless(result.status.isSuccess))
      _ <- SecurityProfiles.awaitProfile(central, tenantId, "standard")
      _ <- readsCertificateFromHeader(central, tenantId)
      _ <- central.post("/configuration/clients", Fixtures.client(clientId, tenantId = tenantId))
        .flatMap(result => ZIO.fail(RuntimeException(s"client refused: ${result.status} ${result.body}")).unless(result.status.isSuccess))
    yield FirstParty(edgeId, token, ssl, tenantId, clientId)

  /** Tells auth where to read a client certificate from for the clients of `tenantId` (RFC 8705 §6.5): the header a proxy forwards it in. */
  private def readsCertificateFromHeader(central: CentralApi, tenantId: String): Task[Unit] =
    for
      current <- central.get("/configuration/challenges/challenge-settings", "tenantId" -> tenantId).flatMap(_.obj)
      settings <- ZIO.fromOption(current.obj("settings")).orElseFail(RuntimeException(s"tenant '$tenantId' has no challenge settings: $current"))
      written <- central.put(
        "/configuration/challenges/challenge-settings",
        Json.Obj(
          settings.fields.filterNot(f => Set("mtlsCertificateHeader", "mtlsCertificateEncoding").contains(f._1)) :+
            ("mtlsCertificateHeader" -> Json.Str(OAuthClient.mtlsCertificateHeader)) :+
            ("mtlsCertificateEncoding" -> Json.Str("urlEncodedPem")),
        ),
      )
      _ <- ZIO.fail(RuntimeException(s"could not set the certificate header of '$tenantId': ${written.status} ${written.body}"))
        .unless(written.status == Status.NoContent)
    yield ()

  def onSurface(central: CentralApi, partner: Partner): CentralApi =
    central.onEdgeSurface(Some(partner.token), Some(partner.ssl))

  def onSurface(central: CentralApi, edge: FirstParty): CentralApi =
    central.onEdgeSurface(Some(edge.token), Some(edge.ssl))

  /** The partner's edge as a console's management call is made: with the staff member's access token in `Versola-Principal`. */
  def asStaff(central: CentralApi, partner: Partner, staffToken: String): CentralApi =
    onSurface(central, partner).withHeader("Versola-Principal", staffToken)

  /** The ids in `items` of a document, as the partner's edge reads it; a refusal reads as none. */
  def ids(result: ApiResult, items: String, id: String): Task[Set[String]] =
    if result.status == Status.Ok then result.items(items).map(_.flatMap(_.str(id)).toSet) else ZIO.succeed(Set.empty)

  def syncedTo(central: CentralApi, partner: Partner, path: String, items: String, id: String)(own: String): Task[Set[String]] =
    eventually(onSurface(central, partner).get(path).flatMap(ids(_, items, id)))(_.contains(own))

  /** Runs `use` with a client of auth's public API, as a browser or a back end would be. */
  def withAuth[A](central: CentralApi)(use: OAuthClient => Task[A]): Task[A] =
    ZIO.serviceWithZIO[OAuthClient](use).provide(Client.default, ZLayer.succeed(central.config), OAuthClient.live)

  /** Whether auth knows the console client of `partner`, seen as a browser would: auth sends a client it knows, and which must push
    * its authorization request (the console does), back to its redirect URI with `invalid_request`; a client it does not know is
    * answered 400 and not redirected to anything. Auth learns of a change at its next reload, which is asked for, and the answer is polled. */
  def authKnows(central: CentralApi, partner: Partner)(known: Boolean): Task[Boolean] =
    // `POST /service/configuration/sync` (non-prod) makes central tell auth to reload at once rather than at its next interval
    val status = central.postEmpty("/service/configuration/sync") *>
      withAuth(central)(_.authorizeRaw(partner.consoleClientId, partner.consoleRedirectUri).map(_.response.status))
    eventually(status.map(_ == Status.SeeOther))(_ == known)

  def renew(central: CentralApi, partner: Partner): Task[ApiResult] =
    for
      csr <- EdgeCertificate.renewalRequest(partner.edgeId, partner.groupId)
      result <- onSurface(central, partner).post("/edge/certificate/renew", Json.Obj("csr" -> Json.Str(csr)))
    yield result

  def fixture[A](central: CentralApi)(use: (Partner, Partner, FirstParty) => Task[A]): Task[A] =
    for
      tenantId <- CentralApi.id("e2e-partner-tenant")
      firstParty <- createFirstParty(central, tenantId)
      a <- createPartner(central, tenantId)
      b <- createPartner(central, tenantId)
      result <- use(a, b, firstParty).ensuring:
        // best effort: a partner has no delete, and its rows are in a database that is thrown away with the stack
        (central.delete("/configuration/clients", "clientId" -> firstParty.clientId) *>
          central.delete("/configuration/edges", "edgeId" -> firstParty.edgeId)).ignore
    yield result

  // ── the group's certificate, as auth is shown it ─────────────────────────────────────────────

  /** A certificate of one replica of the partner's edge group, in the header form the tenant reads: it names the group, which is
    * what a partner's client authenticates by. `replica` makes two of them differ in key and serial, as two edges' certificates do. */
  def groupCertificate(partner: Partner, replica: String): Task[EdgeCertificate] =
    EdgeCertificate.make(
      commonName = s"${partner.id}-$replica",
      caDirectory = "auth/dev/mtls",
      uris = List(s"urn:versola:edge-group:${partner.groupId}"),
    )

  // ── a user signing in through a partner's client ─────────────────────────────────────────────

  /** A user who can sign in with a login and a password. */
  case class User(id: java.util.UUID, login: String, password: String)

  def createUser(auth: OAuthClient): Task[User] =
    for
      login <- CentralApi.id("staff")
      password = s"Pass-$login-1!"
      userId <- auth.registerUser(login = Some(login))
      _ <- auth.flushUserOutbox()
      _ <- auth.setUserPassword(userId, password)
    yield User(userId, login, password)

  /** The whole code flow of a client that must push its request, authenticates with `certificate` (the header form), and has a
    * consent screen: push, sign in, consent, exchange the code. Answers the token response. */
  def signIn(
      auth: OAuthClient,
      clientId: String,
      redirectUri: String,
      scope: Set[String],
      certificate: String,
      user: User,
  ): Task[TokenResult.Success] =
    for
      pushed <- auth.pushAuthorizationRequest(clientId, "", redirectUri, scope = scope.mkString(" "), certificate = Some(certificate)).success
      authorize <- auth.authorizePushed(clientId, pushed).assertChallengeRedirect
      cookie <- ZIO.fromOption(authorize.conversationCookie).orElseFail(RuntimeException("no conversation cookie"))
      credential <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      _ <- auth.submitLoginPassword(cookie, user.login, user.password, credential.csrf)
      consent <- auth.getChallenge(cookie).assertStep(ConversationStep.Consent)
      code <- auth.submitConsent(cookie, scope, consent.csrf).assertRedirect(auth, cookie)
      token <- auth.token(code, authorize.verifier, Some(clientId), Some(""), Some(redirectUri), certificate = Some(certificate)).success
    yield token

  /** A staff member's access token for the partner's console: the console client is given the login-and-password flow, and a user
    * signs in through it with the certificate of the partner's group. This is what a partner's edge forwards in `Versola-Principal`. */
  def staffToken(central: CentralApi, partner: Partner): Task[(String, User)] =
    withAuth(central): auth =>
      for
        user <- createUser(auth)
        certificate <- groupCertificate(partner, "console")
        _ <- auth.updateClient(Fixtures.clientUpdate(partner.consoleClientId, "authFlow" -> Flows.loginPasswordAuthFlow))
        _ <- auth.syncConfiguration()
        token <- signIn(auth, partner.consoleClientId, partner.consoleRedirectUri, Set("openid"), certificate.urlEncodedPem, user)
      yield (token.accessToken, user)

  // ── a partner's client, from the partner's request to the operator's approval ────────────────

  def clientRequest(id: String, partner: Partner, scope: Set[String]): Json.Obj =
    Json.Obj(
      "id" -> Json.Str(id),
      "kind" -> Json.Str("web"),
      "clientName" -> Json.Obj("en" -> Json.Str(s"Shop of ${partner.id}")),
      "redirectUris" -> Json.Arr(Json.Str(s"https://shop.${partner.id}.example/callback")),
      "scope" -> Json.Arr(scope.toList.sorted.map(Json.Str(_))*),
      "authMethod" -> Json.Str("tls_client_auth"),
      "accessTokenTtl" -> Json.Num(300),
      "refreshTokenTtl" -> Json.Num(3600),
    )

  /** The waiting request of the operator's queue about `targetId` of the partner. */
  def waitingRequest(central: CentralApi, partner: Partner, targetId: String): Task[Option[Json.Obj]] =
    eventually(
      central.get("/configuration/partners/approvals", "partnerId" -> partner.id, "status" -> "waiting").flatMap(_.items("approvals")).map(
        _.find(_.str("targetId").contains(targetId)),
      ),
    )(_.isDefined)

  def approve(central: CentralApi, partner: Partner, targetId: String, overrides: Option[Json.Obj] = None): Task[ApiResult] =
    for
      request <- waitingRequest(central, partner, targetId).someOrFail(RuntimeException(s"no waiting request about $targetId"))
      id <- ZIO.fromOption(request.str("id")).orElseFail(RuntimeException("the request has no id"))
      result <- central.post(
        "/configuration/partners/approvals/approve",
        Json.Obj(Chunk[(String, Json)]("id" -> Json.Str(id)) ++ Chunk.fromIterable(overrides.map("overrides" -> _))),
      )
    yield result

  /** A partner's client created by the partner's staff and approved by the operator: the client id. */
  def approvedClient(central: CentralApi, partner: Partner, staff: String, scope: Set[String]): Task[String] =
    for
      id <- CentralApi.id(s"${partner.id}-shop")
      created <- asStaff(central, partner, staff).post("/edge/partner/clients", clientRequest(id, partner, scope))
      _ <-
        ZIO.fail(RuntimeException(s"the partner's client was refused: ${created.status} ${created.body}")).unless(created.status == Status.Accepted)
      approved <- approve(central, partner, id)
      _ <-
        ZIO.fail(RuntimeException(s"the operator's approval was refused: ${approved.status} ${approved.body}")).unless(approved.status == Status.Ok)
      // how the operator's users sign in through the partner's client is the operator's to say
      _ <- withAuth(central)(_.updateClient(Fixtures.clientUpdate(id, "authFlow" -> Flows.loginPasswordAuthFlow)))
      // auth learns of the client at its next reload, which is asked for
      _ <- central.postEmpty("/service/configuration/sync")
    yield id
