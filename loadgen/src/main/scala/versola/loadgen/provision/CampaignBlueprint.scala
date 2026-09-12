package versola.loadgen.provision

import versola.loadgen.config.{ProvisionConfig, TargetsConfig}
import versola.loadgen.protocol.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.util.UUID

/** The campaign's desired configuration in central: the four clients of design doc §2.2, the
  * three `mockapi`-backed resources and ten protected actions of §3, the permissions those
  * actions need, the `retail-user`/`retail-basic` roles, the one edge login preset and the ACR
  * vocabulary every step-up resolves through.
  *
  * A pure value, computed once from config and the shared flow documents, so that what
  * `provision` is about to write can be asserted on without a SUT.
  */
case class CampaignBlueprint(
    clients: List[ClientSpec],
    resources: List[ResourceSpec],
    permissions: List[PermissionSpec],
    roles: List[RoleSpec],
    presets: List[AuthRequestPresetsSpec],
    challengeSettings: ChallengeSettingsSpec,
)

object CampaignBlueprint:

  /** The four clients, named as the design doc names them -- also the ids a driver's config picks
    * a client by, so they are not free to change.
    */
  val mobileOtpClientId = "mobile-otp"
  val mobileOtpPasswordClientId = "mobile-otp-password"
  val mobilePasskeyClientId = "mobile-passkey"
  val webOtpClientId = "web-otp"

  val clientIds: List[String] =
    List(mobileOtpClientId, mobileOtpPasswordClientId, mobilePasskeyClientId, webOtpClientId)

  val coreResourceId = "core"
  val payResourceId = "pay"
  val notifyResourceId = "notify"

  /** The full-access role 90% of the population holds, and the read-only one the other 10% does.
    * The 403s `retail-basic` collects on the write actions are the campaign's only source of a
    * realistic forbidden rate (design doc §3), which is why it is provisioned rather than
    * emulated driver-side.
    */
  val retailUserRoleId = "retail-user"
  val retailBasicRoleId = "retail-basic"

  /** Scopes every client requests. `offline_access` is not optional: without it there is no
    * refresh token, and §2.3's 96.7% refresh path -- the bulk of the campaign's traffic -- cannot
    * happen at all.
    */
  val scopes: Set[String] = Set("openid", "profile", "phone", "offline_access")

  /** Design doc §5's access-token TTL; the session model (§5's `session.access-token-ttl`) reads
    * the same 15 minutes to decide when a long session needs an extra refresh.
    */
  private val accessTokenTtlSeconds = 900

  /** 30 days, per §2.2's "refresh token in the app, rotating, 30 d". */
  private val refreshTokenTtlSeconds = 2592000

  /** Step-up ACRs, in the two combinations the ten actions require.
    *
    * L2 is `otp-level`: an ordinary login requests no `acr_values` and so carries no `acr` at all,
    * which is what makes the payment actions demand a step-up rather than pass on the factor the
    * user happened to log in with (§2.3's "if token acr < L2").
    *
    * L3 names both the passkey and the password level, and edge treats `stepUpAcr` as a
    * space-separated set of alternatives -- design doc §3 #10 is "passkey or password re-auth",
    * and pinning it to the passkey level alone would make the action unreachable for the 75% of
    * the population that has never enrolled one.
    */
  private val level2Acr = Acr.OtpLevel
  private val level3Acr = s"${Acr.PasskeyLevel} ${Acr.PasswordLevel}"

  /** Design doc §3 #9: `max_age=300` forces a re-authentication on a stale `auth_time`. */
  private val cardLimitsMaxAgeSeconds = 300

  /** Reads as "required" so that the L2/L3 actions always demand their ACR. The condition exists
    * because `stepUpAcr` is only consulted when it passes; the variable part of the step-up rate
    * is the session model's, not the SUT's.
    */
  private val stepUpAlways = "true"

  /** One of the ten protected actions of design doc §3. `permission` is the one that unlocks it;
    * several actions share one, which is what makes the read/write split a role can express.
    */
  private case class Action(
      resourceId: String,
      method: String,
      path: String,
      permission: String,
      permissionDescription: String,
      fetchUserInfo: Boolean,
      allow: Option[String],
      stepUpAcr: Option[String],
      maxAgeSeconds: Option[Int],
  )

  /** The CEL access rule the two payment endpoints carry, so that `checkRules` is evaluated on
    * the measured path rather than skipped -- an edge benchmarked with no rule at all flatters it
    * (design doc §3).
    *
    * Guarded by `has` and widened with `double`, because a rule that cannot read what it needs is
    * a 403: the campaign's forbidden rate is supposed to come from `retail-basic` alone, and a
    * payment body without an `amount`, or with an integral one, must not add to it.
    */
  private def amountRule(threshold: Long): String =
    s"!has(request.body.amount) || double(request.body.amount) <= ${threshold.toDouble}"

  private def actions(threshold: Long): List[Action] = List(
    Action(
      resourceId = coreResourceId,
      method = "GET",
      path = "/accounts",
      permission = "accounts:read",
      permissionDescription = "Read account list and balances",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = coreResourceId,
      method = "GET",
      path = "/accounts/{accountId}/transactions",
      permission = "transactions:read",
      permissionDescription = "Read account transactions",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = coreResourceId,
      method = "GET",
      path = "/cards",
      permission = "cards:read",
      permissionDescription = "Read card list",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    // The only action that asks edge to call `/userinfo`, which is what puts auth's claims path
    // under load at all (design doc §3 #4).
    Action(
      resourceId = coreResourceId,
      method = "GET",
      path = "/profile",
      permission = "profile:read",
      permissionDescription = "Read own profile",
      fetchUserInfo = true,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = notifyResourceId,
      method = "GET",
      path = "/notifications",
      permission = "notifications:read",
      permissionDescription = "Read notifications",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = payResourceId,
      method = "GET",
      path = "/templates",
      permission = "payments:read",
      permissionDescription = "Read payment templates",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = None,
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = payResourceId,
      method = "POST",
      path = "/p2p",
      permission = "payments:write",
      permissionDescription = "Submit payments",
      fetchUserInfo = false,
      allow = Some(amountRule(threshold)),
      stepUpAcr = Some(level2Acr),
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = payResourceId,
      method = "POST",
      path = "/utility",
      permission = "payments:write",
      permissionDescription = "Submit payments",
      fetchUserInfo = false,
      allow = Some(amountRule(threshold)),
      stepUpAcr = Some(level2Acr),
      maxAgeSeconds = None,
    ),
    Action(
      resourceId = coreResourceId,
      method = "PUT",
      path = "/cards/{cardId}/limits",
      permission = "cards:write",
      permissionDescription = "Change card limits",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = Some(level2Acr),
      maxAgeSeconds = Some(cardLimitsMaxAgeSeconds),
    ),
    Action(
      resourceId = coreResourceId,
      method = "DELETE",
      path = "/profile/security/devices/{deviceId}",
      permission = "profile:write",
      permissionDescription = "Revoke own devices",
      fetchUserInfo = false,
      allow = None,
      stepUpAcr = Some(level3Acr),
      maxAgeSeconds = None,
    ),
  )

  /** Endpoint ids have to survive a re-`provision`: a permission grants an endpoint by id, so a
    * fresh UUID per run would leave every permission pointing at an endpoint that no longer
    * exists and every action 403. Derived from the endpoint's identity -- resource, method and
    * path -- rather than stored, so the seeder, a re-run and a second driver all compute the same
    * value with nothing to keep in sync.
    */
  private def endpointId(resourceId: String, method: String, path: String): UUID =
    UUID.nameUUIDFromBytes(s"versola-loadgen/$resourceId/$method$path".getBytes(StandardCharsets.UTF_8))

  def apply(targets: TargetsConfig, provision: ProvisionConfig, flows: CampaignFlows): CampaignBlueprint =
    val allActions = actions(provision.paymentAmountThreshold)

    // Where a completed edge login sends the browser, and where central posts this client's
    // back-channel logouts. The first is a redirect URI the browser must reach, so it is built on
    // the public origin -- central refuses plain HTTP on a non-loopback host anyway; the second is
    // server-to-server and uses the in-cluster address.
    val edgeCompleteUri = s"${targets.origin}/complete"
    val edgeBackChannelLogoutUri = s"${targets.edgeUrl}/logout/backchannel"

    val mobileClients = List(
      (mobileOtpClientId, "Loadgen mobile OTP", flows.phoneOtpAuthFlow, Some(flows.registrationFlow)),
      (
        mobileOtpPasswordClientId,
        "Loadgen mobile OTP + password",
        flows.phoneOtpPasswordAuthFlow,
        Some(flows.registrationFlow),
      ),
      // No registration flow: an account is registered from a credential card, and this client's
      // primary credential is the passkey the new account does not have yet. The passkey cohort
      // enrols from an already-registered session (§2.3's 0.5%/month enrolment).
      (mobilePasskeyClientId, "Loadgen mobile passkey", flows.phonePasskeyAuthFlow, None),
    ).map: (clientId, name, authFlow, registrationFlow) =>
      ClientSpec(
        clientId = clientId,
        clientName = name,
        redirectUris = Set(provision.mobileRedirectUri),
        allowedScopes = scopes,
        accessTokenTtlSeconds = accessTokenTtlSeconds,
        refreshTokenTtlSeconds = Some(refreshTokenTtlSeconds),
        publicClient = true,
        authFlow = authFlow,
        registrationFlow = registrationFlow,
        backChannelLogoutUri = None,
      )

    val webClient = ClientSpec(
      clientId = webOtpClientId,
      clientName = "Loadgen web OTP",
      redirectUris = Set(edgeCompleteUri),
      allowedScopes = scopes,
      accessTokenTtlSeconds = accessTokenTtlSeconds,
      refreshTokenTtlSeconds = Some(refreshTokenTtlSeconds),
      publicClient = false,
      authFlow = flows.phoneOtpAuthFlow,
      registrationFlow = Some(flows.registrationFlow),
      // Design doc §3 #10 exercises the back-channel logout, which only reaches edge if the
      // client edge fronts declares where to send it.
      backChannelLogoutUri = Some(edgeBackChannelLogoutUri),
    )

    val clients = mobileClients :+ webClient

    // Every client may hold a token for every resource: a session's actions are drawn from all
    // ten regardless of how it authenticated, so a resource that named only some of the clients
    // would fail audience validation for the rest.
    val audience = clientIds

    val resourceUris = Map(
      coreResourceId -> provision.resources.coreUri,
      payResourceId -> provision.resources.payUri,
      notifyResourceId -> provision.resources.notifyUri,
    )

    val resources = List(coreResourceId, payResourceId, notifyResourceId).map: resourceId =>
      ResourceSpec(
        resourceId = resourceId,
        resourceUri = resourceUris(resourceId),
        audience = audience,
        endpoints = allActions.filter(_.resourceId == resourceId).map: action =>
          ResourceEndpointSpec(
            id = endpointId(action.resourceId, action.method, action.path),
            method = action.method,
            path = action.path,
            fetchUserInfo = action.fetchUserInfo,
            allow = action.allow,
            stepUpCondition = action.stepUpAcr.map(_ => stepUpAlways),
            stepUpAcr = action.stepUpAcr,
            maxAgeSeconds = action.maxAgeSeconds,
          ),
      )

    val permissions = allActions
      .groupBy(action => (action.permission, action.permissionDescription))
      .toList
      .sortBy(_._1._1)
      .map: entry =>
        val ((permission, description), grouped) = entry
        PermissionSpec(
          permission = permission,
          description = description,
          endpointIds = grouped.map(a => endpointId(a.resourceId, a.method, a.path)).toSet,
        )

    val readPermissions = permissions.map(_.permission).filter(_.endsWith(":read")).toSet

    val roles = List(
      RoleSpec(
        roleId = retailUserRoleId,
        description = "Retail customer",
        // Every permission, including the two writes the design doc's summary line leaves out:
        // with `cards:write`/`profile:write` withheld from this role too, actions #9 and #10
        // would 403 for the entire population, which would both bury the `retail-basic` signal
        // and stop the max-age re-auth and back-channel-logout paths from ever running.
        permissions = permissions.map(_.permission).toSet,
      ),
      RoleSpec(
        roleId = retailBasicRoleId,
        description = "Retail customer, read-only",
        permissions = readPermissions,
      ),
    )

    val presets = List(
      AuthRequestPresetsSpec(
        clientId = webOtpClientId,
        presets = List(
          AuthRequestPresetSpec(
            presetId = provision.preset.id,
            description = "Loadgen web login",
            redirectUri = edgeCompleteUri,
            postLoginRedirectUri = targets.origin,
            postLogoutRedirectUri = provision.preset.postLogoutRedirectUri,
            scope = scopes,
            responseType = "code",
            cookieDomain = provision.preset.cookieDomain,
            cookiePath = provision.preset.cookiePath,
          ),
        ),
      ),
    )

    val challengeSettings = ChallengeSettingsSpec(
      allowedPrefixes = Nil,
      otpLength = 6,
      otpResendAfterSeconds = 60,
      passkeyRpId = provision.passkey.rpId,
      passkeyRpName = provision.passkey.rpName,
      passkeyOrigins = Set(targets.origin),
      passkeyUserVerification = provision.passkey.userVerification,
      ipHeader = "X-Forwarded-For",
      // What makes `acr_values` resolvable: without a vocabulary, auth can neither tell that a
      // session already satisfies the requested ACR nor work out which factor would achieve it,
      // so every step-up would come back as `unmet_authentication_requirements` (§7.4).
      acrVocabulary = Map(
        Acr.OtpLevel -> List("otp"),
        Acr.PasswordLevel -> List("password"),
        Acr.PasskeyLevel -> List("passkey"),
      ),
    )

    CampaignBlueprint(
      clients = clients,
      resources = resources,
      permissions = permissions,
      roles = roles,
      presets = presets,
      challengeSettings = challengeSettings,
    )

/** The `authFlow`/`registrationFlow` documents, read verbatim from the shared resource files of
  * dev spec §3.4 rather than re-encoded here -- central's schema changes are exactly what this
  * indirection exists to keep from being discovered twice, once by e2e and once by the emulator.
  */
case class CampaignFlows(
    phoneOtpAuthFlow: Json,
    phoneOtpPasswordAuthFlow: Json,
    phonePasskeyAuthFlow: Json,
    registrationFlow: Json,
)
