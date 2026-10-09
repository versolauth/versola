package versola.loadgen.provision

import versola.loadgen.config.{LoadgenConfig, ProvisionConfig, SecurityProfile}
import versola.loadgen.protocol.{AdminClient, ClientCreds, HttpAdminClient}
import zio.*
import zio.http.Client

/** `loadgen provision`: writes the campaign's configuration into central and makes auth and edge
  * pick it up, then exits. Deliverable D6 of the tracking issue; run once before a campaign, and
  * again whenever the blueprint changes.
  *
  * Re-runnable by construction. Every write is a desired-state apply ([[AdminClient]]), the
  * endpoint ids are derived rather than drawn, and the order below is the dependency order, so a
  * run against an already-provisioned environment converges instead of duplicating or failing --
  * which matters most for the case it was written for: a run that died halfway.
  */
object Provisioner:

  /** The order is not cosmetic. The tenant comes first, and its settings next, because every
    * other write is made into it and a client is validated against the profile and the
    * certificate header those settings hold. Resources come before permissions because a permission grants an
    * endpoint id the resource has to have declared; permissions before roles because a role
    * grants a permission; roles before clients because a client's registration flow grants a
    * role by id and central rejects the write with a `400` if that role does not exist yet;
    * clients before presets because a preset is validated against its client's redirect URIs.
    * The sync comes last because auth reads client and preset state from central, and the wait
    * on edge after it because edge reads the same state on its own schedule.
    */
  def run(admin: AdminClient, blueprint: CampaignBlueprint): Task[Map[String, ClientCreds]] =
    for
      _ <- ZIO.logInfo(s"Provisioning campaign configuration in tenant '${blueprint.tenant.tenantId}' (${blueprint.tenant.securityProfile})")
      _ <- admin.ensureTenant(blueprint.tenant)
      // Before the clients, not after them: a `tls_client_auth` client is refused by a tenant that
      // has no certificate header yet, and the profile in these settings is already the tenant's.
      _ <- admin.upsertChallengeSettings(blueprint.challengeSettings)
      _ <- ZIO.foreachDiscard(blueprint.resources)(admin.registerResource)
      _ <- admin.upsertPermissions(blueprint.permissions)
      _ <- admin.upsertRoles(blueprint.roles)
      creds <- ZIO.foreach(blueprint.clients)(spec => admin.registerClient(spec).map(spec.clientId -> _))
      _ <- ZIO.foreachDiscard(blueprint.presets)(admin.upsertAuthRequestPresets)
      // The outbox carries user writes to auth. Nothing here creates a user, but the seeder (§10)
      // and a campaign's registration ramp both leave entries behind, and a campaign that starts
      // with users auth has not heard about yet measures failed logins.
      _ <- admin.flushUserOutbox()
      _ <- admin.syncConfiguration()
      // Any of the campaign's resources proves the cache turned over, since one sync loads them
      // all; the first with a registered endpoint is used so the wait is over a resource this
      // run actually wrote, probed at that endpoint rather than the resource's bare root -- edge
      // never recognizes a loaded resource there, since no campaign resource registers `/`.
      _ <- ZIO.foreachDiscard(blueprint.resources.flatMap(r => r.endpoints.headOption.map(r.resourceId -> _)).headOption):
        case (resourceId, endpoint) => admin.awaitEdgeConfiguration(resourceId, endpoint.method, endpoint.path)
      _ <- ZIO.logInfo(s"Provisioned ${blueprint.clients.size} clients, ${blueprint.resources.size} resources, " +
        s"${blueprint.permissions.size} permissions, ${blueprint.roles.size} roles")
    yield creds.toMap

  /** Role dispatch's entry point: resolves what `role = provision` needs out of the config tree
    * and runs one pass.
    *
    * The `provision` block is optional in [[LoadgenConfig]], so its absence is caught here rather
    * than at decode time -- a driver's config legitimately omits it, and failing the decode for
    * everyone would stop `role` from being read at all.
    */
  /** `campaign.security-profile` is where a campaign states its profile, so the tenant provision
    * creates and the report the coordinator writes cannot disagree. `provision.fapi2` is what the
    * blueprint reads, and is only consulted for a campaign configured without the profile; a
    * config that sets both and contradicts itself is refused rather than resolved one way.
    */
  private def profileOf(config: LoadgenConfig, provision: ProvisionConfig): IO[ProvisionProfileConflict, ProvisionConfig] =
    config.campaign.securityProfile match
      case None => ZIO.succeed(provision)
      case Some(profile) =>
        val fapi2 = profile == SecurityProfile.Fapi2
        ZIO
          .fail(ProvisionProfileConflict(provision.fapi2, profile.wire))
          .when(provision.fapi2 && !fapi2)
          .as(provision.copy(fapi2 = fapi2))

  def provision(config: LoadgenConfig): RIO[Client, Unit] =
    for
      configured <- ZIO
        .fromOption(config.provision)
        .orElseFail(MissingProvisionConfig)
      provisionConfig <- profileOf(config, configured)
      flows <- FlowResources.load
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, config.targets, provisionConfig)
      _ <- run(admin, CampaignBlueprint(config.targets, provisionConfig, flows))
    yield ()

case class ProvisionProfileConflict(provisionSays: Boolean, campaignSays: String)
    extends RuntimeException(
      s"provision.fapi2 = $provisionSays contradicts campaign.security-profile = $campaignSays; " +
        "state the profile once, as campaign.security-profile",
    )

case object MissingProvisionConfig
    extends RuntimeException("role = provision requires a 'provision' configuration block")
