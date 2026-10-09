package versola.loadgen.protocol

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jwt.SignedJWT
import versola.loadgen.config.ProvisionConfig
import versola.loadgen.provision.FakeCentral.*
import versola.loadgen.provision.{CampaignBlueprint, FakeCentral, ProvisionFixtures}
import versola.util.{ClientAssertion, Dpop}
import zio.*

import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

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

  /** Seeds the role the blueprint's registration flows grant: central rejects a client naming a
    * role that does not exist, and these specs write clients without provisioning first.
    */
  private def fakeAdmin(staleClientListing: Boolean = false): ZIO[TestClient & Client, Throwable, (AdminClient, FakeCentral)] =
    for
      fake <- FakeCentral.make(staleClientListing).flatMap(_.withRoles(Set(CampaignBlueprint.retailUserRoleId)))
      _ <- TestClient.addRoutes(fake.handler.toRoutes)
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
    yield (admin, fake)

  /** Every admin call is made with a token from auth, so a stub central that never answers the
    * token endpoint fails at authentication instead of at the step under test. */
  private val issuedToken = Response.json("""{"access_token":"stub-token","token_type":"Bearer"}""")

  private def isTokenRequest(request: Request): Boolean =
    request.method == Method.POST && request.url.path.encode.endsWith("/token")

  /** Reads succeed with an empty listing, writes fail -- so the operation the failure names is
    * the write, not the read that preceded it.
    */
  private def failingWrites(status: Status): ZIO[TestClient & Client, Throwable, AdminClient] =
    for
      _ <- TestClient.addRoutes(
        Handler
          .fromFunction[Request]: request =>
            if isTokenRequest(request) then issuedToken
            else if request.method == Method.GET then Response.json("""{"clients":[],"resources":[],"permissions":[],"roles":[]}""")
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
          str(body, "authMethod") == "client_secret",
          str(body, "applicationType") == "web",
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
      // Central declares every one of these mandatory, so omitting them is not "the campaign
      // wants the default" but a 400 that stops provisioning on its first client.
      test("names the client protections central requires, all switched off") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(webClient)
          _ <- admin.registerClient(webClient)
          state <- fake.snapshot
          create = state.clients(webClient.clientId).spec
          update = parse(state.callsTo(Method.PUT, "/configuration/clients").head.body)
          posted = parse(state.callsTo(Method.POST, "/configuration/clients").head.body)
        yield assertTrue(
          bool(create, "certificateBoundAccessTokens").contains(false),
          field(create, "dpopSigningAlgs").contains(Json.Arr()),
          bool(create, "dpopBoundAccessTokens").contains(false),
          bool(create, "requireSignedRequestObject").contains(false),
          bool(create, "requirePushedAuthorizationRequests").contains(false),
          // Create-only: the update DTO has no such member, so it is read from the POST itself.
          bool(posted, "issueEdgeClientCertificate").contains(false),
          bool(posted, "enrollEdgeClientCertificate").contains(false),
          str(create, "applicationType") == "web",
          // Written on the update too, so a client left bound by a previous configuration is
          // converged rather than left holding a setting the campaign cannot satisfy.
          bool(update, "certificateBoundAccessTokens").contains(false),
          field(update, "dpopSigningAlgs").contains(Json.Arr()),
          bool(update, "dpopBoundAccessTokens").contains(false),
          bool(update, "requireSignedRequestObject").contains(false),
          bool(update, "requirePushedAuthorizationRequests").contains(false),
          str(update, "applicationType") == "web",
        )
      },
      // #535: what a FAPI 2.0 tenant admits. The mobile client is native, fronted by edge and
      // DPoP-bound; both kinds authenticate by the certificate central issues for edge and push
      // every request; neither ever has a secret, so none is rotated on a re-run.
      test("registers a fapi2 campaign's clients as certificate clients, native ones DPoP-bound, and rotates no secret") {
        val fapi = CampaignBlueprint(ProvisionFixtures.targets, ProvisionFixtures.provision.copy(fapi2 = true), ProvisionFixtures.flows)
        val web = fapi.clients.find(_.clientId == CampaignBlueprint.webOtpClientId).get
        val mobile = fapi.clients.find(_.clientId == CampaignBlueprint.mobilePasskeyClientId).get
        for
          (admin, fake) <- fakeAdmin()
          webCreds <- admin.registerClient(web)
          mobileCreds <- admin.registerClient(mobile)
          _ <- admin.registerClient(mobile)
          state <- fake.snapshot
          webBody = parse(state.callsTo(Method.POST, "/configuration/clients").head.body)
          mobileBody = parse(state.callsTo(Method.POST, "/configuration/clients").last.body)
          update = parse(state.callsTo(Method.PUT, "/configuration/clients").head.body)
        yield assertTrue(
          str(webBody, "authMethod") == "tls_client_auth",
          str(mobileBody, "authMethod") == "tls_client_auth",
          bool(webBody, "issueEdgeClientCertificate").contains(true),
          bool(mobileBody, "issueEdgeClientCertificate").contains(true),
          bool(webBody, "requirePushedAuthorizationRequests").contains(true),
          bool(mobileBody, "requirePushedAuthorizationRequests").contains(true),
          str(webBody, "applicationType") == "web",
          str(mobileBody, "applicationType") == "native",
          bool(webBody, "dpopBoundAccessTokens").contains(false),
          bool(mobileBody, "dpopBoundAccessTokens").contains(true),
          webCreds == ClientCreds(web.clientId, None),
          mobileCreds == ClientCreds(mobile.clientId, None),
          bool(update, "dpopBoundAccessTokens").contains(true),
          bool(update, "requirePushedAuthorizationRequests").contains(true),
          state.callsTo(Method.POST, "/configuration/clients/rotate-secret").isEmpty,
        )
      },
      test("marks a mobile client public and carries no secret back") {
        for
          (admin, fake) <- fakeAdmin()
          creds <- admin.registerClient(passkeyClient)
          state <- fake.snapshot
          body = state.clients(passkeyClient.clientId).spec
        yield assertTrue(
          str(body, "authMethod") == "none",
          str(body, "applicationType") == "native",
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
      // The multi-value fields are patched, not replaced, so a redirect URI or scope the
      // blueprint narrowed away stays active on central unless the update names it -- an
      // obsolete OAuth redirect target surviving every re-run.
      test("takes away a redirect URI, scope and permission the blueprint no longer wants") {
        val widened = webClient.copy(
          redirectUris = webClient.redirectUris + "https://bank.example.test/stale",
          allowedScopes = webClient.allowedScopes + "stale:scope",
        )
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerClient(widened)
          _ <- fake.grantClientPermissions(webClient.clientId, Set("stale:permission"))
          _ <- admin.registerClient(webClient)
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/clients").head.body)
          stored = state.clients(webClient.clientId)
        yield assertTrue(
          strings(obj(update, "redirectUris"), "remove") == List("https://bank.example.test/stale"),
          strings(obj(update, "scope"), "remove") == List("stale:scope"),
          strings(obj(update, "permissions"), "remove") == List("stale:permission"),
          stored.redirectUris == webClient.redirectUris,
          stored.scopes == webClient.allowedScopes,
          stored.permissions.isEmpty,
        )
      },
      // Central validates the roles a registration flow grants while saving the client, so a
      // pass that wrote the clients before the roles would be rejected outright.
      test("is refused when the role its registration flow grants does not exist yet") {
        for
          fake <- FakeCentral.make()
          _ <- TestClient.addRoutes(fake.handler.toRoutes)
          client <- ZIO.service[Client]
          admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
          error <- admin.registerClient(webClient).flip
        yield assert(error)(
          isSubtype[AdminCallFailed](
            hasField[AdminCallFailed, String]("operation", _.operation, equalTo("registerClient")) &&
              hasField[AdminCallFailed, Status]("status", _.status, equalTo(Status.BadRequest)),
          ),
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
      // Central stores a resource's audience as an ordered list and patches it in place (see
      // `PatchAudience.patch`); sending the full desired list back as `add` would duplicate every
      // client already in it instead of leaving the list untouched, so the update names only what
      // changed.
      test("patches a resource's audience by add and remove rather than resending it whole") {
        val narrowed = coreResource.copy(audience = List(CampaignBlueprint.mobileOtpClientId, "stale-client"))
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerResource(narrowed)
          _ <- admin.registerResource(coreResource)
          state <- fake.snapshot
          update = parse(state.callsTo(Method.PUT, "/configuration/resources").head.body)
        yield assertTrue(
          strings(obj(update, "audience"), "remove") == List("stale-client"),
          strings(obj(update, "audience"), "add").toSet == coreResource.audience.toSet - CampaignBlueprint.mobileOtpClientId,
          state.resources(coreResource.resourceId).audience == coreResource.audience.toSet,
        )
      },
      // The listing the create-or-update choice is made on is cached, so a retry after a partial
      // run can be told the resource is absent and have the create rejected as a duplicate.
      test("converges on a resource a concurrent run committed behind a stale listing") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.registerResource(coreResource)
          _ <- fake.staleListings
          _ <- admin.registerResource(coreResource)
          state <- fake.snapshot
        yield assertTrue(
          state.callsTo(Method.POST, "/configuration/resources").size == 2,
          state.callsTo(Method.PUT, "/configuration/resources").size == 1,
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
      // Same race as the resources': a unique-violation 500 on a create whose listing was stale
      // must not fail the pass, because the id it names is already there.
      test("converges on a permission and a role a concurrent run committed behind a stale listing") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.upsertPermissions(blueprint.permissions)
          _ <- admin.upsertRoles(blueprint.roles)
          first <- fake.snapshot
          _ <- fake.staleListings
          _ <- admin.upsertPermissions(blueprint.permissions)
          _ <- admin.upsertRoles(blueprint.roles)
          second <- fake.snapshot
        yield assertTrue(
          // The cold listing sends the second pass down the create branch for everything, and
          // every one of those creates is refused as a duplicate.
          second.callsTo(Method.POST, "/configuration/permissions").size ==
            first.callsTo(Method.POST, "/configuration/permissions").size + blueprint.permissions.size,
          second.callsTo(Method.POST, "/configuration/roles").size ==
            first.callsTo(Method.POST, "/configuration/roles").size + blueprint.roles.size,
          second.permissions == first.permissions,
          second.roles == first.roles,
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
          str(body, "securityProfile") == "standard",
        )
      },
    ),
    suite("syncs")(
      test("tells auth to reload, and flushes the user outbox") {
        for
          (admin, fake) <- fakeAdmin()
          _ <- admin.flushUserOutbox()
          _ <- admin.syncConfiguration()
          state <- fake.snapshot
        yield assertTrue(state.outboxFlushes == 1, state.authSyncs == 1)
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
          _ <- TestClient.addRoutes(
            Handler
              .fromFunction[Request]: request =>
                if isTokenRequest(request) then issuedToken else Response.json("""{"unexpected":true}""")
              .toRoutes,
          )
          client <- ZIO.service[Client]
          admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
          error <- admin.registerClient(webClient).flip
        yield assert(error)(isSubtype[AdminCallFailed](hasField("operation", _.operation, Assertion.equalTo("listClients"))))
      },
    ),
    // #424: the provisioner on a FAPI 2.0 tenant -- RFC 7523 at auth, RFC 9449 at both hops.
    suite("provisioner credential")(
      test("authenticates with an assertion for the issuer and a DPoP proof, and presents the token under DPoP") {
        for
          seen <- capturing(_ => ZIO.none)
          admin <- keyedAdmin
          _ <- admin.syncConfiguration()
          captured <- seen.get
          tokenRequest = captured.head
          call = captured(1)
          form = Form.fromURLEncoded(tokenRequest.body, Charsets.Utf8).toOption.get
          assertion = SignedJWT.parse(form.get("client_assertion").flatMap(_.stringValue).get)
          tokenProof = SignedJWT.parse(tokenRequest.headers("dpop"))
          callProof = SignedJWT.parse(call.headers("dpop"))
        yield assertTrue(
          tokenRequest.path == "/token",
          !tokenRequest.headers.contains("authorization"),
          form.get("client_id").flatMap(_.stringValue).contains("utils"),
          form.get("client_assertion_type").flatMap(_.stringValue).contains(ClientAssertion.Type),
          assertion.verify(ECDSAVerifier(provisionerKey.toPublicJWK)),
          assertion.getJWTClaimsSet.getIssuer == "utils",
          assertion.getJWTClaimsSet.getSubject == "utils",
          assertion.getJWTClaimsSet.getAudience.asScala.toList == List("http://auth:8080"),
          tokenProof.getJWTClaimsSet.getStringClaim("htm") == "POST",
          tokenProof.getJWTClaimsSet.getStringClaim("htu") == "http://auth:8080/token",
          tokenProof.getJWTClaimsSet.getClaim("ath") == null,
          call.headers("authorization") == "DPoP dpop-bound-token",
          callProof.getJWTClaimsSet.getStringClaim("htu") == "http://edge:8095/resources/central/service/configuration/sync",
          callProof.getJWTClaimsSet.getStringClaim("ath") == Dpop.ath("dpop-bound-token"),
          callProof.getHeader.getJWK.computeThumbprint() == tokenProof.getHeader.getJWK.computeThumbprint(),
        )
      },
      test("answers auth's and edge's nonce challenges once each, and carries edge's nonce on the next call") {
        for
          seen <- capturing: request =>
            ZIO.succeed:
              if request.path == "/token" && !request.dpopNonce.contains("auth-nonce") then
                Some(Response.json("""{"error":"use_dpop_nonce"}""").status(Status.BadRequest)
                  .addHeader(Header.Custom("DPoP-Nonce", "auth-nonce")))
              else if request.path != "/token" && !request.dpopNonce.contains("edge-nonce") then
                Some(Response.status(Status.Unauthorized)
                  .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="use_dpop_nonce""""))
                  .addHeader(Header.Custom("DPoP-Nonce", "edge-nonce")))
              else None
          admin <- keyedAdmin
          _ <- admin.syncConfiguration()
          _ <- admin.syncConfiguration()
          captured <- seen.get
          assertions = captured.filter(_.path == "/token").map(r =>
            Form.fromURLEncoded(r.body, Charsets.Utf8).toOption.get.get("client_assertion").flatMap(_.stringValue).get,
          )
        yield assertTrue(
          captured.map(r => (r.path == "/token", r.dpopNonce)) == Chunk(
            (true, None),
            (true, Some("auth-nonce")),
            (false, None),
            (false, Some("edge-nonce")),
            (false, Some("edge-nonce")),
          ),
          assertions.distinct.size == 2,
        )
      },
      test("provisions against central with the key alone") {
        for
          fake <- FakeCentral.make().flatMap(_.withRoles(Set(CampaignBlueprint.retailUserRoleId)))
          _ <- TestClient.addRoutes(fake.handler.toRoutes)
          admin <- keyedAdmin
          creds <- admin.registerClient(webClient)
        yield assertTrue(creds.clientId == webClient.clientId)
      },
      test("refuses to start with neither a key nor a secret") {
        for
          client <- ZIO.service[Client]
          error <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision.copy(provisionerSecret = None)).flip
        yield assert(error)(isSubtype[InvalidProvisionerCredential](anything))
      },
      test("refuses a key that carries no private half") {
        for
          client <- ZIO.service[Client]
          error <- HttpAdminClient.make(client, ProvisionFixtures.targets, keyed(provisionerKey.toPublicJWK)).flip
        yield assert(error)(
          isSubtype[InvalidProvisionerCredential](hasField("reason", _.reason, containsString("provisioner-private-key"))),
        )
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.silentLogging

  private val provisionerKey: ECKey =
    ECKeyGenerator(Curve.P_256).keyID("utils-1").algorithm(JWSAlgorithm.ES256).generate()

  private def keyed(key: ECKey): ProvisionConfig =
    ProvisionFixtures.provision.copy(provisionerSecret = None, provisionerPrivateKey = Some(Config.Secret(key.toJSONString)))

  private def keyedAdmin: ZIO[Client, Throwable, AdminClient] =
    ZIO.serviceWithZIO[Client](HttpAdminClient.make(_, ProvisionFixtures.targets, keyed(provisionerKey)))

  private case class Captured(path: String, headers: Map[String, String], body: String):
    def dpopNonce: Option[String] =
      headers.get("dpop").flatMap(proof => Option(SignedJWT.parse(proof).getJWTClaimsSet.getStringClaim("nonce")))

  /** Records every request, answering each with `override` when it has one, and otherwise as
    * auth and edge would: a DPoP-bound token at `/token`, an empty `200` everywhere else. */
  private def capturing(`override`: Captured => UIO[Option[Response]]): ZIO[TestClient, Nothing, Ref[Chunk[Captured]]] =
    for
      seen <- Ref.make(Chunk.empty[Captured])
      _ <- TestClient.addRoutes(
        Handler
          .fromFunctionZIO[Request]: request =>
            for
              body <- request.body.asString.orDie
              captured = Captured(
                request.url.path.encode,
                request.headers.toList.map(h => h.headerName.toLowerCase -> h.renderedValue).toMap,
                body,
              )
              _ <- seen.update(_ :+ captured)
              answer <- `override`(captured)
            yield answer.getOrElse:
              if captured.path == "/token" then Response.json("""{"access_token":"dpop-bound-token","token_type":"DPoP"}""")
              else Response.ok
          .toRoutes,
      )
    yield seen
