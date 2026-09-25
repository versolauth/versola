package versola.loadgen.provision

import versola.loadgen.protocol.Acr
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

/** Asserts what `provision` is about to write, without a central to write it to.
  *
  * The blueprint is where a campaign's meaning lives -- which action needs a step-up, which role
  * is allowed to write -- and a mistake here does not fail a run, it silently measures the wrong
  * system: an endpoint with no `stepUpAcr` produces a campaign with no step-ups and a latency
  * profile that looks better than the real one.
  */
object CampaignBlueprintSpec extends ZIOSpecDefault:

  import ProvisionFixtures.blueprint

  private def endpoints = blueprint.resources.flatMap(r => r.endpoints.map(r.resourceId -> _))

  private def endpointFor(method: String, path: String) =
    endpoints.map(_._2).find(e => e.method == method && e.path == path)

  def spec = suite("CampaignBlueprint")(
    suite("clients")(
      test("declares the four clients of design doc §2.2") {
        assertTrue(blueprint.clients.map(_.clientId) == CampaignBlueprint.clientIds)
      },
      test("makes the three mobile clients public and the edge-fronted one confidential") {
        val public = blueprint.clients.filter(_.publicClient).map(_.clientId)
        val confidential = blueprint.clients.filterNot(_.publicClient).map(_.clientId)
        assertTrue(
          public == List(
            CampaignBlueprint.mobileOtpClientId,
            CampaignBlueprint.mobileOtpPasswordClientId,
            CampaignBlueprint.mobilePasskeyClientId,
          ),
          confidential == List(CampaignBlueprint.webOtpClientId),
        )
      },
      test("gives each client the auth flow its cohort authenticates with") {
        val flows = blueprint.clients.map(c => c.clientId -> c.authFlow).toMap
        assertTrue(
          flows(CampaignBlueprint.mobileOtpClientId) == ProvisionFixtures.flows.phoneOtpAuthFlow,
          flows(CampaignBlueprint.mobileOtpPasswordClientId) == ProvisionFixtures.flows.phoneOtpPasswordAuthFlow,
          flows(CampaignBlueprint.mobilePasskeyClientId) == ProvisionFixtures.flows.phonePasskeyAuthFlow,
          flows(CampaignBlueprint.webOtpClientId) == ProvisionFixtures.flows.phoneOtpAuthFlow,
        )
      },
      test("withholds a registration flow from the passkey client only") {
        val without = blueprint.clients.filter(_.registrationFlow.isEmpty).map(_.clientId)
        assertTrue(without == List(CampaignBlueprint.mobilePasskeyClientId))
      },
      // Without offline_access there is no refresh token, and §2.3's 96.7% refresh path -- most
      // of the campaign's traffic -- cannot be exercised at all.
      test("requests offline_access for every client") {
        assert(blueprint.clients)(forall(hasField("scopes", _.allowedScopes, contains("offline_access"))))
      },
      test("redirects the mobile clients to the app scheme and the web client to edge") {
        val mobile = blueprint.clients.filter(_.publicClient)
        val web = blueprint.clients.find(_.clientId == CampaignBlueprint.webOtpClientId).get
        assertTrue(
          mobile.forall(_.redirectUris == Set(ProvisionFixtures.provision.mobileRedirectUri)),
          web.redirectUris == Set(s"${ProvisionFixtures.targets.origin}/complete"),
        )
      },
      // §3 #10 exercises the back-channel logout, which only reaches edge if the client edge
      // fronts declares where central should post it.
      test("declares a back-channel logout URI for the edge-fronted client only") {
        val declared = blueprint.clients.filter(_.backChannelLogoutUri.isDefined).map(_.clientId)
        assertTrue(
          declared == List(CampaignBlueprint.webOtpClientId),
          blueprint.clients.last.backChannelLogoutUri.contains(s"${ProvisionFixtures.targets.edgeUrl}/logout/backchannel"),
        )
      },
    ),
    suite("resources and endpoints")(
      test("declares the ten protected actions of design doc §3 across three resources") {
        assertTrue(
          blueprint.resources.map(_.resourceId) == List(
            CampaignBlueprint.coreResourceId,
            CampaignBlueprint.payResourceId,
            CampaignBlueprint.notifyResourceId,
          ),
          endpoints.size == 10,
        )
      },
      test("assigns each endpoint to the resource that serves it") {
        val byResource = endpoints.groupMap(_._1)(pair => s"${pair._2.method} ${pair._2.path}").view.mapValues(_.toSet).toMap
        assertTrue(
          byResource(CampaignBlueprint.coreResourceId) == Set(
            "GET /accounts",
            "GET /accounts/{accountId}/transactions",
            "GET /cards",
            "GET /profile",
            "PUT /cards/{cardId}/limits",
            "DELETE /profile/security/devices/{deviceId}",
          ),
          byResource(CampaignBlueprint.payResourceId) == Set("GET /templates", "POST /p2p", "POST /utility"),
          byResource(CampaignBlueprint.notifyResourceId) == Set("GET /notifications"),
        )
      },
      test("names every client in every resource's audience") {
        assert(blueprint.resources)(
          forall(hasField("audience", _.audience, equalTo(CampaignBlueprint.clientIds))),
        )
      },
      // An L2 action must not be satisfiable by the factor the user happened to log in with:
      // an ordinary login requests no acr_values and so carries no acr at all.
      test("requires the OTP level on the payment and card-limit actions") {
        val stepUp = endpoints.map(_._2).filter(_.stepUpAcr.contains(Acr.OtpLevel)).map(e => s"${e.method} ${e.path}")
        assertTrue(stepUp.toSet == Set("POST /p2p", "POST /utility", "PUT /cards/{cardId}/limits"))
      },
      // §3 #10 is "passkey or password re-auth": edge reads stepUpAcr as a space-separated set of
      // alternatives, and pinning it to the passkey level alone would make the action unreachable
      // for the 75% of the population that has never enrolled one.
      test("accepts either passkey or password re-auth on the device-revocation action") {
        val revoke = endpointFor("DELETE", "/profile/security/devices/{deviceId}").get
        assertTrue(
          revoke.stepUpAcr.toList.flatMap(_.split(" ")).toSet == Set(Acr.PasskeyLevel, Acr.PasswordLevel),
        )
      },
      test("pairs every step-up ACR with a condition, and declares neither elsewhere") {
        assert(endpoints.map(_._2))(
          forall(assertion("has a condition exactly when it has an ACR")(e => e.stepUpAcr.isDefined == e.stepUpCondition.isDefined)),
        )
      },
      test("forces a re-authentication on a stale auth_time for the card-limit action only") {
        val withMaxAge = endpoints.map(_._2).filter(_.maxAgeSeconds.isDefined)
        assertTrue(withMaxAge.map(e => e.path -> e.maxAgeSeconds) == List("/cards/{cardId}/limits" -> Some(300)))
      },
      test("only the /profile action asks edge to fetch userinfo") {
        val fetching = endpoints.map(_._2).filter(_.fetchUserInfo).map(_.path)
        assertTrue(fetching == List("/profile"))
      },
      // A CEL rule exists so that checkRules runs on the measured path; it must not add to the
      // campaign's forbidden rate on a body that carries no amount.
      test("guards the payment CEL rule against a missing or integral amount") {
        val rules = endpoints.map(_._2).flatMap(_.allow)
        assertTrue(
          rules.size == 2,
          rules.forall(_.startsWith("!has(request.body.amount) ||")),
          rules.forall(_.contains("double(request.body.amount) <= 1000000.0")),
        )
      },
      // A permission grants an endpoint by id, so a fresh UUID per run would leave every
      // permission pointing at an endpoint that no longer exists and every action 403.
      test("derives endpoint ids stably across blueprints") {
        val other = CampaignBlueprint(ProvisionFixtures.targets, ProvisionFixtures.provision, ProvisionFixtures.flows)
        assertTrue(
          other.resources.flatMap(_.endpoints.map(_.id)) == blueprint.resources.flatMap(_.endpoints.map(_.id)),
          endpoints.map(_._2.id).distinct.size == 10,
        )
      },
    ),
    suite("permissions and roles")(
      test("covers every endpoint with exactly one permission") {
        val granted = blueprint.permissions.flatMap(_.endpointIds)
        assertTrue(
          granted.distinct.size == 10,
          granted.toSet == endpoints.map(_._2.id).toSet,
        )
      },
      test("shares one permission between the two payment actions") {
        val write = blueprint.permissions.find(_.permission == "payments:write").get
        val paths = endpoints.map(_._2).filter(e => write.endpointIds.contains(e.id)).map(_.path).toSet
        assertTrue(paths == Set("/p2p", "/utility"))
      },
      test("grants the full-access role every permission") {
        val role = blueprint.roles.find(_.roleId == CampaignBlueprint.retailUserRoleId).get
        assertTrue(role.permissions == blueprint.permissions.map(_.permission).toSet)
      },
      // The 403s this role collects on the write actions are the campaign's only source of a
      // realistic forbidden rate (§3), so it must hold the reads and none of the writes.
      test("grants the read-only role the reads and none of the writes") {
        val role = blueprint.roles.find(_.roleId == CampaignBlueprint.retailBasicRoleId).get
        assertTrue(
          role.permissions.forall(_.endsWith(":read")),
          role.permissions.nonEmpty,
          blueprint.permissions.map(_.permission).toSet -- role.permissions == Set(
            "payments:write",
            "cards:write",
            "profile:write",
          ),
        )
      },
    ),
    suite("presets and challenge settings")(
      test("declares the one edge login preset against the edge-fronted client") {
        val preset = blueprint.presets.head
        assertTrue(
          blueprint.presets.map(_.clientId) == List(CampaignBlueprint.webOtpClientId),
          preset.presets.map(_.presetId) == List(ProvisionFixtures.provision.preset.id),
          preset.presets.head.redirectUri == s"${ProvisionFixtures.targets.origin}/complete",
          preset.presets.head.scope == CampaignBlueprint.scopes,
        )
      },
      // Without a vocabulary auth cannot tell that a session already satisfies the requested ACR,
      // nor which factor would achieve it, so every step-up comes back as
      // unmet_authentication_requirements (§7.4).
      test("maps every ACR the endpoints request to the factor that satisfies it") {
        val vocabulary = blueprint.challengeSettings.acrVocabulary
        val requested = endpoints.flatMap(_._2.stepUpAcr).flatMap(_.split(" ")).toSet
        assertTrue(
          requested.subsetOf(vocabulary.keySet),
          vocabulary(Acr.OtpLevel) == List("otp"),
          vocabulary(Acr.PasswordLevel) == List("password"),
          vocabulary(Acr.PasskeyLevel) == List("passkey"),
        )
      },
      test("signs passkeys against the configured relying party and the campaign's origin") {
        val settings = blueprint.challengeSettings
        assertTrue(
          settings.passkeyRpId == ProvisionFixtures.provision.passkey.rpId,
          settings.passkeyOrigins == Set(ProvisionFixtures.targets.origin),
        )
      },
    ),
  )
