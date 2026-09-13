package versola.loadgen.config

import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** Pure config-parsing test for [[LoadgenConfig]], mirroring EdgeConfigSpec's pattern: a
  * kebab-case [[zio.ConfigProvider]] over a raw HOCON string, loaded via
  * `deriveConfig[LoadgenConfig]` -- the same mechanism `VersolaApp.parseConfig` uses in
  * production.
  *
  * This is deliberately the first thing `loadgen` gets tested against: every parallel track
  * (protocol, store, scheduler, scenario engine, ...) reads its settings out of this tree, so a
  * decode failure here would otherwise only surface once all of them are wired together.
  */
object LoadgenConfigSpec extends ZIOSpecDefault:

  private val loadgenConfigDescriptor = deriveConfig[LoadgenConfig]

  private val hocon: String =
    """role = driver
      |shard { index = 0, count = 8 }
      |
      |targets {
      |  auth-url    = "http://auth:8080"
      |  edge-url    = "http://edge:8095"
      |  central-url = "http://central:8090"
      |  mock-url    = "http://mockapi:8100"
      |  origin      = "https://bank.example.test"
      |}
      |
      |coordinator { url = "http://loadgen-coordinator:8100", poll-interval = 10s }
      |
      |store {
      |  url = "jdbc:postgresql://loadgen-db:5432/loadgen"
      |  maximum-pool-size = 16
      |  write-behind { flush-interval = 200ms, batch-size = 500 }
      |}
      |
      |population {
      |  target = 10000000
      |  classes = [
      |    { name = heavy,   share = 0.15, active-probability = 0.90, sessions = 4 },
      |    { name = regular, share = 0.45, active-probability = 0.40, sessions = 2 },
      |    { name = light,   share = 0.30, active-probability = 0.10, sessions = 1 },
      |    { name = dormant, share = 0.10, active-probability = 0.01, sessions = 1 },
      |  ]
      |  platform { mobile = 0.88, web = 0.12 }
      |  credentials { otp = 0.35, otp-password = 0.40, passkey = 0.25 }
      |  roles { retail-user = 0.90, retail-basic = 0.10 }
      |}
      |
      |session {
      |  full-login-probability { mobile = 0.033, web = 0.85 }
      |  action-count { mobile-mean = 5, web-mean = 9, dispersion = 0.6 }
      |  think-time { median = 4s, sigma = 0.8 }
      |  payment-probability = 0.25
      |  extra-refresh-probability = 0.25
      |  logout-probability { mobile = 0.05, web = 0.35 }
      |  access-token-ttl = 15m
      |}
      |
      |campaign {
      |  name = "c3-10m-steady"
      |  phases = [
      |    { name = warmup,  duration = 15m, scale = 0.1 },
      |    { name = ramp,    duration = 30m, scale-from = 0.1, scale-to = 1.0 },
      |    { name = steady,  duration = 10h, scale = 1.0 },
      |  ]
      |  diurnal { enabled = true, peak-factor = 3.0, peak-hour = 20, timezone = "Asia/Almaty" }
      |  registration { enabled = false, target = 1000000, duration = 72h }
      |}
      |
      |actions = [
      |  { name = balance, weight = 0.4, method = GET, path = "/accounts/balance" },
      |  { name = pay,     weight = 0.1, method = POST, path = "/payments", acr = "password-level" },
      |]
      |""".stripMargin

  def spec = suite("LoadgenConfig")(
    suite("parsing")(
      test("decodes a full campaign config") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hocon)
            .kebabCase
            .load(loadgenConfigDescriptor)
        yield assertTrue(
          config.role == LoadgenRole.Driver,
          config.shard == Some(ShardConfig(index = 0, count = 8)),
          config.targets.mockUrl == "http://mockapi:8100",
          config.store.writeBehind.batchSize == 500,
          config.population.target == 10000000L,
          config.population.classes.map(_.name) == List("heavy", "regular", "light", "dormant"),
          config.session.actionCount.webMean == 9,
          config.campaign.phases.map(_.name) == List("warmup", "ramp", "steady"),
          config.campaign.phases(1).scaleFrom == Some(0.1),
          config.campaign.phases(1).scale == None,
          config.campaign.diurnal.timezone == "Asia/Almaty",
          config.actions.map(_.name) == List("balance", "pay"),
          config.actions(1).acr == Some("password-level"),
          config.actions(0).acr == None,
        )
      },
      test("decodes a coordinator config, which owns no shard") {
        for config <- TypesafeConfigProvider
            .fromHoconString(
              hocon
                .replaceFirst("role = driver", "role = coordinator")
                .replaceFirst("shard \\{ index = 0, count = 8 \\}", ""),
            )
            .kebabCase
            .load(loadgenConfigDescriptor)
        yield assertTrue(
          config.role == LoadgenRole.Coordinator,
          config.shard == None,
        )
      },
      test("rejects an unknown role") {
        for exit <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("role = driver", "role = orchestrator"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(exit.isFailure)
      },
    ),
    suite("campaign phases")(
      // Each of these decodes into a `CampaignPhaseConfig` the schedule cannot represent. Rejecting
      // them here rather than at first use is the point: a campaign whose volume is wrong is
      // indistinguishable from a slow SUT once it is running, so it has to be impossible to boot.
      test("rejects a phase that sets neither scale nor both ramp ends, and one that sets both") {
        for
          neither <- decodePhase("{ name = warmup, duration = 15m, scale-from = 0.1 }").exit
          both <- decodePhase("{ name = warmup, duration = 15m, scale = 0.5, scale-from = 0.1, scale-to = 1.0 }").exit
        yield assertTrue(neither.isFailure, both.isFailure)
      },
      test("rejects a negative duration but accepts a zero-length phase") {
        for
          negative <- decodePhase("{ name = warmup, duration = -10m, scale = 1.0 }").exit
          zero <- decodePhase("{ name = warmup, duration = 0s, scale = 1.0 }").exit
        yield assertTrue(negative.isFailure, zero.isSuccess)
      },
      test("rejects a negative or non-finite scale but accepts zero") {
        for
          negativeFlat <- decodePhase("{ name = warmup, duration = 15m, scale = -1.0 }").exit
          negativeRamp <- decodePhase("{ name = ramp, duration = 15m, scale-from = 0.1, scale-to = -1.0 }").exit
          nonFinite <- decodePhase("{ name = warmup, duration = 15m, scale = NaN }").exit
          idle <- decodePhase("{ name = idle, duration = 15m, scale = 0.0 }").exit
        yield assertTrue(negativeFlat.isFailure, negativeRamp.isFailure, nonFinite.isFailure, idle.isSuccess)
      },
    ),
  )

  /** Decodes `hocon` with its first campaign phase replaced, so the assertions above exercise the
    * real `deriveConfig[LoadgenConfig]` path rather than calling the validator directly.
    */
  private def decodePhase(phase: String) =
    TypesafeConfigProvider
      .fromHoconString(hocon.replaceFirst("\\{ name = warmup,  duration = 15m, scale = 0.1 \\}", phase))
      .kebabCase
      .load(loadgenConfigDescriptor)
