package versola.loadgen.provision

import versola.loadgen.config.LoadgenConfig
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

  /** The order is not cosmetic. Resources come before permissions because a permission grants an
    * endpoint id the resource has to have declared; permissions before roles because a role
    * grants a permission; roles before clients because a client's registration flow grants a
    * role by id and central rejects the write with a `400` if that role does not exist yet;
    * clients before presets because a preset is validated against its client's redirect URIs.
    * The syncs come last, and edge's after auth's, because edge reads client and preset state
    * from central.
    */
  def run(admin: AdminClient, blueprint: CampaignBlueprint): Task[Map[String, ClientCreds]] =
    for
      _ <- ZIO.logInfo("Provisioning campaign configuration")
      _ <- ZIO.foreachDiscard(blueprint.resources)(admin.registerResource)
      _ <- admin.upsertPermissions(blueprint.permissions)
      _ <- admin.upsertRoles(blueprint.roles)
      creds <- ZIO.foreach(blueprint.clients)(spec => admin.registerClient(spec).map(spec.clientId -> _))
      _ <- ZIO.foreachDiscard(blueprint.presets)(admin.upsertAuthRequestPresets)
      _ <- admin.upsertChallengeSettings(blueprint.challengeSettings)
      // The outbox carries user writes to auth. Nothing here creates a user, but the seeder (§10)
      // and a campaign's registration ramp both leave entries behind, and a campaign that starts
      // with users auth has not heard about yet measures failed logins.
      _ <- admin.flushUserOutbox()
      _ <- admin.syncConfiguration()
      _ <- admin.syncEdgeConfiguration()
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
  def provision(config: LoadgenConfig): RIO[Client, Unit] =
    for
      provisionConfig <- ZIO
        .fromOption(config.provision)
        .orElseFail(MissingProvisionConfig)
      flows <- FlowResources.load
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, config.targets, provisionConfig)
      _ <- run(admin, CampaignBlueprint(config.targets, provisionConfig, flows))
    yield ()

case object MissingProvisionConfig
    extends RuntimeException("role = provision requires a 'provision' configuration block")
