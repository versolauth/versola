package versola.loadgen.provision

import versola.loadgen.config.{LoadgenConfig, LoadgenConfigSpec}
import versola.loadgen.protocol.{AdminCallFailed, AdminClient, HttpAdminClient}
import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.*
import zio.test.*

/** Asserts that one `provision` pass writes the whole campaign, and that a second pass over the
  * same environment converges instead of duplicating or failing.
  *
  * Re-runnability is the requirement worth a test: the run that needs it most is the one that
  * died halfway, leaving clients created and roles not, and the operator's only recovery is to
  * run the same command again.
  */
object ProvisionerSpec extends ZIOSpecDefault:

  private val blueprint = ProvisionFixtures.blueprint

  private def fakeAdmin: ZIO[TestClient & Client, Throwable, (AdminClient, FakeCentral)] =
    for
      fake <- FakeCentral.make()
      _ <- TestClient.addRoutes(fake.handler.toRoutes)
      client <- ZIO.service[Client]
      admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
    yield (admin, fake)

  private def loadConfig(hocon: String): Task[LoadgenConfig] =
    TypesafeConfigProvider.fromHoconString(hocon).kebabCase.load(deriveConfig[LoadgenConfig])

  def spec = suite("Provisioner")(
    test("writes the campaign's clients, resources, permissions, roles, presets and settings") {
      for
        (admin, fake) <- fakeAdmin
        creds <- Provisioner.run(admin, blueprint)
        state <- fake.snapshot
      yield assertTrue(
        creds.keySet == CampaignBlueprint.clientIds.toSet,
        state.clients.keySet == CampaignBlueprint.clientIds.toSet,
        state.resources.keySet == Set("core", "pay", "notify"),
        state.permissions.keySet == blueprint.permissions.map(_.permission).toSet,
        state.roles.keySet == Set(CampaignBlueprint.retailUserRoleId, CampaignBlueprint.retailBasicRoleId),
        state.presets.keySet == Set(CampaignBlueprint.webOtpClientId),
        state.challengeSettings.isDefined,
      )
    },
    // A permission grants an endpoint by id, so provisioning a permission before its resource
    // declared that endpoint would leave the grant pointing at nothing.
    test("declares every endpoint a permission grants before granting it") {
      for
        (admin, fake) <- fakeAdmin
        _ <- Provisioner.run(admin, blueprint)
        state <- fake.snapshot
        declared = state.resources.values.flatMap(_.endpointIds).toSet
        granted = state.permissions.values.flatten.toSet
        firstPermission = state.calls.indexWhere(_.path == "/configuration/permissions")
        lastResource = state.calls.lastIndexWhere(_.path == "/configuration/resources")
      yield assertTrue(granted == declared, granted.size == 10, lastResource < firstPermission)
    },
    // Edge reads client and preset state from central, so a sync that ran before the writes
    // leaves edge serving the previous campaign's configuration.
    test("syncs auth and then edge, after every write") {
      for
        (admin, fake) <- fakeAdmin
        _ <- Provisioner.run(admin, blueprint)
        state <- fake.snapshot
        lastWrite = state.calls.lastIndexWhere(!_.path.startsWith("/service"))
        authSync = state.calls.indexWhere(call => call.path == "/service/configuration/sync")
        edgeSync = state.calls.lastIndexWhere(call => call.path == "/service/configuration/sync")
      yield assertTrue(
        state.authSyncs == 1,
        state.edgeSyncs == 1,
        state.outboxFlushes == 1,
        lastWrite < authSync,
        authSync < edgeSync,
      )
    },
    test("a second pass creates nothing and changes nothing") {
      for
        (admin, fake) <- fakeAdmin
        _ <- Provisioner.run(admin, blueprint)
        first <- fake.snapshot
        _ <- Provisioner.run(admin, blueprint)
        second <- fake.snapshot
      yield assertTrue(
        FakeCentral.createPaths.forall(path =>
          second.callsTo(Method.POST, path).size == first.callsTo(Method.POST, path).size,
        ),
        second.clients.keySet == first.clients.keySet,
        second.resources.view.mapValues(_.endpointIds).toMap == first.resources.view.mapValues(_.endpointIds).toMap,
        second.permissions == first.permissions,
        second.roles == first.roles,
        second.presets.view.mapValues(_.size).toMap == first.presets.view.mapValues(_.size).toMap,
        second.challengeSettings == first.challengeSettings,
      )
    },
    // The recovery case: a run that died after the clients were created must be able to finish
    // the rest on the next attempt rather than tripping over its own leftovers.
    test("finishes a run that died halfway") {
      for
        (admin, fake) <- fakeAdmin
        _ <- ZIO.foreachDiscard(blueprint.clients)(admin.registerClient)
        _ <- ZIO.foreachDiscard(blueprint.resources.take(1))(admin.registerResource)
        _ <- Provisioner.run(admin, blueprint)
        state <- fake.snapshot
      yield assertTrue(
        state.resources.keySet == Set("core", "pay", "notify"),
        state.permissions.values.flatten.toSet == state.resources.values.flatMap(_.endpointIds).toSet,
        state.roles(CampaignBlueprint.retailUserRoleId) == blueprint.roles.head.permissions,
      )
    },
    test("hands back usable credentials for every client, secret-bearing only where there is one") {
      for
        (admin, _) <- fakeAdmin
        creds <- Provisioner.run(admin, blueprint)
      yield assertTrue(
        creds(CampaignBlueprint.webOtpClientId).clientSecret.isDefined,
        creds(CampaignBlueprint.mobileOtpClientId).clientSecret.isEmpty,
        creds(CampaignBlueprint.mobilePasskeyClientId).clientSecret.isEmpty,
      )
    },
    // A half-provisioned environment measures the wrong system, so a failed step stops the run
    // rather than leaving the rest unwritten and the campaign to discover it.
    test("stops at the first failed step") {
      for
        _ <- TestClient.addRoutes(Handler.fromResponse(Response.status(Status.InternalServerError)).toRoutes)
        client <- ZIO.service[Client]
        admin <- HttpAdminClient.make(client, ProvisionFixtures.targets, ProvisionFixtures.provision)
        error <- Provisioner.run(admin, blueprint).flip
      yield assert(error)(Assertion.isSubtype[AdminCallFailed](Assertion.anything))
    },
    // The block is optional so that a driver's config still decodes, which makes its absence the
    // provisioner's problem to report rather than the config loader's.
    test("refuses to run without a provision configuration block") {
      for
        config <- loadConfig(LoadgenConfigSpec.hoconWithoutProvision)
        error <- Provisioner.provision(config).flip
      yield assertTrue(error == MissingProvisionConfig)
    },
    test("provisions from a config file, reading the campaign's flows off the classpath") {
      for
        (_, fake) <- fakeAdmin
        config <- loadConfig(LoadgenConfigSpec.hocon)
        _ <- Provisioner.provision(config)
        state <- fake.snapshot
        flows <- FlowResources.load
      yield assertTrue(
        state.clients.keySet == CampaignBlueprint.clientIds.toSet,
        FakeCentral.field(state.clients(CampaignBlueprint.mobileOtpClientId).spec, "authFlow")
          .contains(flows.phoneOtpAuthFlow),
      )
    },
  ).provide(TestClient.layer) @@ TestAspect.silentLogging
