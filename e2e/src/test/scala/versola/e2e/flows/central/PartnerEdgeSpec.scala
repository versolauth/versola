package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Client, ClientSSLConfig, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** The central side of the partner edge (#549, #962), on the staged stack: central's operator API creates a partner with its
  * group, console client and first edge; the partner's edge then reads `/edge/partner` over central's mutual-TLS listener,
  * with its own token and a certificate that names its group.
  *
  * What only this level shows is the whole chain at once: the migration's tables under real Postgres, the operator's creation
  * finding its way through the client service, the preset validation and the edge registration, the group name of a
  * certificate being what scopes an edge's answers, a renewal being signed by the CA central really holds, and a suspension
  * reaching the sync auth reads, the edge surface and auth's revocation endpoint (a suspension answers only once central has
  * asked auth to end the grants of the partner's clients).
  */
object PartnerEdgeSpec extends CentralApiSpec:

  /** A partner created through the operator API, and what its first edge holds: the key from the bundle and a certificate of
    * the CA central's listener trusts, naming the edge and its group. */
  private case class Partner(
      id: String,
      groupId: String,
      consoleClientId: String,
      edgeId: String,
      token: String,
      ssl: ClientSSLConfig,
  )

  /** A first-party edge in a tenant of its own: what the partners' data must not appear in. */
  private case class FirstParty(edgeId: String, token: String, ssl: ClientSSLConfig, tenantId: String, clientId: String)

  private def partnerBody(id: String, tenantId: String, edgeId: String): Json.Obj =
    Json.Obj(
      "partner" -> Json.Obj(
        "id" -> Json.Str(id),
        "tenantId" -> Json.Str(tenantId),
        "displayName" -> Json.Str(s"Partner $id"),
        "contact" -> Json.Obj("email" -> Json.Str(s"ops@$id.example")),
        "limits" -> Json.Obj(
          "scopes" -> Json.Arr(Json.Str("openid"), Json.Str("email")),
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

  private def createPartner(central: CentralApi, tenantId: String): Task[Partner] =
    for
      id <- CentralApi.id("e2e-partner")
      edgeId = s"$id-edge"
      created <- central.post("/configuration/partners", partnerBody(id, tenantId, edgeId))
      _ <- ZIO.fail(RuntimeException(s"partner $id was refused: ${created.status} ${created.body}")).unless(created.status == Status.Created)
      body <- created.obj
      bundle <- ZIO.fromEither(body.str("bundle").getOrElse("").fromJson[Json.Obj]).mapError(RuntimeException(_))
      key <- ZIO.fromOption(bundle.obj("signingKey")).orElseFail(RuntimeException("the bundle carries no signing key"))
      groupId = bundle.str("group").getOrElse("")
      token <- EdgeToken.sign(edgeId, key.str("keyId").getOrElse(""), key.str("privateKey").getOrElse(""))
      certificate <- EdgeCertificate.forCentralListener(Some(edgeId), Some(groupId))
      ssl <- certificate.clientSsl(central.config.centralMutualTlsServerCertificate)
      partner = Partner(id, groupId, s"$id-console", edgeId, token, ssl)
      // central authenticates an edge against a cache a Postgres notification refreshes after the registration lands
      known <- eventually(onSurface(central, partner).get("/edge/partner/sync/dpop"))(_.status == Status.Ok)
      _ <- ZIO.fail(RuntimeException(s"central never served the edge of $id: ${known.status} ${known.body}")).unless(known.status == Status.Ok)
    yield partner

  private def createFirstParty(central: CentralApi, tenantId: String): Task[FirstParty] =
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
      _ <- central.post("/configuration/clients", Fixtures.client(clientId, tenantId = tenantId))
        .flatMap(result => ZIO.fail(RuntimeException(s"client refused: ${result.status} ${result.body}")).unless(result.status.isSuccess))
    yield FirstParty(edgeId, token, ssl, tenantId, clientId)

  private def onSurface(central: CentralApi, partner: Partner): CentralApi =
    central.onEdgeSurface(Some(partner.token), Some(partner.ssl))

  private def onSurface(central: CentralApi, edge: FirstParty): CentralApi =
    central.onEdgeSurface(Some(edge.token), Some(edge.ssl))

  /** The ids in `items` of a document, as the partner's edge reads it; a refusal reads as none. */
  private def ids(result: ApiResult, items: String, id: String): Task[Set[String]] =
    if result.status == Status.Ok then result.items(items).map(_.flatMap(_.str(id)).toSet) else ZIO.succeed(Set.empty)

  private def syncedTo(central: CentralApi, partner: Partner, path: String, items: String, id: String)(own: String): Task[Set[String]] =
    eventually(onSurface(central, partner).get(path).flatMap(ids(_, items, id)))(_.contains(own))

  /** Whether auth knows the console client of `partner`, seen as a browser would: auth sends a client it knows, and which must push
    * its authorization request (the console does), back to its redirect URI with `invalid_request`; a client it does not know is
    * answered 400 and not redirected to anything. Auth learns of a change at its next reload, which is asked for, and the answer is polled. */
  private def authKnows(central: CentralApi, partner: Partner)(known: Boolean): Task[Boolean] =
    // `POST /service/configuration/sync` (non-prod) makes central tell auth to reload at once rather than at its next interval
    val status = central.postEmpty("/service/configuration/sync") *>
      ZIO.serviceWithZIO[OAuthClient](_.authorizeRaw(
        partner.consoleClientId,
        s"https://console.${partner.id}.example/callback",
      ).map(_.response.status))
        .provide(Client.default, ZLayer.succeed(central.config), OAuthClient.live)
    eventually(status.map(_ == Status.SeeOther))(_ == known)

  private def renew(central: CentralApi, partner: Partner): Task[ApiResult] =
    for
      csr <- EdgeCertificate.renewalRequest(partner.edgeId, partner.groupId)
      result <- onSurface(central, partner).post("/edge/certificate/renew", Json.Obj("csr" -> Json.Str(csr)))
    yield result

  private def fixture[A](central: CentralApi)(use: (Partner, Partner, FirstParty) => Task[A]): Task[A] =
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

  def spec = suite("Central API: the partner edge")(
    test("two partners' edges each read only their own data, and the first-party sync has none of it") {
      for
        central <- api
        result <- fixture(central): (a, b, firstParty) =>
          for
            me <- onSurface(central, a).get("/edge/partner/me")
            document <- me.obj
            aClients <- syncedTo(central, a, "/edge/partner/sync/clients", "clients", "id")(a.consoleClientId)
            bClients <- syncedTo(central, b, "/edge/partner/sync/clients", "clients", "id")(b.consoleClientId)
            aPresets <- syncedTo(central, a, "/edge/partner/sync/auth-request-presets", "presets", "id")(a.consoleClientId)
            bPresets <- syncedTo(central, b, "/edge/partner/sync/auth-request-presets", "presets", "id")(b.consoleClientId)
            firstPartyClients <- eventually(
              onSurface(central, firstParty).get("/edge/sync/clients").flatMap(ids(_, "clients", "id")),
            )(_.contains(firstParty.clientId))
            asFirstParty <- onSurface(central, firstParty).get("/edge/partner/me")
            partnerOnFirstParty <- onSurface(central, a).get("/edge/sync/clients")
          yield assertTrue(me.status == Status.Ok, document.obj("partner").flatMap(_.str("id")).contains(a.id))
            .label("the partner's edge is told which partner it is") &&
            assertTrue(document.obj("edge").flatMap(_.str("id")).contains(a.edgeId)) &&
            assertTrue(aClients == Set(a.consoleClientId)).label("partner A's edge reads its own console client and nothing else") &&
            assertTrue(bClients == Set(b.consoleClientId)).label("partner B's edge reads B's, not A's") &&
            assertTrue(aPresets == Set(a.consoleClientId), bPresets == Set(b.consoleClientId))
              .label("the presets are scoped through the client they belong to") &&
            assertTrue(
              firstPartyClients.contains(firstParty.clientId),
              !firstPartyClients.contains(a.consoleClientId),
              !firstPartyClients.contains(b.consoleClientId),
            )
              .label("a partner's clients are not in the operator's own edge's sync") &&
            assertTrue(asFirstParty.status == Status.Unauthorized)
              .label("an operator's own edge is not served the partner surface: its sync is /edge/sync") &&
            assertTrue(partnerOnFirstParty.status == Status.Unauthorized)
              .label("a partner's edge is not served /edge/sync, which is the operator's")
      yield result
    },
    test("a partner's first call makes it active, and an edge presenting another partner's group is refused") {
      for
        central <- api
        result <- fixture(central): (a, b, _) =>
          for
            _ <- onSurface(central, a).get("/edge/partner/me")
            partners <- eventually(central.get("/configuration/partners").flatMap(_.items("partners")))(
              _.exists(p => p.str("id").contains(a.id) && p.str("status").contains("active")),
            )
            // B's token with a certificate that names A's group (an edge cannot present itself as a member of another group)
            forged <- EdgeCertificate.forCentralListener(Some(b.edgeId), Some(a.groupId))
            forgedSsl <- forged.clientSsl(central.config.centralMutualTlsServerCertificate)
            asOtherGroup <- central.onEdgeSurface(Some(b.token), Some(forgedSsl)).get("/edge/partner/me")
          yield assertTrue(
            partners.find(_.str("id").contains(a.id)).flatMap(_.str("status")).contains("active"),
            partners.find(_.str("id").contains(b.id)).flatMap(_.str("status")).contains("onboarding"),
          ).label("the partner whose edge called is active, the one whose edge never did is still onboarding") &&
            assertTrue(asOtherGroup.status == Status.Unauthorized)
              .label("a certificate naming another group does not carry this edge's token")
      yield result
    },
    test("a partner's edge renews its certificate as often as it likes, and what it is given names the edge and its group") {
      for
        central <- api
        result <- fixture(central): (a, _, _) =>
          for
            first <- renew(central, a)
            second <- renew(central, a)
            third <- renew(central, a)
            body <- first.obj
            wrongEdge <- EdgeCertificate.renewalRequest(s"${a.edgeId}-other", a.groupId)
              .flatMap(csr => onSurface(central, a).post("/edge/certificate/renew", Json.Obj("csr" -> Json.Str(csr))))
            garbage <- onSurface(central, a).post("/edge/certificate/renew", Json.Obj("csr" -> Json.Str("not a request")))
          yield assertTrue(first.status == Status.Ok, second.status == Status.Ok, third.status == Status.Ok)
            .label("renewal is always on for a partner's edge, as for a first-party one") &&
            assertTrue(body.str("certificate").exists(_.contains("BEGIN CERTIFICATE")))
              .label("a chain comes back") &&
            assertTrue(wrongEdge.status == Status.UnprocessableEntity, garbage.status == Status.UnprocessableEntity)
              .label("a request that names another edge, or is no request, is refused")
      yield result
    },
    test("suspending a partner ends its edges' access and omits its clients from auth's sync, and resuming restores both") {
      for
        central <- api
        result <- fixture(central): (a, b, _) =>
          for
            _ <- onSurface(central, a).get("/edge/partner/me")
            beforeA <- authKnows(central, a)(known = true)
            beforeB <- authKnows(central, b)(known = true)
            suspended <- central.post("/configuration/partners/suspend", Json.Obj("id" -> Json.Str(a.id), "note" -> Json.Str("e2e")))
            aOnSuspended <- eventually(onSurface(central, a).get("/edge/partner/me"))(_.status == Status.Forbidden)
            aSync <- onSurface(central, a).get("/edge/partner/sync/clients")
            aRenew <- renew(central, a)
            bAfter <- onSurface(central, b).get("/edge/partner/me")
            bRenew <- renew(central, b)
            duringA <- authKnows(central, a)(known = false)
            duringB <- authKnows(central, b)(known = true)
            resumed <- central.post("/configuration/partners/resume", Json.Obj("id" -> Json.Str(a.id)))
            aOnResumed <- eventually(onSurface(central, a).get("/edge/partner/me"))(_.status == Status.Ok)
            aRenewed <- renew(central, a)
            afterA <- authKnows(central, a)(known = true)
            again <- central.post("/configuration/partners/resume", Json.Obj("id" -> Json.Str(a.id)))
          yield assertTrue(beforeA, beforeB).label("before: auth knows both partners' console clients") &&
            assertTrue(suspended.status == Status.NoContent)
              .label("a suspension answers only after central has asked auth to end the grants of the partner's clients") &&
            assertTrue(aOnSuspended.status == Status.Forbidden, aSync.status == Status.Forbidden, aRenew.status == Status.Forbidden)
              .label("the suspended partner's edge is refused everywhere, certificate renewal included") &&
            assertTrue(bAfter.status == Status.Ok, bRenew.status == Status.Ok).label("another partner is untouched") &&
            assertTrue(!duringA, duringB)
              .label("the suspended partner's client is omitted from what auth reads, so auth no longer knows it; another partner's is untouched") &&
            assertTrue(resumed.status == Status.NoContent, aOnResumed.status == Status.Ok, aRenewed.status == Status.Ok)
              .label("resuming gives the edge its access back") &&
            assertTrue(afterA).label("and auth knows the client again") &&
            assertTrue(again.status == Status.Conflict).label("resuming a partner that is not suspended is a conflict")
      yield result
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
