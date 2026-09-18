package versola.loadgen.config

import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*
import zio.{Config, Duration, durationInt}

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

  /** Not private: [[versola.loadgen.provision.ProvisionerSpec]] loads role dispatch's input from
    * the same tree, and a second copy of it would drift the moment a field is added.
    */
  val hocon: String =
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
      |  postgres {
      |    url = "jdbc:postgresql://loadgen-db:5432/loadgen"
      |    user = "loadgen"
      |    password = "loadgen"
      |    maximum-pool-size = 16
      |    minimum-idle = 16
      |    connection-timeout = 30s
      |    max-lifetime = 30m
      |    leak-detection-threshold = 0s
      |  }
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
      |clients {
      |  mobile-otp          = "mobile-otp"
      |  mobile-otp-password = "mobile-otp-password"
      |  mobile-passkey      = "mobile-passkey"
      |  mobile-redirect-uri = "versola://callback"
      |  web-preset          = "web-otp"
      |  scope               = "openid profile phone offline_access"
      |  otp-length          = 6
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
      |  refresh-token-ttl = 30d
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
      |
      |plan {
      |  shard-count = 8
      |  acceptance {
      |    latency = [
      |      { scenario = "mobile-otp", name = token-refresh, ceiling = 120ms },
      |      { scenario = "mobile-otp", name = proxy-accounts, ceiling = 200ms },
      |      { name = mobile-otp-login, ceiling = 2s },
      |    ]
      |    edge-proxy    = { scenario = "mobile-otp", name = proxy-accounts }
      |    mock-backend  = { name = mock-accounts }
      |  }
      |}
      |
      |provision {
      |  tenant-id = default
      |  central-secret = "central-secret"
      |  edge-secret = "edge-secret"
      |  mobile-redirect-uri = "versola://callback"
      |  resources {
      |    core-uri   = "http://mockapi-core:8100"
      |    pay-uri    = "http://mockapi-pay:8100"
      |    notify-uri = "http://mockapi-notify:8100"
      |  }
      |  preset {
      |    id = web-otp
      |    cookie-domain = "bank.example.test"
      |    cookie-path = "/"
      |    post-logout-redirect-uri = "https://bank.example.test/goodbye"
      |  }
      |  passkey { rp-id = "bank.example.test", rp-name = "Versola Bank", user-verification = preferred }
      |  payment-amount-threshold = 1000000
      |}
      |
      |seed {
      |  auth {
      |    url      = "jdbc:postgresql://auth-db:5432/auth"
      |    user     = "auth"
      |    password = "authpass"
      |  }
      |  central {
      |    url      = "jdbc:postgresql://central-db:5432/central"
      |    user     = "central"
      |    password = "centralpass"
      |  }
      |  tenant-id        = default
      |  passwords-secret = "AAECAwQFBgcICQoLDA0ODw"
      |  shard-count      = 8
      |  hash-parallelism = 16
      |  batch-size       = 10000
      |}
      |
      |sut-stats {
      |  databases = [
      |    { name = auth,    database { url = "jdbc:postgresql://auth-db:5432/auth",       user = "stats", password = "[redacted]]" } },
      |    { name = central, database { url = "jdbc:postgresql://central-db:5432/central", user = "stats", password = "[redacted]]" } },
      |  ]
      |}
      |""".stripMargin

  /** The provision and seed blocks dropped, as a coordinator's config file leaves them -- the
    * `plan` block above is deliberately on the other side of the cut, since that is the one role
    * that does need it.
    */
  val hoconWithoutProvision: String = hocon.substring(0, hocon.indexOf("provision {"))

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
          config.store.postgres.url == "jdbc:postgresql://loadgen-db:5432/loadgen",
          config.store.postgres.maximumPoolSize == 16,
          config.store.writeBehind.batchSize == 500,
          config.population.target == 10000000L,
          config.population.classes.map(_.name) == List("heavy", "regular", "light", "dormant"),
          config.clients.map(_.mobilePasskey) == Some("mobile-passkey"),
          // Without `offline_access` the campaign is all full logins and no refreshes, which
          // still passes every threshold while measuring something else entirely.
          config.clients.map(_.scope) == Some("openid profile phone offline_access"),
          config.clients.map(_.otpLength) == Some(6),
          config.session.actionCount.webMean == 9,
          config.session.refreshTokenTtl == zio.Duration.fromSeconds(2592000),
          config.campaign.phases.map(_.name) == List("warmup", "ramp", "steady"),
          config.campaign.phases(1).scaleFrom == Some(0.1),
          config.campaign.phases(1).scale == None,
          config.campaign.diurnal.timezone == "Asia/Almaty",
          config.actions.map(_.name) == List("balance", "pay"),
          config.plan.map(_.shardCount) == Some(8),
          config.plan.map(_.acceptance.latency.head) ==
            Some(LatencyThresholdConfig(Some("mobile-otp"), "token-refresh", Duration.fromMillis(120))),
          // No scenario names a flow rather than a step here too, and the ceiling is the
          // campaign's rather than a constant: §6 sets a different one per endpoint.
          config.plan.map(_.acceptance.latency.last) ==
            Some(LatencyThresholdConfig(None, "mobile-otp-login", Duration.fromSeconds(2))),
          // No scenario names a flow rather than a step (§11), so the absence has to survive the
          // decode as `None` instead of becoming an empty string.
          config.plan.map(_.acceptance.mockBackend) == Some(MeasurementRefConfig(None, "mock-accounts")),
          config.actions(1).acr == Some("password-level"),
          config.actions(0).acr == None,
          config.provision.map(_.tenantId) == Some("default"),
          config.provision.map(_.resources.coreUri) == Some("http://mockapi-core:8100"),
          config.provision.flatMap(_.preset.cookieDomain) == Some("bank.example.test"),
          config.provision.map(_.passkey.rpId) == Some("bank.example.test"),
          config.provision.map(_.paymentAmountThreshold) == Some(1000000L),
          config.seed.map(_.auth.url) == Some("jdbc:postgresql://auth-db:5432/auth"),
          config.seed.map(_.central.user) == Some("central"),
          config.seed.map(_.tenantId) == Some("default"),
          config.seed.map(_.shardCount) == Some(8),
          config.seed.map(_.hashParallelism) == Some(16),
          config.seed.map(_.batchSize) == Some(10000),
          // base64url, the same string auth reads as PASSWORDS_SECRET -- not the raw UTF-8 bytes
          // of it, which would silently hash the whole population against the wrong pepper.
          config.seed.map(_.passwordsSecret.toSeq) == Some((0 to 15).map(_.toByte)),
          // §3 of the report is one block per SUT database, so the block is a list of named
          // databases rather than the fixed pair the seeder writes into.
          config.sutStats.map(_.databases.map(_.name)) == Some(List("auth", "central")),
          config.sutStats.map(_.databases.head.database.url) == Some("jdbc:postgresql://auth-db:5432/auth"),
          config.sutStats.map(_.databases.head.database.user) == Some("stats"),
        )
      },
      // A driver holds no admin credentials, so requiring the block here would fail its decode
      // before `role` was ever read.
      test("decodes a config that omits the provision and seed blocks") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hoconWithoutProvision)
            .kebabCase
            .load(loadgenConfigDescriptor)
        yield assertTrue(config.provision == None, config.seed == None)
      },
      // Reading another service's database is a privilege somebody has to grant, and a campaign
      // whose report has no database section is still a campaign.
      test("decodes a coordinator config that holds no SUT database credentials") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hoconWithoutProvision)
            .kebabCase
            .load(loadgenConfigDescriptor)
        yield assertTrue(config.sutStats == None)
      },
      // An empty list boots a coordinator that reports no database section and says nothing about
      // why, which is indistinguishable from one configured without the block at all. Duplicate
      // names collide on `(campaign, database, phase)`, and the report then shows one database's
      // statistics under the other's name.
      test("rejects an empty, unnamed or duplicated sut-stats database list") {
        for
          empty <- decodeSutStats("sut-stats { databases = [] }").exit
          duplicated <- decodeSutStats(sutStatsWith("auth", "auth")).exit
          unnamed <- decodeSutStats(sutStatsWith("", "central")).exit
          named <- decodeSutStats(sutStatsWith("auth", "central")).exit
        yield assertTrue(empty.isFailure, duplicated.isFailure, unnamed.isFailure, named.isSuccess)
      },
      // The block's presence is the switch between a bearer run and a DPoP one, so its absence
      // has to be the default rather than a decode failure -- every campaign so far has no such
      // block, and the report's `tokenMode` is read straight off this.
      test("defaults the dpop block to absent and reads the pool's size and seed when it is there") {
        for
          absent <- decodeSutStats("")
          configured <- decodeSutStats("dpop { key-pool-size = 100, key-seed = \"campaign-1\" }")
        yield assertTrue(
          absent.dpop.isEmpty,
          configured.dpop.map(_.keyPoolSize) == Some(100),
          configured.dpop.map(_.keySeed) == Some("campaign-1"),
        )
      },
      // V0006's identity index is `(campaign, pooler, phase)`, so a duplicated name has
      // sut-stats' consequence: the second reading is dropped and one pooler's numbers appear
      // under the other's name.
      test("rejects an empty, unnamed or duplicated pooler-stats list, and defaults the block to absent") {
        for
          absent <- decodeSutStats("")
          empty <- decodeSutStats("pooler-stats { poolers = [] }").exit
          duplicated <- decodeSutStats(poolerStatsWith("auth-pooler", "auth-pooler")).exit
          unnamed <- decodeSutStats(poolerStatsWith("", "edge-pooler")).exit
          named <- decodeSutStats(poolerStatsWith("auth-pooler", "edge-pooler"))
        yield assertTrue(
          absent.poolerStats.isEmpty,
          empty.isFailure,
          duplicated.isFailure,
          unnamed.isFailure,
          named.poolerStats.map(_.poolers.map(_.name)) == Some(List("auth-pooler", "edge-pooler")),
          named.poolerStats.exists(_.poolers.head.admin.url.endsWith("/pgbouncer")),
        )
      },
      // `pg_stat_wal`, `pg_stat_checkpointer` and `pg_stat_io` answer for the whole cluster, so
      // two names pointed at the same host:port -- accepted, unlike a duplicated name, because
      // that is 03-postgres-topology.md's own developer-machine topology -- read those three
      // identically. `clusterGroups` is the boot-time warning's input, not a validation: it
      // reports the shared group rather than failing the decode.
      test("names two sut-stats databases on the same host:port as one cluster group, and two on different hosts as none") {
        val samePort = SutStatsConfig(
          List(
            SutStatsDatabaseConfig("auth", SutDatabaseConfig("jdbc:postgresql://combined:5432/auth", "stats", Config.Secret("x"))),
            SutStatsDatabaseConfig("central", SutDatabaseConfig("jdbc:postgresql://combined:5432/central", "stats", Config.Secret("x"))),
          ),
        )
        val distinct = SutStatsConfig(
          List(
            SutStatsDatabaseConfig("auth", SutDatabaseConfig("jdbc:postgresql://auth-db:5432/auth", "stats", Config.Secret("x"))),
            SutStatsDatabaseConfig("central", SutDatabaseConfig("jdbc:postgresql://central-db:5432/central", "stats", Config.Secret("x"))),
          ),
        )
        assertTrue(
          SutStatsConfig.clusterGroups(samePort.databases) == List(List("auth", "central")),
          SutStatsConfig.clusterGroups(distinct.databases) == Nil,
        )
      },
      // A pepper of the wrong length is a population whose every password fails to verify, and
      // the 16-byte check is the only place that can still be said out loud -- once it has been
      // hashed with, the evidence is gone.
      test("rejects a seed pepper that is not 16 base64url-encoded bytes") {
        for
          tooShort <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("AAECAwQFBgcICQoLDA0ODw", "AAECAw"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
          notBase64 <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("AAECAwQFBgcICQoLDA0ODw", "not base64 at all!"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(tooShort.isFailure, notBase64.isFailure)
      },
      // Both hang rather than fail when they reach the seeder: hash-parallelism = 0 builds a
      // zero-permit semaphore in SecurityService and every Argon2 hash waits on it forever,
      // and batch-size = 0 iterates the same id range forever.
      test("rejects a non-positive hash-parallelism or batch-size, which would hang the seeder") {
        for
          noHashers <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("hash-parallelism = 16", "hash-parallelism = 0"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
          negativeHashers <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("hash-parallelism = 16", "hash-parallelism = -1"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
          noBatch <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("batch-size       = 10000", "batch-size       = 0"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(noHashers.isFailure, negativeHashers.isFailure, noBatch.isFailure)
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
      // A count of zero answers 500 to every driver's poll for the whole campaign:
      // `ShardAssignment.shardOf` requires a positive count and `ArrivalProcess.shardRate`
      // divides by it.
      // Every one of these decodes to a `plan` block that boots and then grades the campaign on
      // nothing, or grades one measurement twice with two different ceilings. `Option[PlanConfig]`
      // turns a decode failure into `None` rather than an error, so the rejection has to happen
      // in validation or it does not happen at all.
      test("rejects an acceptance block that would judge no endpoint's latency") {
        val withoutThresholds = hocon.replaceFirst(
          "(?s)latency = \\[.*?\\]",
          "latency = []",
        )
        for
          empty <- TypesafeConfigProvider
            .fromHoconString(withoutThresholds)
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(withoutThresholds.contains("latency = []"), empty.isFailure)
      },
      test("rejects a non-positive latency ceiling") {
        for
          zero <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("ceiling = 120ms", "ceiling = 0s"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(zero.isFailure)
      },
      test("rejects the same measurement named twice, which would grade it against two ceilings") {
        for
          duplicated <- TypesafeConfigProvider
            .fromHoconString(
              hocon.replaceFirst(
                "\\{ scenario = \"mobile-otp\", name = proxy-accounts, ceiling = 200ms \\},",
                "{ scenario = \"mobile-otp\", name = token-refresh, ceiling = 200ms },",
              ),
            )
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(duplicated.isFailure)
      },
      test("rejects a non-positive plan shard count") {
        for
          zero <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("shard-count = 8", "shard-count = 0"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
          negative <- TypesafeConfigProvider
            .fromHoconString(hocon.replaceFirst("shard-count = 8", "shard-count = -4"))
            .kebabCase
            .load(loadgenConfigDescriptor)
            .exit
        yield assertTrue(zero.isFailure, negative.isFailure)
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
    suite("calibration")(
      test("decodes the gate's block alongside role = calibrate") {
        for config <- decodeCalibration(calibration)
        yield assertTrue(
          config.role == LoadgenRole.Calibrate,
          config.calibration.map(_.ratePerSecond) == Some(120.0),
          config.calibration.map(_.seed) == Some(424242L),
          config.calibration.map(_.read.p50) == Some(6.millis),
          config.calibration.map(_.read.p99) == Some(46.millis),
          // 13.5 ms: mockapi's write mixture has no whole-millisecond p50, so the block has to be
          // able to state one it does not round.
          config.calibration.map(_.write.p50) == Some(Duration.fromNanos(13_500_000L)),
          config.calibration.map(_.write.p99) == Some(50.millis),
        )
      },
      // No other role runs the gate, so requiring the block would fail a driver's decode before
      // `role` was ever read -- the same cut `provision` and `seed` are on.
      test("decodes a config that omits the calibration block") {
        for config <- TypesafeConfigProvider
            .fromHoconString(hoconWithoutProvision)
            .kebabCase
            .load(loadgenConfigDescriptor)
        yield assertTrue(config.calibration == None)
      },
      // `ArrivalProcess.next` requires a positive ceiling and dies on anything else, so without
      // this the run fails some way into its own startup instead of at boot.
      test("rejects a rate that is not a positive finite number") {
        for
          zero <- decodeCalibration(calibration.replaceFirst("120.0", "0.0")).exit
          negative <- decodeCalibration(calibration.replaceFirst("120.0", "-5.0")).exit
          nonFinite <- decodeCalibration(calibration.replaceFirst("120.0", "NaN")).exit
        yield assertTrue(zero.isFailure, negative.isFailure, nonFinite.isFailure)
      },
      // A target of zero makes the reported delta the measurement itself, which reads as a driver
      // fault rather than as the config error it is. A p99 below its own p50 is not a
      // distribution any sampler can have produced.
      test("rejects a non-positive target and a p99 below its own p50") {
        for
          zero <- decodeCalibration(calibration.replaceFirst("p-50 = 6ms", "p-50 = 0ms")).exit
          negative <- decodeCalibration(calibration.replaceFirst("p-99 = 46ms", "p-99 = -46ms")).exit
          inverted <- decodeCalibration(calibration.replaceFirst("p-99 = 46ms", "p-99 = 5ms")).exit
        yield assertTrue(zero.isFailure, negative.isFailure, inverted.isFailure)
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

  /** The gate's own block (versolauth/versola#281), stated with `mockapi`'s configured quantiles
    * -- the figures a real calibration run compares itself against.
    */
  private val calibration: String =
    """calibration {
      |  rate-per-second = 120.0
      |  seed            = 424242
      |  read  { p-50 = 6ms,         p-99 = 46ms }
      |  write { p-50 = 13500micros, p-99 = 50ms }
      |}
      |""".stripMargin

  /** Two SUT databases under the names given, so the rejections above differ from the accepted
    * case in exactly the field each of them is about.
    */
  private def poolerStatsWith(first: String, second: String): String =
    s"""pooler-stats {
       |  poolers = [
       |    { name = "$first",  admin { url = "jdbc:postgresql://auth-pgb:6432/pgbouncer", user = stats, password = "[redacted]" } },
       |    { name = "$second", admin { url = "jdbc:postgresql://edge-pgb:6432/pgbouncer", user = stats, password = "[redacted]" } },
       |  ]
       |}
       |""".stripMargin

  private def sutStatsWith(first: String, second: String): String =
    s"""sut-stats {
       |  databases = [
       |    { name = "$first",  database { url = "jdbc:postgresql://auth-db:5432/auth", user = stats, password = "[redacted]]" } },
       |    { name = "$second", database { url = "jdbc:postgresql://c-db:5432/central", user = stats, password = "[redacted]]" } },
       |  ]
       |}
       |""".stripMargin

  private def decodeSutStats(block: String) =
    TypesafeConfigProvider
      .fromHoconString(hoconWithoutProvision + block)
      .kebabCase
      .load(loadgenConfigDescriptor)

  private def decodeCalibration(block: String) =
    TypesafeConfigProvider
      .fromHoconString(hocon.replaceFirst("role = driver", "role = calibrate") + block)
      .kebabCase
      .load(loadgenConfigDescriptor)

  /** Decodes `hocon` with its first campaign phase replaced, so the assertions above exercise the
    * real `deriveConfig[LoadgenConfig]` path rather than calling the validator directly.
    */
  private def decodePhase(phase: String) =
    TypesafeConfigProvider
      .fromHoconString(hocon.replaceFirst("\\{ name = warmup,  duration = 15m, scale = 0.1 \\}", phase))
      .kebabCase
      .load(loadgenConfigDescriptor)
