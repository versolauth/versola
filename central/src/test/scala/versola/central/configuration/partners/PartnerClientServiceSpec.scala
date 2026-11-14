package versola.central.configuration.partners

import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.clients.{
  ApplicationType,
  AuthFlow,
  AuthMethod,
  AuthorizationPresetService,
  ClientApproval,
  ClientId,
  ClientKind,
  ClientTemplate,
  ConsentFlow,
  InvalidRegistrationConfiguration,
  MutualTlsAuth,
  MutualTlsSubjectType,
  OAuthClientRecord,
  OAuthClientService,
  PresetId,
}
import versola.central.configuration.edges.{EdgeCertificate, EdgeGroupId}
import versola.central.configuration.scopes.ScopeToken
import versola.central.configuration.tenants.TenantId
import versola.util.RedirectUri
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant

/** What a partner asks for about its clients and what happens to it (#549 decision 7): a creation always waits, a change
  * inside the limits applies, one touching the consent screen or the limits waits, and the operator's decision is applied
  * to the client. */
object PartnerClientServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val group = EdgeGroupId("acme")
  private val groupAuth = MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, EdgeCertificate.groupUriFor(group))
  private val now = Instant.parse("2026-11-10T10:00:00Z")

  private val acme = PartnerRecord(
    id = PartnerId("acme"),
    tenantId = TenantId("default"),
    displayName = "ACME",
    logoUri = None,
    contact = PartnerContact("ops@acme.example", None),
    status = PartnerStatus.Active,
    limits = PartnerLimits(
      scopes = Set(ScopeToken("openid"), ScopeToken("phone")),
      redirectDomains = Set("acme.example"),
      maxClients = 5,
      maxEdges = 2,
      accessTokenTtlMax = 600,
      refreshTokenTtlMax = 86400,
      clientAuthMethods = Set(AuthMethod.tls_client_auth),
      resourceDomains = None,
    ),
    groupId = group,
    consoleClientId = ClientId("acme-console"),
    settings = PartnerSettings.empty,
    reviewAt = None,
    createdAt = now,
    version = 0L,
  )

  private val request = PartnerClientRequest(
    id = ClientId("acme-shop"),
    kind = PartnerClientKind.web,
    clientName = Map("en" -> "ACME Shop"),
    logoUri = Some("https://acme.example/logo.png"),
    policyUri = None,
    tosUri = None,
    redirectUris = Set(RedirectUri("https://shop.acme.example/cb")),
    scope = Set(ScopeToken("openid")),
    authMethod = AuthMethod.tls_client_auth,
    jwks = None,
    accessTokenTtl = 300,
    refreshTokenTtl = Some(3600),
    backChannelLogoutUri = None,
    consentFlow = None,
  )

  private def client(id: ClientId, approval: ClientApproval, partnerId: Option[PartnerId] = Some(acme.id)): OAuthClientRecord =
    OAuthClientRecord(
      id = id,
      tenantId = acme.tenantId,
      clientName = Map("en" -> "ACME Shop"),
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
      logoUri = Some("https://acme.example/logo.png"),
      policyUri = None,
      tosUri = None,
      consentFlow = Some(ConsentFlow(allowPartial = false, rememberDuration = None, requiredScopes = Set.empty, offlineAccessOptional = false)),
      dpopBoundAccessTokens = false,
      dpopSigningAlgs = Set.empty,
      dpopMinRsaKeySize = None,
      authMethod = AuthMethod.tls_client_auth,
      mtlsAuth = Some(groupAuth),
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
      partnerId = partnerId,
      approval = approval,
    )

  private class Env:
    val clients = stub[OAuthClientService]
    val presets = stub[AuthorizationPresetService]
    val approvals = stub[PartnerApprovalRepository]
    val service = PartnerClientService.Impl(clients, presets, approvals)

  private def registered(env: Env) =
    env.clients.registerClient.returnsZIO((_, _, _, _, _) => ZIO.succeed(versola.central.configuration.clients.RegisteredClient(None, now, None)))

  def spec = suite("PartnerClientService")(
    // Owner requirement (#962): a partner's client is never bound to the operator's tenant edges or group, whatever it sends
    test("what a partner's client is registered as is the partner's: its tenant, its owner and its group's certificate, for either kind") {
      val registered =
        for
          kind <- List(PartnerClientKind.web, PartnerClientKind.native)
          method <- List(AuthMethod.tls_client_auth, AuthMethod.private_key_jwt)
        yield request.copy(kind = kind, authMethod = method).toCreateClientRequest(acme)
      assertTrue(
        registered.size == 4,
        registered.forall(r => r.tenantId == acme.tenantId && r.partnerId.contains(acme.id)),
        registered.filter(_.authMethod == AuthMethod.tls_client_auth).forall(
          _.mtlsAuth.contains(MutualTlsAuth.TlsClientAuth(MutualTlsSubjectType.san_uri, s"urn:versola:edge-group:${acme.groupId}")),
        ),
        registered.filter(_.authMethod != AuthMethod.tls_client_auth).forall(_.mtlsAuth.isEmpty),
        // no certificate-bound tokens, no edge credentials of the operator's, no certificate issued for it
        registered.forall(r =>
          !r.certificateBoundAccessTokens && r.edgeSigningKey.isEmpty && r.edgeClientCertificate.isEmpty && !r.issueEdgeClientCertificate,
        ),
      )
    },
    test(
      "a new client is registered pending, outside the limits or not, and its request names the violations and always the consent screen's fields",
    ) {
      val env = Env()
      val wide = request.copy(scope = Set(ScopeToken("openid"), ScopeToken("email")))
      for
        _ <- registered(env)
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Pending).copy(scope = wide.scope)))
        _ <- env.approvals.create.succeedsWith(())
        _ <- env.approvals.createIfNoneWaiting.succeedsWith(true)
        (created, approval) <- env.service.create(acme, wide, requestedBy = "staff-1")
        registration = env.clients.registerClient.calls.head
      yield assertTrue(
        registration._1.partnerId == Some(acme.id),
        registration._1.mtlsAuth == Some(groupAuth),
        registration._1.requirePushedAuthorizationRequests,
        registration._1.consentFlow == Some(PartnerClientRequest.DefaultConsent),
        registration._1.authFlow == Some(AuthFlow.default),
        registration._4 == ClientApproval.Pending,
        registration._5,
        created.approval == ClientApproval.Pending,
        approval.kind == ApprovalKind.ClientCreate,
        approval.status == ApprovalStatus.Waiting,
        approval.targetId == "acme-shop",
        approval.requestedBy == "staff-1",
        approval.outsideLimits == List("scope", "clientName", "logoUri", "policyUri", "tosUri", "consentFlow"),
        approval.requested.get("id") == Some(Json.Str("acme-shop")),
        env.approvals.create.calls.size == 1,
      )
    },
    test("the console client's id cannot be taken, and a registration central refuses is the partner's 400") {
      val env = Env()
      for
        console <- env.service.create(acme, request.copy(id = acme.consoleClientId), "staff-1").flip
        _ <- env.clients.registerClient.returnsZIO((_, _, _, _, _) =>
          ZIO.fail(versola.central.configuration.clients.InvalidRegistrationConfiguration(request.id, "partner client is never a service client")),
        )
        refused <- env.service.create(acme, request, "staff-1").flip
      yield assertTrue(
        console == PartnerError.Conflict("client 'acme-console' is the partner's console client"),
        refused.isInstanceOf[PartnerError.Invalid],
        env.approvals.create.calls.isEmpty,
      )
    },
    test("a change inside the limits that leaves the consent screen alone applies at once") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Approved)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.clients.updateClient.returnsZIO((_, _, _, _) => ZIO.unit)
        result <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(redirectUris = Some(Set(RedirectUri("https://pay.acme.example/cb")))),
          "staff-1",
        )
        update = env.clients.updateClient.calls.head._1
      yield assertTrue(
        result == Right(()),
        update.redirectUris.add == Set(RedirectUri("https://pay.acme.example/cb")),
        update.redirectUris.remove == Set(RedirectUri("https://shop.acme.example/cb")),
        env.approvals.create.calls.isEmpty,
      )
    },
    test("a change of the name or logo, or outside the limits, waits for the operator and changes nothing yet") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Approved)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.approvals.create.succeedsWith(())
        _ <- env.approvals.createIfNoneWaiting.succeedsWith(true)
        renamed <- env.service.update(acme, PartnerClientUpdate.of(request.id).copy(clientName = Some(Map("en" -> "ACME Pay"))), "staff-1")
        widened <- env.service.update(acme, PartnerClientUpdate.of(request.id).copy(accessTokenTtl = Some(3600L)), "staff-1")
      yield assertTrue(
        renamed.left.toOption.exists(a => a.kind == ApprovalKind.ClientChange && a.outsideLimits == List("clientName")),
        renamed.left.toOption.exists(_.current == Some(Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("ACME Shop"))))),
        widened.left.toOption.exists(_.outsideLimits == List("accessTokenTtl")),
        env.clients.updateClient.calls.isEmpty,
        env.approvals.create.calls.size + env.approvals.createIfNoneWaiting.calls.size == 2,
      )
    },
    test("a change of scope or of the refresh token's life outside the limits waits for the operator and names the field") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Approved)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.approvals.createIfNoneWaiting.succeedsWith(true)
        scoped <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(scope = Some(Set(ScopeToken("openid"), ScopeToken("payments")))),
          "staff-1",
        )
        longer <- env.service.update(acme, PartnerClientUpdate.of(request.id).copy(refreshTokenTtl = Some(Int.MaxValue.toLong)), "staff-1")
      yield assertTrue(
        scoped.left.toOption.exists(_.outsideLimits == List("scope")),
        longer.left.toOption.exists(_.outsideLimits == List("refreshTokenTtl")),
        env.clients.updateClient.calls.isEmpty,
      )
    },
    test("a change that two requests make at once is queued once: the one that loses is told there is a change waiting") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Approved)))
        // the check against what is waiting found none, and the insert under the partner's lock found one
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.approvals.createIfNoneWaiting.succeedsWith(false)
        lost <- env.service.update(acme, PartnerClientUpdate.of(request.id).copy(clientName = Some(Map("en" -> "Other"))), "staff-1").flip
      yield assertTrue(lost.isInstanceOf[PartnerError.Conflict], env.clients.updateClient.calls.isEmpty)
    },
    test("a client that is not the partner's, or still pending, or already waiting for a change, cannot be changed") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Approved, partnerId = Some(PartnerId("globex")))))
        foreign <- env.service.update(acme, PartnerClientUpdate.of(request.id), "staff-1").flip
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Pending)))
        pending <- env.service.update(acme, PartnerClientUpdate.of(request.id), "staff-1").flip
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Approved)))
        _ <- env.approvals.waitingFor.succeedsWith(Vector(waitingChange))
        busy <- env.service.update(acme, PartnerClientUpdate.of(request.id), "staff-1").flip
      yield assertTrue(
        foreign == PartnerError.NotFound("client 'acme-shop'"),
        pending.isInstanceOf[PartnerError.Conflict],
        busy.isInstanceOf[PartnerError.Conflict],
      )
    },
    test("withdrawing a creation deletes the client; withdrawing a change leaves it as registered") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Pending)))
        _ <- env.approvals.waitingFor.succeedsWith(Vector(waitingCreate))
        _ <- env.approvals.decide.succeedsWith(true)
        _ <- env.clients.deleteClient.succeedsWith(())
        creation <- env.service.withdraw(acme, request.id)
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Approved)))
        _ <- env.approvals.waitingFor.succeedsWith(Vector(waitingChange))
        change <- env.service.withdraw(acme, request.id)
      yield assertTrue(
        creation.status == ApprovalStatus.Withdrawn,
        change.status == ApprovalStatus.Withdrawn,
        env.clients.deleteClient.calls == List(request.id),
        env.approvals.decide.calls.map(_._2) == List(ApprovalStatus.Withdrawn, ApprovalStatus.Withdrawn),
      )
    },
    test("a change of the policy or terms link, or of the consent flow, waits for the operator as the name does") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Approved)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.approvals.create.succeedsWith(())
        _ <- env.approvals.createIfNoneWaiting.succeedsWith(true)
        policy <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(policyUri = Some(versola.util.Patch.Modified("https://acme.example/privacy"))),
          "staff-1",
        )
        terms <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(tosUri = Some(versola.util.Patch.Modified("https://acme.example/terms"))),
          "staff-1",
        )
        consent <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(consentFlow = Some(PartnerClientRequest.DefaultConsent.copy(allowPartial = true))),
          "staff-1",
        )
      yield assertTrue(
        policy.left.toOption.exists(_.outsideLimits == List("policyUri")),
        terms.left.toOption.exists(_.outsideLimits == List("tosUri")),
        consent.left.toOption.exists(_.outsideLimits == List("consentFlow")),
        env.clients.updateClient.calls.isEmpty,
      )
    },
    test("a back-channel address outside the partner's domains is refused, not queued; one under them is a change like any other") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Approved)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.approvals.waitingFor.succeedsWith(Vector.empty)
        _ <- env.clients.updateClient.returnsZIO((_, _, _, _) => ZIO.unit)
        outside <- ZIO.foreach(List(
          "https://internal.example/logout",
          "https://10.0.0.5/logout",
          "http://shop.acme.example/logout",
          "https://localhost/logout",
        )): uri =>
          env.service.update(
            acme,
            PartnerClientUpdate.of(request.id).copy(backChannelLogoutUri = Some(versola.util.Patch.Modified(uri))),
            "staff-1",
          ).flip
        inside <- env.service.update(
          acme,
          PartnerClientUpdate.of(request.id).copy(backChannelLogoutUri = Some(versola.util.Patch.Modified("https://shop.acme.example/logout"))),
          "staff-1",
        )
      yield assertTrue(
        outside.forall(_.isInstanceOf[PartnerError.Invalid]),
        env.clients.updateClient.calls.size == 1,
        inside == Right(()),
      )
    },
    test("a client id that ends in -console is no partner's to register, and a partner that is full is the partner's 400") {
      val env = Env()
      for
        console <- env.service.create(acme, request.copy(id = ClientId("globex-console")), "staff-1").flip
        // what the client service answers when the repository, under the partner's lock, finds the partner full
        _ <- env.clients.registerClient.returnsZIO((_, _, _, _, _) =>
          ZIO.fail(InvalidRegistrationConfiguration(request.id, "partner 'acme' may register at most 5 clients")),
        )
        full <- env.service.create(acme, request, "staff-1").flip
      yield assertTrue(console.isInstanceOf[PartnerError.Invalid], full.isInstanceOf[PartnerError.Invalid], env.approvals.create.calls.isEmpty)
    },
    test("a withdrawal that lost to the operator's decision deletes nothing") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Pending)))
        _ <- env.approvals.waitingFor.succeedsWith(Vector(waitingCreate))
        // the operator approved between the read and the decision
        _ <- env.approvals.decide.succeedsWith(false)
        lost <- env.service.withdraw(acme, request.id).flip
      yield assertTrue(lost.isInstanceOf[PartnerError.Conflict], env.clients.deleteClient.calls.isEmpty)
    },
    test("only what a partner asked for can be overridden: not the id, the kind or the way the client authenticates") {
      val env = Env()
      for
        id <- env.service.checkOverrides(waitingCreate, Some(Json.Obj("id" -> Json.Str("globex-shop")))).flip
        kind <- env.service.checkOverrides(waitingCreate, Some(Json.Obj("kind" -> Json.Str("native")))).flip
        method <- env.service.checkOverrides(waitingCreate, Some(Json.Obj("authMethod" -> Json.Str("none")))).flip
        profile <- env.service.checkOverrides(waitingCreate.copy(kind = ApprovalKind.ProfileChange), Some(Json.Obj("scope" -> Json.Arr()))).flip
        ok <- env.service.checkOverrides(
          waitingCreate.copy(requested =
            Json.Obj(
              "id" -> Json.Str("acme-shop"),
              "kind" -> Json.Str("web"),
              "clientName" -> Json.Obj("en" -> Json.Str("ACME Shop")),
              "redirectUris" -> Json.Arr(Json.Str("https://shop.acme.example/cb")),
              "scope" -> Json.Arr(Json.Str("openid")),
              "authMethod" -> Json.Str("tls_client_auth"),
              "accessTokenTtl" -> Json.Num(300),
            ),
          ),
          Some(Json.Obj("accessTokenTtl" -> Json.Num(600))),
        )
        none <- env.service.checkOverrides(waitingCreate, Some(Json.Obj()))
      yield assertTrue(
        id.isInstanceOf[PartnerError.Invalid],
        kind.isInstanceOf[PartnerError.Invalid],
        method.isInstanceOf[PartnerError.Invalid],
        profile.isInstanceOf[PartnerError.Invalid],
        ok.exists(_.get("accessTokenTtl") == Some(Json.Num(600))),
        none.isEmpty,
      )
    },
    test("an approval with a new redirect URI is applied from the client as stored now: the preset is the new first URI") {
      val env = Env()
      val before = client(request.id, ClientApproval.Pending)
      val overridden = before.copy(redirectUris = Set(RedirectUri("https://other.acme.example/cb")))
      val approved = overridden.copy(approval = ClientApproval.Approved)
      val applied = Some(Json.Obj(
        "id" -> Json.Str("acme-shop"),
        "kind" -> Json.Str("web"),
        "clientName" -> Json.Obj("en" -> Json.Str("ACME Shop")),
        "redirectUris" -> Json.Arr(Json.Str("https://other.acme.example/cb")),
        "scope" -> Json.Arr(Json.Str("openid")),
        "authMethod" -> Json.Str("tls_client_auth"),
        "accessTokenTtl" -> Json.Num(300),
      ))
      for
        // the first read is before the update, the second after: the second is what the preset is made from
        _ <- env.clients.findClientFresh.returnsZIOOnCall:
          case 1 => ZIO.succeed(Some(before))
          case _ => ZIO.succeed(Some(approved))
        _ <- env.clients.updateClient.returnsZIO((_, _, _, _) => ZIO.unit)
        _ <- env.clients.setApproval.succeedsWith(true)
        _ <- env.clients.refreshNow.succeedsWith(())
        _ <- env.presets.savePresets.succeedsWith(Right(()))
        _ <- env.service.applyApproval(waitingCreate, applied)
      yield assertTrue(
        env.clients.updateClient.calls.head._1.redirectUris.add == Set(RedirectUri("https://other.acme.example/cb")),
        env.presets.savePresets.calls.head.presets.head.redirectUri == RedirectUri("https://other.acme.example/cb"),
        env.clients.setApproval.calls == List((request.id, ClientApproval.Approved)),
      )
    },
    test("reverting an approval puts a created client back to pending, and leaves a change alone") {
      val env = Env()
      for
        _ <- env.clients.setApproval.succeedsWith(true)
        _ <- env.service.revertApproval(waitingCreate)
        _ <- env.service.revertApproval(waitingChange)
      yield assertTrue(env.clients.setApproval.calls == List((request.id, ClientApproval.Pending)))
    },
    test("approving a creation applies the operator's overrides, sets the client approved and gives it its login preset") {
      val env = Env()
      val stored = client(request.id, ClientApproval.Pending)
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(stored))
        _ <- env.clients.updateClient.returnsZIO((_, _, _, _) => ZIO.unit)
        _ <- env.clients.setApproval.succeedsWith(true)
        _ <- env.clients.refreshNow.succeedsWith(())
        _ <- env.presets.savePresets.succeedsWith(Right(()))
        applied <- env.service.checkOverrides(waitingCreate, Some(Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("ACME Shop (verified)")))))
        _ <- env.service.applyApproval(waitingCreate, applied)
        update = env.clients.updateClient.calls.head
        preset = env.presets.savePresets.calls.head
      yield assertTrue(
        applied.exists(_.get("clientName") == Some(Json.Obj("en" -> Json.Str("ACME Shop (verified)")))),
        applied.exists(_.get("id") == Some(Json.Str("acme-shop"))),
        update._1.clientName == Some(Map("en" -> "ACME Shop (verified)")),
        update._4,
        env.clients.setApproval.calls == List((request.id, ClientApproval.Approved)),
        preset.clientId == request.id,
        preset.presets.map(_.id) == List(PresetId("acme-shop")),
        preset.presets.head.redirectUri == RedirectUri("https://shop.acme.example/cb"),
        preset.presets.head.postLoginRedirectUri == RedirectUri("https://shop.acme.example/cb"),
        preset.presets.head.scope == stored.scope,
      )
    },
    test("approving a creation as asked changes nothing but the approval, and records no `applied`") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Pending)))
        _ <- env.clients.setApproval.succeedsWith(true)
        _ <- env.clients.refreshNow.succeedsWith(())
        _ <- env.presets.savePresets.succeedsWith(Right(()))
        applied <- env.service.checkOverrides(waitingCreate, None)
        _ <- env.service.applyApproval(waitingCreate, applied)
      yield assertTrue(applied.isEmpty, env.clients.updateClient.calls.isEmpty, env.clients.setApproval.calls.size == 1)
    },
    test("approving a change applies what was asked, with the overrides over it, outside the limits or not") {
      val env = Env()
      for
        _ <- env.clients.findClientFresh.succeedsWith(Some(client(request.id, ClientApproval.Approved)))
        _ <- env.clients.updateClient.returnsZIO((_, _, _, _) => ZIO.unit)
        applied <- env.service.checkOverrides(waitingChange, Some(Json.Obj("accessTokenTtl" -> Json.Num(900))))
        _ <- env.service.applyApproval(waitingChange, applied)
        update = env.clients.updateClient.calls.head
      yield assertTrue(
        update._1.clientName == Some(Map("en" -> "ACME Pay")),
        update._1.accessTokenTtl == Some(900L),
        update._4,
        env.clients.setApproval.calls.isEmpty,
        env.presets.savePresets.calls.isEmpty,
        applied.isDefined,
      )
    },
    test("rejecting a creation sets the client rejected; rejecting a change leaves it") {
      val env = Env()
      for
        _ <- env.clients.setApproval.succeedsWith(true)
        _ <- env.service.rejectApproval(waitingCreate)
        _ <- env.service.rejectApproval(waitingChange)
      yield assertTrue(env.clients.setApproval.calls == List((request.id, ClientApproval.Rejected)))
    },
    test("the list says where each client stands, leaves the console client out, and never another partner's") {
      val env = Env()
      val approved = client(ClientId("acme-shop"), ClientApproval.Approved)
      val pending = client(ClientId("acme-pay"), ClientApproval.Pending)
      val rejected = client(ClientId("acme-old"), ClientApproval.Rejected)
      val console = client(acme.consoleClientId, ClientApproval.Approved)
      for
        _ <- env.clients.getPartnerClients.succeedsWith(Vector(approved, pending, rejected, console))
        _ <- env.approvals.list.succeedsWith(Vector(
          waitingChange,
          waitingCreate.copy(id = ApprovalId("p"), targetId = "acme-pay", outsideLimits = List("scope", "clientName", "logoUri")),
          waitingCreate.copy(id = ApprovalId("o"), targetId = "acme-old", status = ApprovalStatus.Rejected, note = Some("no")),
        ))
        views <- env.service.list(acme)
      yield assertTrue(
        views.map(_.client.id) == Vector(ClientId("acme-old"), ClientId("acme-pay"), ClientId("acme-shop")),
        views.map(_.approval.kind) == Vector(
          PartnerClientService.RequestKind.create,
          PartnerClientService.RequestKind.create,
          PartnerClientService.RequestKind.change,
        ),
        views.map(_.approval.status) == Vector(
          PartnerClientService.RequestState.rejected,
          PartnerClientService.RequestState.pending,
          PartnerClientService.RequestState.pending,
        ),
        views(0).approval.note == Some("no"),
        views(1).approval.waitingFields == List("scope", "clientName", "logoUri"),
        views(2).approval.waitingFields == List("clientName"),
        env.approvals.list.calls == List((None, None, Some(acme.id), PartnerClientService.HistoryLimit)),
      )
    },
  ) @@ TestAspect.silentLogging

  private val waitingCreate = PartnerApprovalRecord(
    id = ApprovalId("c"),
    partnerId = acme.id,
    kind = ApprovalKind.ClientCreate,
    targetId = "acme-shop",
    requested = Json.Obj(
      "id" -> Json.Str("acme-shop"),
      "kind" -> Json.Str("web"),
      "clientName" -> Json.Obj("en" -> Json.Str("ACME Shop")),
      "logoUri" -> Json.Str("https://acme.example/logo.png"),
      "redirectUris" -> Json.Arr(Json.Str("https://shop.acme.example/cb")),
      "scope" -> Json.Arr(Json.Str("openid")),
      "authMethod" -> Json.Str("tls_client_auth"),
      "accessTokenTtl" -> Json.Num(300),
      "refreshTokenTtl" -> Json.Num(3600),
    ),
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

  private val waitingChange = waitingCreate.copy(
    id = ApprovalId("ch"),
    kind = ApprovalKind.ClientChange,
    requested = Json.Obj("id" -> Json.Str("acme-shop"), "clientName" -> Json.Obj("en" -> Json.Str("ACME Pay"))),
    current = Some(Json.Obj("clientName" -> Json.Obj("en" -> Json.Str("ACME Shop")))),
    outsideLimits = List("clientName"),
  )
