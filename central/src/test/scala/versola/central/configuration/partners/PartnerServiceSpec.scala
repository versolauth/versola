package versola.central.configuration.partners

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.CentralConfig
import versola.central.TestCentralConfig
import versola.central.configuration.clients.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  AuthorizationPresetService,
  ClientApproval,
  ClientId,
  ClientKind,
  ClientTemplate,
  InvalidRegistrationConfiguration,
  MutualTlsAuth,
  MutualTlsSubjectType,
  OAuthClientRecord,
  OAuthClientService,
  PresetId,
  RegisteredClient,
}
import versola.central.configuration.edges.{
  Change,
  EdgeCertificate,
  EdgeEnrollmentTokenRepository,
  EdgeGroupId,
  EdgeGroupKind,
  EdgeGroupRecord,
  EdgeGroupService,
  EdgeId,
  EdgeIdentityService,
  EdgeRecord,
  EdgeService,
  PolicyPatchError,
  PolicySection,
  PolicySectionMeta,
}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.{TenantId, TenantRecord, TenantRepository}
import versola.central.users.AuthClient
import versola.util.http.RateLimitPolicies
import versola.util.{RedirectUri, RsaKeyPair}
import zio.*
import zio.http.URL
import zio.json.DecoderOps
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant

/** The operator's partner operations (#962 §3, §5): one request creates everything a partner starts with; suspending is the
  * kill switch; limits are re-checked, not re-applied; the operator's decisions are applied. */
object PartnerServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val tenantId = TenantId("default")
  private val now = Instant.parse("2026-11-10T10:00:00Z")

  private val limits = PartnerLimits(
    scopes = Set(ScopeToken("phone")),
    redirectDomains = Set("acme.example"),
    maxClients = 3,
    maxEdges = 1,
    accessTokenTtlMax = 600,
    refreshTokenTtlMax = 86400,
    clientAuthMethods = Set(AuthMethod.tls_client_auth),
    resourceDomains = None,
  )

  private val newPartner = PartnerService.NewPartner(
    id = PartnerId("acme"),
    tenantId = tenantId,
    displayName = "ACME",
    logoUri = Some("https://acme.example/logo.png"),
    contact = PartnerContact("ops@acme.example", None),
    limits = limits,
    reviewAt = None,
    consoleRedirectUri = RedirectUri("https://console.acme.example/complete"),
    firstEdgeId = EdgeId("edge-acme-1"),
    firstEdgePublicUrl = "https://login.acme.example",
  )

  private val acme = PartnerRecord(
    id = PartnerId("acme"),
    tenantId = tenantId,
    displayName = "ACME",
    logoUri = None,
    contact = PartnerContact("ops@acme.example", None),
    status = PartnerStatus.Active,
    limits = limits.copy(scopes = Set(ScopeToken("openid"), ScopeToken("phone"))),
    groupId = EdgeGroupId("acme"),
    consoleClientId = ClientId("acme-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = now,
    version = 0L,
  )

  private val keyPair: RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = "edge-key", publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  private def edge(id: EdgeId, groupId: EdgeGroupId = EdgeGroupId("acme")) = EdgeRecord(
    id = id,
    publicKey = None, // a partner edge brings its key when it enrols
    oldPublicKey = None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = groupId,
    groupKind = EdgeGroupKind.Partner,
    publicUrl = Some("https://login.acme.example"),
  )

  private def client(id: ClientId, approval: ClientApproval): OAuthClientRecord =
    OAuthClientRecord(
      id = id,
      tenantId = tenantId,
      clientName = Map("en" -> id),
      redirectUris = Set(RedirectUri("https://shop.acme.example/cb")),
      scope = Set(ScopeToken("openid")),
      secret = None,
      previousSecret = None,
      accessTokenTtl = 300.seconds,
      refreshTokenTtl = 3600.seconds,
      permissions = Set.empty,
      theme = "default",
      authFlow = Some(AuthFlow.default),
      otpTemplateId = "default",
      frontChannelLogoutUri = None,
      frontChannelLogoutSessionRequired = false,
      backChannelLogoutUri = None,
      logoUri = None,
      policyUri = None,
      tosUri = None,
      consentFlow = None,
      dpopBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = AuthMethod.tls_client_auth,
      mtlsAuth = Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(EdgeGroupId("acme")))),
      certificateBoundAccessTokens = false,
      jwks = None,
      requireSignedRequestObject = false,
      requirePushedAuthorizationRequests = true,
      edgeSigningKey = None,
      edgeClientCertificate = None,
      template = Some(ClientTemplate(ClientKind.web)),
      createdAt = now,
      applicationType = ApplicationType.web,
      themeOverrides = None,
      partnerId = Some(acme.id),
      approval = approval,
    )

  private class Env(config: CentralConfig = configured):
    val partners = stub[PartnerRepository]
    val registry = stub[PartnerRegistry]
    val approvals = stub[PartnerApprovalRepository]
    val partnerClients = stub[PartnerClientService]
    val clients = stub[OAuthClientService]
    val presets = stub[AuthorizationPresetService]
    val groups = stub[EdgeGroupService]
    val edges = stub[EdgeService]
    val identity = stub[EdgeIdentityService]
    val enrollmentTokens = stub[EdgeEnrollmentTokenRepository]
    val tenants = stub[TenantRepository]
    val auth = stub[AuthClient]
    val service =
      PartnerService.Impl(
        partners,
        registry,
        approvals,
        partnerClients,
        clients,
        presets,
        groups,
        edges,
        identity,
        enrollmentTokens,
        tenants,
        auth,
        config,
      )

    /** Nothing exists yet: what a creation finds. */
    def empty: UIO[Unit] =
      tenants.getAll.succeedsWith(Vector(TenantRecord(tenantId, "Default", None))) *>
        partners.find.succeedsWith(None) *>
        groups.find.succeedsWith(None) *>
        clients.findClient.succeedsWith(None) *>
        edges.findFresh.succeedsWith(None) *>
        registry.refreshNow.succeedsWith(()) *>
        clients.checkRegistration.succeedsWith(())

  private val configured = TestCentralConfig.config.copy(
    auth = TestCentralConfig.config.auth.copy(mtlsUrl = Some(URL.decode("https://id-mtls.example.com").toOption.get)),
    publicUrls = Some(CentralConfig.PublicUrlsConfig(
      enroll = URL.decode("https://central.example.com").toOption.get,
      mtls = URL.decode("https://central-mtls.example.com").toOption.get,
    )),
  )

  def spec = suite("PartnerService")(
    suite("create")(
      test(
        "creates the group, the partner, the console client with its preset, the first edge without a key and a 24-hour token, and answers the token alone",
      ) {
        val env = Env(configured)
        val console = client(ClientId("acme-console"), ClientApproval.Approved)
        for
          _ <- env.empty
          _ <- env.groups.create.succeedsWith(true)
          _ <- env.partners.create.succeedsWith(())
          _ <- env.clients.registerClient.returnsZIO((_, _, _, _, _) => ZIO.succeed(RegisteredClient(None, now, None)))
          // the console client is read back after it was registered, and not before: the pre-check must find none; it has the scope
          // it was registered with, which is what its preset is made of
          _ <- env.clients.findClient.returnsZIO(id =>
            ZIO.succeed(
              Option.when(id == ClientId("acme-console") && env.clients.registerClient.calls.nonEmpty)(
                console.copy(scope = env.clients.registerClient.calls.head._1.allowedScopes),
              ),
            ),
          )
          _ <- env.clients.refreshNow.succeedsWith(())
          _ <- env.presets.savePresets.succeedsWith(Right(()))
          _ <- env.edges.registerEdgeWithoutKey.succeedsWith(())
          _ <- env.edges.sync().succeedsWith(())
          // the edge is read back after it was registered, and not before: the pre-check must find none
          _ <- env.edges.findFresh.returnsZIO(id =>
            ZIO.succeed(Option.when(id == EdgeId("edge-acme-1") && env.edges.registerEdgeWithoutKey.calls.nonEmpty)(edge(id))),
          )
          _ <- env.identity.issueEnrollmentToken.succeedsWith(EdgeIdentityService.IssuedToken("TOKEN", now.plusSeconds(86400)))
          created <- env.service.create(newPartner)
          registration = env.clients.registerClient.calls.head
        yield assertTrue(
          env.groups.create.calls == List(EdgeGroupRecord(EdgeGroupId("acme"), EdgeGroupKind.Partner)),
          env.partners.create.calls.map(p => (p.id, p.status, p.groupId, p.consoleClientId, p.limits.scopes)) ==
            List((
              PartnerId("acme"),
              PartnerStatus.Onboarding,
              EdgeGroupId("acme"),
              ClientId("acme-console"),
              Set(ScopeToken("openid"), ScopeToken("phone")),
            )),
          registration._1.id == ClientId("acme-console"),
          registration._1.partnerId == Some(PartnerId("acme")),
          registration._1.authMethod == AuthMethod.tls_client_auth,
          registration._1.mtlsAuth == Some(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, "urn:versola:edge-group:acme")),
          registration._1.allowedScopes == PartnerService.ConsoleScope,
          // the console session has no refresh token, by decision: neither the client nor the preset it signs in with asks for `offline_access`
          !registration._1.allowedScopes.contains(ScopeToken("offline_access")),
          !PartnerService.ConsoleScope.contains(ScopeToken("offline_access")),
          env.presets.savePresets.calls.head.presets.map(_.scope) == List(PartnerService.ConsoleScope),
          registration._1.redirectUris == Set(newPartner.consoleRedirectUri),
          // the console signs in the partner's own staff, not the operator's users: it is the one client of a partner without consent
          registration._1.consentFlow.isEmpty,
          // a client with no auth flow is "not configured for sign-in" at auth: until the operator sets one, the platform's default
          registration._1.authFlow == Some(AuthFlow.default),
          // the staff token the console is issued authorises management calls and nothing revokes it: minutes
          registration._1.accessTokenTtl <= PartnerService.ConsoleAccessTokenTtlMax,
          !registration._1.certificateBoundAccessTokens,
          registration._5,
          env.presets.savePresets.calls.head.presets.map(p => (p.id, p.redirectUri)) == List((
            PresetId("acme-console"),
            newPartner.consoleRedirectUri,
          )),
          env.edges.registerEdgeWithoutKey.calls == List((EdgeId("edge-acme-1"), EdgeGroupId("acme"), Some("https://login.acme.example"))),
          env.identity.issueEnrollmentToken.calls == List((EdgeId("edge-acme-1"), 24.hours)),
          created.edge.token.token == "TOKEN",
          // no key is made or shown for the edge: it makes its own and registers the public half when it enrols
          env.edges.registerEdge.calls.isEmpty,
          created.edge.edge.publicKey.isEmpty,
        )
      },
      test("a refusal of the console client (its tenant's security profile, its redirect URI) refuses the partner before anything is written") {
        val env = Env(configured)
        for
          _ <- env.empty
          _ <- env.clients.checkRegistration.failsWith(
            InvalidRegistrationConfiguration(ClientId("acme-console"), "FAPI 2.0 requires sender-constrained access tokens"),
          )
          refused <- env.service.create(newPartner).flip
        yield assertTrue(
          refused.isInstanceOf[PartnerError.Invalid],
          // no group, no partner, no client, no preset, no edge, no token
          env.groups.create.calls.isEmpty,
          env.partners.create.calls.isEmpty,
          env.clients.registerClient.calls.isEmpty,
          env.presets.savePresets.calls.isEmpty,
          env.edges.registerEdgeWithoutKey.calls.isEmpty,
          env.identity.issueEnrollmentToken.calls.isEmpty,
          // and what was asked of the profile is the console client the creation would register
          env.clients.checkRegistration.calls.map(c => (c._1.id, c._1.partnerId, c._1.consentFlow, c._2.map(_.id))) ==
            List((ClientId("acme-console"), Some(PartnerId("acme")), None, Some(PartnerId("acme")))),
        )
      },
      // The group of a partner is of kind `partner` because the service makes it so, and for no other reason: no input names a group, and
      // the partner row is written only after this call created the group (nothing in the database checks the kind any more)
      test("a partner is written only to a group this call created, of kind partner: one that appeared meanwhile, of any kind, is a conflict") {
        val env = Env(configured)
        for
          _ <- env.empty
          _ <- env.groups.create.succeedsWith(false)
          raced <- env.service.create(newPartner).flip
        yield assertTrue(
          raced.isInstanceOf[PartnerError.Conflict],
          env.groups.create.calls == List(EdgeGroupRecord(EdgeGroupId("acme"), EdgeGroupKind.Partner)),
          env.partners.create.calls.isEmpty,
        )
      },
      test(
        "refuses before writing anything: a taken partner, group, client or edge id, an unknown tenant, bad limits, a console or an edge that is not https",
      ) {
        val env = Env()
        for
          _ <- env.empty
          _ <- env.partners.find.succeedsWith(Some(acme))
          partnerTaken <- env.service.create(newPartner).flip
          _ <- env.partners.find.succeedsWith(None)
          _ <- env.groups.find.succeedsWith(Some(EdgeGroupRecord(EdgeGroupId("acme"), EdgeGroupKind.FirstParty)))
          groupTaken <- env.service.create(newPartner).flip
          _ <- env.groups.find.succeedsWith(None)
          _ <- env.clients.findClient.succeedsWith(Some(client(ClientId("acme-console"), ClientApproval.Approved)))
          clientTaken <- env.service.create(newPartner).flip
          _ <- env.clients.findClient.succeedsWith(None)
          _ <- env.edges.findFresh.succeedsWith(Some(edge(EdgeId("edge-acme-1"))))
          edgeTaken <- env.service.create(newPartner).flip
          _ <- env.edges.findFresh.succeedsWith(None)
          _ <- env.tenants.getAll.succeedsWith(Vector.empty)
          noTenant <- env.service.create(newPartner).flip
          _ <- env.tenants.getAll.succeedsWith(Vector(TenantRecord(tenantId, "Default", None)))
          badLimits <- env.service.create(newPartner.copy(limits = limits.copy(clientAuthMethods = Set(AuthMethod.client_secret)))).flip
          noEdges <- env.service.create(newPartner.copy(limits = limits.copy(maxEdges = 0))).flip
          plainConsole <- env.service.create(newPartner.copy(consoleRedirectUri = RedirectUri("http://console.acme.example/complete"))).flip
          plainEdge <- env.service.create(newPartner.copy(firstEdgePublicUrl = "http://login.acme.example")).flip
        yield assertTrue(
          partnerTaken.isInstanceOf[PartnerError.Conflict],
          groupTaken.isInstanceOf[PartnerError.Conflict],
          clientTaken.isInstanceOf[PartnerError.Conflict],
          edgeTaken.isInstanceOf[PartnerError.Conflict],
          noTenant == PartnerError.NotFound("tenant 'default'"),
          badLimits.isInstanceOf[PartnerError.Invalid],
          noEdges.isInstanceOf[PartnerError.Invalid],
          plainConsole.isInstanceOf[PartnerError.Invalid],
          plainEdge.isInstanceOf[PartnerError.Invalid],
          env.groups.create.calls.isEmpty,
          env.partners.create.calls.isEmpty,
        )
      },
    ),
    suite("the kill switch")(
      test(
        "suspend records the status, makes it visible at once, tells auth to reload its clients and voids the unused enrollment tokens; it revokes nothing",
      ) {
        val env = Env()
        for
          _ <- env.registry.findFresh.succeedsWith(Some(acme))
          _ <- env.partners.setStatus.succeedsWith(true)
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.auth.syncConfiguration().succeedsWith(())
          _ <- env.edges.getAllEdges.succeedsWith(Vector(edge(EdgeId("edge-acme-1")), edge(EdgeId("other"), EdgeGroupId("globex"))))
          _ <- env.enrollmentTokens.revoke.succeedsWith(0)
          outcome <- env.service.suspend(acme.id)
        yield assertTrue(
          env.partners.setStatus.calls == List((acme.id, PartnerStatus.Suspended)),
          env.registry.refreshNow.calls.size == 1,
          env.auth.syncConfiguration().calls.size == 1,
          // an enrollment token that was printed and not used is void: of the partner's edges only
          env.enrollmentTokens.revoke.calls == List(EdgeId("edge-acme-1")),
          // nothing is deleted or revoked: no client is read, no grant touched
          env.clients.getPartnerClients.calls.isEmpty,
          outcome == PartnerService.StatusChangeOutcome(authSynced = true),
        )
      },
      test("a suspension auth could not be told of is still a suspension, and says so") {
        val env = Env()
        for
          _ <- env.registry.findFresh.succeedsWith(Some(acme))
          _ <- env.partners.setStatus.succeedsWith(true)
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.auth.syncConfiguration().failsWith(RuntimeException("auth is down"))
          _ <- env.edges.getAllEdges.succeedsWith(Vector.empty)
          outcome <- env.service.suspend(acme.id)
        yield assertTrue(
          env.partners.setStatus.calls == List((acme.id, PartnerStatus.Suspended)),
          outcome == PartnerService.StatusChangeOutcome(authSynced = false),
        )
      },
      test("resume takes a suspended partner back to active, and refuses one that is not suspended") {
        val env = Env()
        for
          _ <- env.registry.findFresh.succeedsWith(Some(acme.copy(status = PartnerStatus.Suspended)))
          _ <- env.partners.setStatus.succeedsWith(true)
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.auth.syncConfiguration().succeedsWith(())
          resumed <- env.service.resume(acme.id)
          _ <- env.registry.findFresh.succeedsWith(Some(acme))
          refused <- env.service.resume(acme.id).flip
          _ <- env.registry.findFresh.succeedsWith(None)
          missing <- env.service.suspend(acme.id).flip
        yield assertTrue(
          env.partners.setStatus.calls == List((acme.id, PartnerStatus.Active)),
          // as a suspension does, a resumption asks auth to reload its clients, and says whether it could
          resumed == PartnerService.StatusChangeOutcome(authSynced = true),
          env.auth.syncConfiguration().calls.size == 1,
          refused.isInstanceOf[PartnerError.Conflict],
          missing == PartnerError.NotFound("partner 'acme'"),
        )
      },
    ),
    test("a resumption auth could not be told of is still a resumption, and says so") {
      val env = Env()
      for
        _ <- env.registry.findFresh.succeedsWith(Some(acme.copy(status = PartnerStatus.Suspended)))
        _ <- env.partners.setStatus.succeedsWith(true)
        _ <- env.registry.refreshNow.succeedsWith(())
        _ <- env.auth.syncConfiguration().failsWith(RuntimeException("auth is down"))
        resumed <- env.service.resume(acme.id)
      yield assertTrue(
        resumed == PartnerService.StatusChangeOutcome(authSynced = false),
        env.partners.setStatus.calls == List((acme.id, PartnerStatus.Active)),
      )
    },
    test("new limits are stored and the clients now outside them are named, not changed") {
      val env = Env()
      val shop = client(ClientId("acme-shop"), ClientApproval.Approved).copy(accessTokenTtl = 500.seconds)
      for
        _ <- env.registry.findFresh.succeedsWith(Some(acme))
        _ <- env.partners.setLimits.succeedsWith(true)
        _ <- env.registry.refreshNow.succeedsWith(())
        _ <- env.clients.getPartnerClients.succeedsWith(Vector(client(ClientId("acme-console"), ClientApproval.Approved), shop))
        outside <- env.service.setLimits(acme.id, limits.copy(accessTokenTtlMax = 300))
      yield assertTrue(
        outside == Vector(PartnerService.ClientOutsideLimits(ClientId("acme-shop"), List("accessTokenTtl"))),
        env.partners.setLimits.calls.map(_._2.scopes) == List(Set(ScopeToken("openid"), ScopeToken("phone"))),
        env.clients.updateClient.calls.isEmpty,
      )
    },
    suite("decisions")(
      test("approving decides the request first, and then applies what was decided through the client service") {
        val env = Env()
        val applied = Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("ACME Shop (verified)")))
        for
          order <- Ref.make(Vector.empty[String])
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.partnerClients.checkOverrides.succeedsWith(Some(applied))
          _ <- env.approvals.decide.returnsZIO((_, _, _, _, _, _) => order.update(_ :+ "decide").as(true))
          _ <- env.partnerClients.applyApproval.returnsZIO((_, _) => order.update(_ :+ "apply"))
          decided <- env.service.approve(waiting.id, Some("admin-1"), Some("ok"), Some(applied))
          events <- order.get
          call = env.approvals.decide.calls.head
        yield assertTrue(
          events == Vector("decide", "apply"),
          env.partnerClients.applyApproval.calls == List((waiting, Some(applied))),
          call._2 == ApprovalStatus.Approved,
          call._3 == Some("admin-1"),
          call._5 == Some("ok"),
          call._6 == Some(applied),
          decided.status == ApprovalStatus.Approved,
          decided.applied == Some(applied),
          decided.requested == waiting.requested,
        )
      },
      test("an approval that lost the race to another decision changes nothing") {
        val env = Env()
        for
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.partnerClients.checkOverrides.succeedsWith(None)
          _ <- env.approvals.decide.succeedsWith(false)
          lost <- env.service.approve(waiting.id, Some("admin-1"), None, None).flip
        yield assertTrue(
          lost.isInstanceOf[PartnerError.Conflict],
          env.partnerClients.applyApproval.calls.isEmpty,
          env.partnerClients.revertApproval.calls.isEmpty,
        )
      },
      test("an approval that cannot be applied is taken back: the client is pending again and the request waits") {
        val env = Env()
        for
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.partnerClients.checkOverrides.succeedsWith(None)
          _ <- env.approvals.decide.succeedsWith(true)
          _ <- env.partnerClients.applyApproval.failsWith(PartnerError.Conflict("the login preset cannot be created"))
          _ <- env.partnerClients.revertApproval.succeedsWith(())
          _ <- env.approvals.reopen.succeedsWith(true)
          failed <- env.service.approve(waiting.id, Some("admin-1"), None, None).flip
        yield assertTrue(
          failed.isInstanceOf[PartnerError.Conflict],
          env.partnerClients.revertApproval.calls == List(waiting),
          env.approvals.reopen.calls == List((waiting.id, ApprovalStatus.Approved)),
        )
      },
      test("an override that is not the operator's to make is refused before anything is decided") {
        val env = Env()
        for
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.partnerClients.checkOverrides.failsWith(PartnerError.Invalid("id cannot be overridden"))
          refused <- env.service.approve(waiting.id, Some("admin-1"), None, Some(Json.Obj("id" -> Json.Str("other-client")))).flip
        yield assertTrue(
          refused.isInstanceOf[PartnerError.Invalid],
          env.approvals.decide.calls.isEmpty,
          env.partnerClients.applyApproval.calls.isEmpty,
        )
      },
      test("a profile change is applied to the name and logo of the partner itself, and to nothing else") {
        val env = Env()
        val profile = waiting.copy(
          kind = ApprovalKind.ProfileChange,
          targetId = acme.id,
          requested = Json.Obj("displayName" -> Json.Str("ACME Ltd"), "logoUri" -> Json.Str("https://acme.example/new.png")),
        )
        for
          _ <- env.approvals.find.succeedsWith(Some(profile))
          _ <- env.partnerClients.checkOverrides.succeedsWith(None)
          _ <- env.registry.findFresh.succeedsWith(Some(acme))
          _ <- env.partners.setNameAndLogo.succeedsWith(true)
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.approvals.decide.succeedsWith(true)
          _ <- env.service.approve(profile.id, Some("admin-1"), None, None)
        yield assertTrue(
          env.partners.setNameAndLogo.calls == List((acme.id, "ACME Ltd", Some("https://acme.example/new.png"))),
          env.partners.updateProfile.calls.isEmpty,
          env.partnerClients.applyApproval.calls.isEmpty,
        )
      },
      test(
        "rejecting decides the request first, records the note, and only the rejection that won reaches the client; a decided request is decided once",
      ) {
        val env = Env()
        for
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.partnerClients.rejectApproval.succeedsWith(())
          _ <- env.approvals.decide.succeedsWith(true)
          rejected <- env.service.reject(waiting.id, Some("admin-1"), "not ours")
          _ <- env.approvals.find.succeedsWith(Some(waiting.copy(status = ApprovalStatus.Approved)))
          again <- env.service.reject(waiting.id, Some("admin-1"), "late").flip
          _ <- env.approvals.find.succeedsWith(Some(waiting))
          _ <- env.approvals.decide.succeedsWith(false)
          lost <- env.service.reject(waiting.id, Some("admin-1"), "lost").flip
          _ <- env.approvals.find.succeedsWith(None)
          missing <- env.service.approve(waiting.id, None, None, None).flip
        yield assertTrue(
          rejected.status == ApprovalStatus.Rejected,
          rejected.note == Some("not ours"),
          env.partnerClients.rejectApproval.calls == List(waiting),
          again.isInstanceOf[PartnerError.Conflict],
          lost.isInstanceOf[PartnerError.Conflict],
          missing == PartnerError.NotFound("approval 'c'"),
        )
      },
    ),
    suite("the partner's own side")(
      test("the first call of an onboarding partner's edge makes it active, and an active one stays as it is") {
        val env = Env()
        for
          _ <- env.partners.activate.succeedsWith(true)
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.service.activate(acme.copy(status = PartnerStatus.Onboarding))
          _ <- env.service.activate(acme)
        yield assertTrue(env.partners.activate.calls == List(acme.id), env.partners.setStatus.calls.isEmpty)
      },
      test("an activation that finds the partner suspended meanwhile changes nothing, and nothing is reloaded") {
        val env = Env()
        for
          // the read said `onboarding`; the row says otherwise, and the conditional update is what decides
          _ <- env.partners.activate.succeedsWith(false)
          _ <- env.service.activate(acme.copy(status = PartnerStatus.Onboarding))
        yield assertTrue(env.partners.activate.calls == List(acme.id), env.registry.refreshNow.calls.isEmpty, env.partners.setStatus.calls.isEmpty)
      },
      test("a profile change is a request for the operator, and only one at a time") {
        val env = Env()
        for
          _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
          _ <- env.approvals.create.succeedsWith(())
          _ <- env.approvals.createIfNoneWaiting.succeedsWith(true)
          approval <- env.service.requestProfileChange(acme, "staff-1", "ACME Ltd", None)
          _ <- env.approvals.waitingFor.succeedsWith(Vector(approval))
          again <- env.service.requestProfileChange(acme, "staff-1", "ACME Inc", None).flip
          empty <- env.service.requestProfileChange(acme, "staff-1", "  ", None).flip
        yield assertTrue(
          approval.kind == ApprovalKind.ProfileChange,
          approval.targetId == acme.id,
          approval.requested == Json.Obj("displayName" -> Json.Str("ACME Ltd")),
          approval.current == Some(Json.Obj("displayName" -> Json.Str("ACME"))),
          approval.outsideLimits == List("displayName", "logoUri"),
          again.isInstanceOf[PartnerError.Conflict],
          empty.isInstanceOf[PartnerError.Invalid],
        )
      },
      test("another edge is enrolled within maxEdges, in the partner's group, without a key, with a token; one over the limit is refused") {
        val env = Env()
        for
          _ <- env.edges.getAllEdges.succeedsWith(Vector(edge(EdgeId("other"), EdgeGroupId("globex"))))
          _ <- env.edges.findFresh.returnsZIO(id =>
            ZIO.succeed(Option.when(id == EdgeId("edge-acme-2"))(edge(id)).filter(_ => env.edges.registerEdgeWithoutKey.calls.nonEmpty)),
          )
          _ <- env.edges.registerEdgeWithoutKey.succeedsWith(())
          _ <- env.edges.sync().succeedsWith(())
          _ <- env.identity.issueEnrollmentToken.succeedsWith(EdgeIdentityService.IssuedToken("TOKEN", now.plusSeconds(86400)))
          enrolled <- env.service.addEdge(acme, EdgeId("edge-acme-2"), "https://login2.acme.example")
          _ <- env.edges.getAllEdges.succeedsWith(Vector(edge(EdgeId("edge-acme-1"))))
          full <- env.service.addEdge(acme, EdgeId("edge-acme-3"), "https://login3.acme.example").flip
          plain <- env.service.addEdge(acme.copy(limits = limits.copy(maxEdges = 5)), EdgeId("edge-acme-4"), "http://login4.acme.example").flip
        yield assertTrue(
          env.edges.registerEdgeWithoutKey.calls == List((EdgeId("edge-acme-2"), EdgeGroupId("acme"), Some("https://login2.acme.example"))),
          enrolled.token.token == "TOKEN",
          enrolled.edge.publicKey.isEmpty,
          env.edges.registerEdge.calls.isEmpty,
          full.isInstanceOf[PartnerError.Invalid],
          plain.isInstanceOf[PartnerError.Invalid],
        )
      },
      test("an edge the repository has no room for is the partner's 400, whatever the cache said") {
        val env = Env()
        for
          _ <- env.edges.getAllEdges.succeedsWith(Vector.empty)
          _ <- env.edges.findFresh.succeedsWith(None)
          _ <- env.edges.registerEdgeWithoutKey.failsWith(PartnerEdgeLimitReached(acme.id, 2))
          full <- env.service.addEdge(acme, EdgeId("edge-acme-2"), "https://login2.acme.example").flip
        yield assertTrue(full.isInstanceOf[PartnerError.Invalid], env.identity.issueEnrollmentToken.calls.isEmpty)
      },
      test("a profile or settings write that finds the row moved on is 409, and writes nothing") {
        val env = Env()
        for
          _ <- env.registry.findFresh.succeedsWith(Some(acme.copy(version = 4L)))
          _ <- env.partners.updateProfile.succeedsWith(false)
          _ <- env.partners.setSettings.succeedsWith(false)
          profile <- env.service.updateProfile(acme.id, 3L, "ACME", None, acme.contact, None).flip
          settings <- env.service.setSettings(acme, 3L, acme.contact, PartnerSettings.empty).flip
        yield assertTrue(
          profile.isInstanceOf[PartnerError.Conflict],
          settings.isInstanceOf[PartnerError.Conflict],
          // each names the version the CALLER read (not one the service reads for itself, which would protect nothing)
          env.partners.updateProfile.calls.map(_._2) == List(3L),
          env.partners.setSettings.calls.map(_._2) == List(3L),
          env.registry.refreshNow.calls.isEmpty,
        )
      },
      test("a token for an edge of the partner's group lives a day; an edge of another group is not the partner's") {
        val env = Env()
        for
          _ <- env.edges.getAllEdges.succeedsWith(Vector(edge(EdgeId("edge-acme-1")), edge(EdgeId("other"), EdgeGroupId("globex"))))
          _ <- env.identity.issueEnrollmentToken.succeedsWith(EdgeIdentityService.IssuedToken("TOKEN", now.plusSeconds(86400)))
          token <- env.service.enrollmentToken(acme, EdgeId("edge-acme-1"))
          foreign <- env.service.enrollmentToken(acme, EdgeId("other")).flip
        yield assertTrue(
          token.token == "TOKEN",
          env.identity.issueEnrollmentToken.calls == List((EdgeId("edge-acme-1"), 24.hours)),
          foreign == PartnerError.NotFound("edge 'other'"),
        )
      },
    ),
    suite("the rate limits of the group (the partner's to manage, once for the group)")(
      test("setting them writes the partner once, not an edge, and the cache is told at once") {
        val env = Env()
        val policies = RateLimitPolicies.recommended
        for
          _ <- env.partners.setRateLimits.succeedsWith(Some(acme.copy(rateLimits = Some(policies), rateLimitsRevision = 3)))
          _ <- env.registry.refreshNow.succeedsWith(())
          set <- env.service.setRateLimits(acme, Some(policies), Some(2))
        yield assertTrue(
          env.partners.setRateLimits.calls == List((acme.id, Some(policies), Some(2))),
          // what an edge is served is the partner's value: no edge is written
          env.edges.patchPolicies.calls.isEmpty,
          env.registry.refreshNow.calls.size == 1,
          set == PartnerService.GroupRateLimits(Some(policies), 3),
        )
      },
      test("an empty set is stored as none, and the validation is done once, before any write") {
        val env = Env()
        // two policies of one name are refused by the same validation as an edge's own
        val invalid = RateLimitPolicies(RateLimitPolicies.recommended.policies ++ RateLimitPolicies.recommended.policies.take(1), 1)
        for
          _ <- env.partners.setRateLimits.succeedsWith(Some(acme))
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.service.setRateLimits(acme, Some(RateLimitPolicies(Nil, 1)), None)
          refused <- env.service.setRateLimits(acme, Some(invalid), None).flip
        yield assertTrue(
          env.partners.setRateLimits.calls == List((acme.id, None, None)),
          refused.isInstanceOf[PartnerError.Invalid],
        )
      },
      test("a revision the limits have moved on from is a conflict") {
        val env = Env()
        for
          _ <- env.partners.setRateLimits.failsWith(PartnerRateLimitsChanged(acme.id, 5))
          refused <- env.service.setRateLimits(acme, None, Some(4)).flip
        yield assertTrue(refused.isInstanceOf[PartnerError.Conflict], env.registry.refreshNow.calls.isEmpty)
      },
      test("what is read is what the partner stores, with its revision; a partner that has none set reads none") {
        val env = Env()
        for
          _ <- env.registry.findFresh.succeedsWith(Some(acme.copy(rateLimits = Some(RateLimitPolicies.recommended), rateLimitsRevision = 2)))
          read <- env.service.rateLimits(acme)
          _ <- env.registry.findFresh.succeedsWith(Some(acme))
          none <- env.service.rateLimits(acme)
        yield assertTrue(
          read == PartnerService.GroupRateLimits(Some(RateLimitPolicies.recommended), 2),
          none == PartnerService.GroupRateLimits(None, 0),
        )
      },
      test("a partner that has no edge can set them, and an edge that joins copies nothing") {
        val env = Env()
        for
          _ <- env.partners.setRateLimits.succeedsWith(Some(acme.copy(rateLimits = Some(RateLimitPolicies.recommended), rateLimitsRevision = 1)))
          _ <- env.registry.refreshNow.succeedsWith(())
          _ <- env.edges.getAllEdges.succeedsWith(Vector.empty)
          _ <- env.service.setRateLimits(acme, Some(RateLimitPolicies.recommended), None)
          _ <- env.edges.getAllEdges.succeedsWith(Vector(edge(EdgeId("edge-acme-1"))))
          _ <- env.edges.findFresh.returnsZIO(id =>
            ZIO.succeed(Option.when(id == EdgeId("edge-acme-2") && env.edges.registerEdgeWithoutKey.calls.nonEmpty)(edge(id))),
          )
          _ <- env.edges.registerEdgeWithoutKey.succeedsWith(())
          _ <- env.edges.sync().succeedsWith(())
          _ <- env.identity.issueEnrollmentToken.succeedsWith(EdgeIdentityService.IssuedToken("TOKEN", now.plusSeconds(86400)))
          _ <- env.service.addEdge(acme.copy(limits = acme.limits.copy(maxEdges = 5)), EdgeId("edge-acme-2"), "https://login2.acme.example")
        yield assertTrue(env.edges.patchPolicies.calls.isEmpty)
      },
    ),
    suite("what the operator and the partner may write as text and numbers")(
      test("domains are names of at least two labels, in any case, and nothing else") {
        val good = List("acme.example", "Shop.ACME.example", "a-b.c-d.example", "xn--80ak6aa92e.com")
        val bad = List(
          "",
          "acme",
          "http://acme.example",
          "acme.example/path",
          "acme.example:8443",
          "*.acme.example",
          "-acme.example",
          "192.168.0.1",
          "acme..example",
          " acme.example",
        )
        assertTrue(good.forall(PartnerService.isDomain), !bad.exists(PartnerService.isDomain))
      },
      test("limits with a domain that is none are refused, for the redirect and the resource domains alike") {
        assertTrue(
          PartnerService.validateLimits(limits.copy(redirectDomains = Set("acme.example", "not a domain"))).isDefined,
          PartnerService.validateLimits(limits.copy(resourceDomains = Some(Set("http://x.example")))).isDefined,
          PartnerService.validateLimits(limits.copy(redirectDomains = Set("acme.example"), resourceDomains = Some(Set("api.acme.example")))).isEmpty,
        )
      },
      test("a name and a logo are validated whoever sends them: text of a sensible length, and an https address") {
        assertTrue(
          PartnerService.validateProfile("ACME", Some("https://acme.example/logo.png")).isEmpty,
          PartnerService.validateProfile("ACME", None).isEmpty,
          PartnerService.validateProfile("  ", None).isDefined,
          PartnerService.validateProfile("x" * (PartnerService.MaxDisplayNameLength + 1), None).isDefined,
          PartnerService.validateProfile("bad\u0000name", None).isDefined,
          PartnerService.validateProfile("ACME", Some("http://acme.example/logo.png")).isDefined,
          PartnerService.validateProfile("ACME", Some("javascript:alert(1)")).isDefined,
          PartnerService.validateProfile("ACME", Some("https://" + "a" * PartnerService.MaxLogoUriLength + ".example")).isDefined,
        )
      },
      test("a profile change that is not valid is refused before anything is queued, and so is an operator's change") {
        val env = Env()
        for
          change <- env.service.requestProfileChange(acme, "staff-1", "ACME", Some("http://acme.example/logo.png")).flip
          update <- env.service.updateProfile(acme.id, 0L, "ACME", Some("ftp://acme.example/logo.png"), acme.contact, None).flip
        yield assertTrue(
          change.isInstanceOf[PartnerError.Invalid],
          update.isInstanceOf[PartnerError.Invalid],
          env.approvals.createIfNoneWaiting.calls.isEmpty,
          env.partners.updateProfile.calls.isEmpty,
        )
      },
    ),
  ) @@ TestAspect.silentLogging

  private val waiting = PartnerApprovalRecord(
    id = ApprovalId("c"),
    partnerId = acme.id,
    kind = ApprovalKind.ClientCreate,
    targetId = "acme-shop",
    requested = Json.Obj("id" -> Json.Str("acme-shop")),
    current = None,
    applied = None,
    outsideLimits = List("clientName", "logoUri"),
    requestedBy = "staff-1",
    requestedAt = now,
    status = ApprovalStatus.Waiting,
    decidedBy = None,
    decidedAt = None,
    note = None,
  )
