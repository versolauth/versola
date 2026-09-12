package versola.loadgen.protocol

import versola.loadgen.provision.FakeCentral.*
import versola.loadgen.provision.{CampaignBlueprint, FakeCentral, ProvisionFixtures}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID

/** Asserts the wire shape of every admin call and the create-or-update choice behind it.
  *
  * These payloads are hand-built against central's DTOs, so nothing but a test notices when a
  * field is misspelled: central answers a 200, ignores the member it does not know, and the
  * campaign fails hours later with a 403 nobody can trace back to provisioning.
  */
object HttpAdminClientSpec extends ZIOSpecDefault:

  private val blueprint = ProvisionFixtures.blueprint
  private val tenantId = ProvisionFixtures.provision.tenantId

  private def webClient = blueprint.clients.find(_.clientId == CampaignBlueprint.webOtpClientId).get
  private def passkeyClient = blueprint.clients.find(_.clientId == CampaignBlueprint.mobilePasskeyClientId).get
  private def coreResource = blueprint.resources.find(_.resourceId == CampaignBlueprint.coreResourceId).get

  private def fakeAdmin(staleClientListing: Boolean = false): ZIO[TestClient & Client, Throwable, (AdminClient, FakeCentral)] =
    for
      fake <- FakeCentral.make(staleClientListing)
      _ <- TestClient.addRoutes(fake.handler.toRoutes)
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
    yield (admin, fake)

  /** Reads succeed with an empty listing, writes fail -- so the operation the failure names is
    * the write, not the read that preceded it.
    */
  private def failingWrites(status: Status): ZIO[TestClient & Client, Throwable, AdminClient] =
    for
      _ <- TestClient.addRoutes(
        Handler
          .fromFunction[Request]: request =>
            if request.method == Method.GET then Response.json("""{"clients":[],"resources":[],"permissions":[],"roles":[]}""")
            else Response.text("boom").status(status)
          .toRoutes,
      )
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
    yield admin

  def spec = suite("HttpAdminClient")(
    suite("registerClient")(
      test("creates a client it has not seen, with the tenant, flows and TTLs of the blueprint") {
        for
          (admin, fake) <- fakeAdmin()
          creds <- admin.registerClient(webClient)
          state <- fake.snapshot
          body = state.clients(webClient.clientId).spec
        yield assertTrue(
          str(body, "tenantId") == tenantId,
          str(body, "id") == webClient.clientId,
          str(body, "clientType") == "web",
          strings(body, "redirectUris").toSet == webClient.redirectUris,
          strings(body, "allowedScopes").toSet == webClient.allowedScopes,
          num(body, "accessTokenTtl").contains(BigDecimal(900)),
          num(body, "refreshTokenTtl").contains(BigDecimal(2592000)),
          field(body, "authFlow").contains(webClient.authFlow),
          field(body, "registrationFlow") == webClient.registrationFlow,
          optionalStr(body, "backChannelLogoutUri") == webClient.backChannelLogoutUri,
          creds == ClientCreds(webClient.clientId, Some("secret-web-otp-0")),
        )
      },
      test("marks a mobile client native and carries no secret back") {
        for
          (admin, fake) <- fakeAdmin()
          creds <- admin.registerClient(passkeyClient)
          state <- fake.snapshot
          body = state.clients(passkeyClient.clientId).spec
        yield assertTrue(
          str(body, "clientType") == "native",
          field(body, "registrationFlow").isEmpty,
          creds == ClientCreds(passkeyClient.clientId, None),
        )
      },
      // A confidential client's secret is readable exactly once, at registration, so the only way
      // to hand the campaign one for a client a previous run created is to rotate.
      test("updates and rotates when the client already exists") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(webClient)
          second <- admin.registerClient(webClient)
          state <- fake.snapshot
        yield assertTrue(
          second == ClientCreds(webClient.clientId, Some("secret-web-otp-1")),
          state.callsTo(Method.POST, "/configuration/clients").size == 1,
          state.callsTo(Method.PUT, "/configuration/clients").size == 1,
          state.callsTo(Method.POST, "/configuration/clients/rotate-secret").size == 1,
        )
      },
      test("does not rotate a public client, which has no secret to rotate") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(passkeyClient)
          second <- admin.registerClient(passkeyClient)
          state <- fake.snapshot
        yield assertTrue(
          second == ClientCreds(passkeyClient.clientId, None),
          state.callsTo(Method.POST, "/configuration/clients/rotate-secret").isEmpty,
          state.callsTo(Method.PUT, "/configuration/clients").size == 1,
        )
      },
      // Central's cached client listing can be stale, so a create can still lose the race -- the
      // 409 has to be a hand-off to the update path and not a failure.
      test("falls back to update when a concurrent create won the race") {
        for
          (admin, fake) <- fakeAdmin(staleClientListing = true)
          _ <- admin.registerClient(webClient)
          creds <- admin.registerClient(webClient)
          state <- fake.snapshot
        yield assertTrue(
          // Both runs see an empty listing and try to create; the second is told it lost.
          state.callsTo(Method.POST, "/configuration/clients").size == 2,
          state.callsTo(Method.PUT, "/configuration/clients").size == 1,
          creds == ClientCreds(webClient.clientId, Some("secret-web-otp-1")),
        )
      },
      // A patchable field is cleared by an explicit null and left alone by omission, so a client
      // that carried a registration flow the campaign no longer wants must be sent one.
      test("clears a dropped registration flow and logout URI rather than omitting them") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(passkeyClient)
          _ <- admin.registerClient(passkeyClient)
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/clients").head.body)
        yield assertTrue(
          field(update, "registrationFlow").contains(Json.Null),
          field(update, "backChannelLogoutUri").contains(Json.Null),
        )
      },
      test("sends the blueprint's flow as a patch when the client keeps one") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(webClient)
          _ <- admin.registerClient(webClient)
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/clients").head.body)
        yield assertTrue(
          field(update, "registrationFlow") == webClient.registrationFlow,
          field(update, "authFlow").contains(webClient.authFlow),
          strings(obj(update, "scope"), "add").toSet == webClient.allowedScopes,
        )
      },
    ),
    suite("registerResource")(
      test("creates a resource with its endpoints, and not as an internal one") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerResource(coreResource)
          state <- fake.snapshot
          body = state.resources(coreResource.resourceId).spec
          limits = objects(body, "endpoints").find(e => str(e, "path") == "/cards/{cardId}/limits").get
          expected = coreResource.endpoints.find(_.path == "/cards/{cardId}/limits").get
        yield assertTrue(
          str(body, "tenantId") == tenantId,
          str(body, "resource") == ProvisionFixtures.provision.resources.coreUri,
          strings(body, "audience") == CampaignBlueprint.clientIds,
          bool(body, "internal").contains(false),
          objects(body, "endpoints").size == coreResource.endpoints.size,
          str(limits, "method") == "PUT",
          str(limits, "id") == expected.id.toString,
          optionalStr(limits, "stepUpAcr") == expected.stepUpAcr,
          optionalStr(limits, "stepUpCondition") == expected.stepUpCondition,
          num(limits, "maxAge").contains(BigDecimal(300)),
          bool(limits, "fetchUserInfo").contains(false),
        )
      },
      // Central's update deletes every id it is about to create before creating it, so sending
      // the full desired set is one atomic desired-state apply.
      test("re-creates the desired endpoints and deletes only the ones the blueprint dropped") {
        val stale = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerResource(coreResource.copy(endpoints = coreResource.endpoints.map(_.copy(id = stale))))
          _ <- admin.registerResource(coreResource)
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/resources").head.body)
        yield assertTrue(
          strings(update, "deleteEndpoints").map(UUID.fromString) == List(stale),
          objects(update, "createEndpoints").size == coreResource.endpoints.size,
          state.resources(coreResource.resourceId).endpointIds == coreResource.endpoints.map(_.id).toSet,
        )
      },
    ),
    suite("permissions and roles")(
      test("creates a permission once and updates it thereafter") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertPermissions(blueprint.permissions)
          _ <- admin.upsertPermissions(blueprint.permissions)
          state <- fake.snapshot
          created = parse(state.callsTo(Method.POST, "/configuration/permissions").head.body)
          updated = parse(state.callsTo(Method.PUT, "/configuration/permissions").head.body)
        yield assertTrue(
          state.callsTo(Method.POST, "/configuration/permissions").size == blueprint.permissions.size,
          state.callsTo(Method.PUT, "/configuration/permissions").size == blueprint.permissions.size,
          str(created, "tenantId") == tenantId,
          field(created, "description").exists(_.asObject.exists(_.fields.map(_._1) == Chunk("en"))),
          str(updated, "permission") == blueprint.permissions.head.permission,
          strings(updated, "endpointIds").map(UUID.fromString).toSet == blueprint.permissions.head.endpointIds,
        )
      },
      test("creates a role with its permissions") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertRoles(blueprint.roles)
          state <- fake.snapshot
        yield assertTrue(
          state.roles(CampaignBlueprint.retailUserRoleId) == blueprint.roles.head.permissions,
          state.roles(CampaignBlueprint.retailBasicRoleId) == blueprint.roles.last.permissions,
        )
      },
      // A role's permissions are patched, not replaced, so a permission the blueprint moved from
      // one role to another would otherwise stay granted on both.
      test("revokes a permission the blueprint took away") {
        val granted = blueprint.roles.last
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertRoles(List(granted))
          _ <- admin.upsertRoles(List(granted.copy(permissions = granted.permissions - "cards:read")))
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/roles").head.body)
        yield assertTrue(
          strings(obj(update, "permissions"), "remove") == List("cards:read"),
          strings(obj(update, "permissions"), "add").isEmpty,
          !state.roles(granted.roleId).contains("cards:read"),
        )
      },
    ),
    suite("presets and challenge settings")(
      // Central stores a client's presets as one set, so a re-run cannot accumulate duplicates.
      test("replaces the client's presets wholesale") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertAuthRequestPresets(blueprint.presets.head)
          _ <- admin.upsertAuthRequestPresets(blueprint.presets.head)
          state <- fake.snapshot
          preset = state.presets(CampaignBlueprint.webOtpClientId).head
        yield assertTrue(
          state.presets(CampaignBlueprint.webOtpClientId).size == 1,
          str(preset, "id") == ProvisionFixtures.provision.preset.id,
          str(preset, "responseType") == "code",
          strings(preset, "scope").toSet == CampaignBlueprint.scopes,
          optionalStr(preset, "cookieDomain") == ProvisionFixtures.provision.preset.cookieDomain,
          optionalStr(preset, "postLogoutRedirectUri") == ProvisionFixtures.provision.preset.postLogoutRedirectUri,
        )
      },
      test("writes the whole challenge-settings document, vocabulary included") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertChallengeSettings(blueprint.challengeSettings)
          state <- fake.snapshot
          body = state.challengeSettings.get
          passkey = obj(body, "passkeySettings")
        yield assertTrue(
          str(body, "tenantId") == tenantId,
          num(body, "otpLength").contains(BigDecimal(6)),
          str(body, "ipHeader") == "X-Forwarded-For",
          str(passkey, "rpId") == ProvisionFixtures.provision.passkey.rpId,
          strings(passkey, "origins") == List(ProvisionFixtures.targets.origin),
          strings(obj(body, "acrVocabulary"), versola.loadgen.protocol.Acr.OtpLevel) == List("otp"),
          field(obj(body, "submissionLimits"), "banDurationSeconds").isDefined,
        )
      },
    ),
    suite("syncs")(
      test("tells auth and edge to reload, and flushes the user outbox") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.flushUserOutbox()
          _ <- admin.syncConfiguration()
          _ <- admin.syncEdgeConfiguration()
          state <- fake.snapshot
        yield assertTrue(state.outboxFlushes == 1, state.authSyncs == 1, state.edgeSyncs == 1)
      },
    ),
    suite("failures")(
      // A unique violation reaches the caller as a 500, which is indistinguishable from central
      // being broken -- so a failure has to stop the run and name the step to re-run.
      test("fails with the operation that broke") {
        for
          admin <- failingWrites(Status.InternalServerError)
          error <- admin.upsertRoles(blueprint.roles).flip
        yield assert(error)(
          isSubtype[AdminCallFailed](
            hasField[AdminCallFailed, String]("operation", _.operation, equalTo("upsertRoles")) &&
              hasField[AdminCallFailed, Status]("status", _.status, equalTo(Status.InternalServerError)),
          ),
        )
      },
      test("fails when a client listing cannot be read") {
        for
          _ <- TestClient.addRoutes(Handler.fromResponse(Response.json("""{"unexpected":true}""")).toRoutes)
          client <- ZIO.service[Client]
          admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
          error <- admin.registerClient(webClient).flip
        yield assert(error)(isSubtype[AdminCallFailed](hasField("operation", _.operation, Assertion.equalTo("listClients"))))
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
