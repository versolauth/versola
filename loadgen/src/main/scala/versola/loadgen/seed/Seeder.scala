package versola.loadgen.seed

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.loadgen.config.{LoadgenConfig, PopulationConfig, SeedConfig, SutDatabaseConfig}
import versola.loadgen.model.VirtualUser
import versola.loadgen.seed.SutSchema.SchemaOwner
import versola.loadgen.store.LoadgenMigrations
import versola.util.postgres.PostgresHikariDataSource
import versola.util.{Argon2Config, SecureRandom, SecurityService}
import zio.*

import java.sql.{Connection, DriverManager}
import java.time.Instant

/** `loadgen seed`: bulk-populates the system under test with the campaign's user population, then
  * exits (versola-loadgen-dev-spec.md §10, deliverable D5).
  *
  * It exists because the alternative does not finish. Registering 20M users through auth's
  * conversation flow is ~60 days (design doc §8.6); writing them with `COPY` and a bounded pool of
  * Argon2 threads is a couple of hours, of which essentially all is the hashing.
  *
  * Three databases, and the difference between them is the whole design:
  *
  *   - **auth's and central's**, whose schemas the seeder does not own. It writes them with bare
  *     `COPY`, never migrates or validates them, and is guarded against their changing underneath
  *     it by [[MigrationFingerprint]] and [[SutSchemaGuard]] -- both checked *before* any hashing,
  *     so a schema change costs a failed pre-flight rather than two hours and an unusable
  *     population.
  *   - **the emulator's own**, whose schema it does own, reached through the same
  *     `TransactorZIO`/Flyway wiring every other repository uses. The seeder is the first process
  *     to touch it in a campaign, so it is also the one that applies its migrations.
  *
  * Resumable, not idempotent, and the distinction is deliberate. A run that dies at user 640,000
  * has paid for 640,000 Argon2 hashes and must not pay again, so [[resumeFrom]] continues at
  * `max(vu_users.id) + 1`. `vu_users` is written **last** in each batch, which is what makes that
  * safe: an id present there is an id whose SUT rows are all committed. The batch that was in
  * flight left rows in the SUT with no `vu_users` counterpart, so each batch deletes its own id
  * range before writing it -- see [[deleteRange]].
  */
object Seeder:

  /** Role dispatch's entry point. */
  def seed(config: LoadgenConfig): ZIO[Scope & ConfigProvider, Throwable, Unit] =
    for
      seedConfig <- ZIO.fromOption(config.seed).orElseFail(MissingSeedConfig)
      _ <- ZIO
        .fromEither(PopulationPlan.validate(config.population, seedConfig.shardCount, config.population.target))
        .mapError(InvalidPopulation(_))
      auth <- connect(seedConfig.auth, SchemaOwner.Auth)
      central <- connect(seedConfig.central, SchemaOwner.Central)
      _ <- preflight(auth, central)
      store <- storeTransactor
      security <- securityService(seedConfig)
      random <- secureRandom
      services = SeedServices(
        sut = SutWriter(CopySink.OfConnection(auth), CopySink.OfConnection(central)),
        store = CopySink.OfTransactor(store),
        hasher = BulkHasher(security, random, seedConfig.passwordsSecret, seedConfig.hashParallelism),
        storeQueries = StoreQueries(store),
      )
      _ <- run(services, config.population, seedConfig)
    yield ()

  /** The pre-flight the §3.4 guard exists for. Both layers, both fatal, and both before the
    * expensive part: the entire value of catching a schema change is catching it at minute zero.
    */
  def preflight(auth: Connection, central: Connection): Task[Unit] =
    for
      fingerprints <- MigrationFingerprint.mismatches
      _ <- ZIO.fail(SutSchemaDrifted(MigrationFingerprint.report(fingerprints))).when(fingerprints.nonEmpty)
      _ <- ZIO.foreachDiscard(List(SchemaOwner.Auth -> auth, SchemaOwner.Central -> central)): (owner, connection) =>
        SutSchemaGuard.check(connection, owner).flatMap: findings =>
          ZIO.fail(SutSchemaDrifted(SutSchemaGuard.report(owner, findings))).when(findings.nonEmpty)
      _ <- ZIO.logInfo("SUT schema pre-flight passed (dev spec §3.4)")
    yield ()

  /** The seeding loop itself, taking its collaborators rather than building them, so
    * `SeederSmokeSpec` can drive the real thing against real databases without a `ConfigProvider`
    * and without the seeder opening its own connections.
    */
  private[seed] def run(services: SeedServices, population: PopulationConfig, seedConfig: SeedConfig): Task[Unit] =
    val target = population.target
    for
      resume <- services.storeQueries.maxVirtualUserId.map(_.fold(1L)(_ + 1L))
      _ <- ZIO.logInfo(
        s"Seeding ${target - resume + 1} users (ids $resume..$target) across ${seedConfig.shardCount} shards, " +
          s"batch ${seedConfig.batchSize}, Argon2 parallelism ${seedConfig.hashParallelism}",
      )
      _ <- ZIO.foreachDiscard(batches(resume, target, seedConfig.batchSize)): (from, until) =>
        seedBatch(services, population, seedConfig, from, until)
      _ <- analyze(services)
      _ <- ZIO.logInfo("Seed complete")
    yield ()

  /** `[from, until)` id ranges. The list is `target / batchSize` long -- 2,000 entries at 20M
    * users and a batch of 10,000 -- so it is the one thing here allowed to be materialised;
    * nothing else in the seeder holds more than one batch at a time.
    */
  private[seed] def batches(from: Long, target: Long, batchSize: Int): List[(Long, Long)] =
    require(batchSize > 0, s"seed.batch-size must be positive, got $batchSize")
    Iterator
      .iterate(from)(_ + batchSize)
      .takeWhile(_ <= target)
      .map(start => (start, math.min(start + batchSize, target + 1)))
      .toList

  private def seedBatch(
      services: SeedServices,
      population: PopulationConfig,
      seedConfig: SeedConfig,
      from: Long,
      until: Long,
  ): Task[Unit] =
    for
      now <- Clock.instant
      planned = Chunk.fromIterable((from until until).map(PopulationPlan.userOf(population, seedConfig.shardCount, _)))
      hashed <- services.hasher.hashAll(
        planned.filter(SeedRows.needsPassword).map(user => user.id -> PopulationPlan.passwordOf(user.id)),
      )
      passkeys <- enrol(planned)
      seeded = assemble(planned, hashed, passkeys)
      _ <- services.sut.write(seeded, seedConfig.tenantId, now, from, until)
      // Last, and that ordering is the resume protocol: an id in `vu_users` is an id whose SUT
      // rows are committed.
      _ <- services.store.copyIn(SeedRows.vuUsersCopyStatement, seeded.map(SeedRows.vuUsers)).flatMap: written =>
        ZIO
          .fail(ShortCopy("vu_users", seeded.size.toLong, written))
          .when(written != seeded.size.toLong)
      _ <- ZIO.logInfo(s"Seeded ids $from..${until - 1}")
    yield ()

  /** P-256 key pairs for the passkey cohort (§10 step 3). Sequential on purpose: generating one
    * is ~0.1 ms against Argon2's ~30 ms, so it is not the cost centre, and `SecureRandom`
    * synchronises internally -- parallelising it would contend on the shared instance for no
    * measurable gain.
    */
  private def enrol(planned: Chunk[VirtualUser]): Task[Map[Long, PasskeyMaterial]] =
    ZIO.attemptBlocking:
      val random = java.security.SecureRandom()
      planned.filter(SeedRows.needsPasskey).map(user => user.id -> PasskeyMaterial.generate(random)).toMap

  private def assemble(
      planned: Chunk[VirtualUser],
      hashed: Chunk[HashedPassword],
      passkeys: Map[Long, PasskeyMaterial],
  ): Chunk[SeededUser] =
    val byId = hashed.map(password => password.id -> password).toMap
    planned.map: user =>
      val passkey = passkeys.get(user.id)
      SeededUser(
        user = user.copy(
          passkeyKey = passkey.map(_.privateKey),
          passkeyCredId = passkey.map(SeedRows.credentialIdOf),
        ),
        password = byId.get(user.id),
        passkey = passkey,
      )

  /** §10 step 7. Every table the seeder touched, including the emulator's own `vu_users`: the
    * coordinator's population counts are a `GROUP BY state` over it every 60 s, and a planner
    * that thinks the table is empty will sequential-scan 10M rows on a fixed timer.
    */
  private def analyze(services: SeedServices): Task[Unit] =
    for
      _ <- ZIO.logInfo("Running ANALYZE on every seeded table (dev spec §10 step 7)")
      _ <- services.sut.analyze
      _ <- services.store.execute("ANALYZE vu_users")
    yield ()

  /** One bare connection per SUT database. Registered with `Scope` so it closes on every exit
    * path, including the pre-flight's.
    */
  private def connect(config: SutDatabaseConfig, owner: SchemaOwner): ZIO[Scope, Throwable, Connection] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlocking(
          DriverManager.getConnection(
            config.url,
            config.user,
            String(config.password.value.toArray),
          ),
        ),
      )(connection => ZIO.attemptBlocking(connection.close()).orDie)
      .tap(_ => ZIO.logInfo(s"Connected to the SUT's ${owner.service} database"))

  /** The emulator's own store, migrated. The seeder runs before any driver, so it is the process
    * that applies this schema; `migrate = false` would make `PostgresHikariDataSource` *validate*
    * a database that does not exist yet.
    */
  private def storeTransactor: ZIO[Scope & ConfigProvider, Throwable, TransactorZIO] =
    PostgresHikariDataSource
      .transactor(
        serviceName = Some("loadgen-seed"),
        migrate = true,
        validateOnMigrate = true,
        migrationLocations = Some(LoadgenMigrations.locations),
        configPath = Seq("store", "postgres"),
      )
      .build
      .map(_.get[TransactorZIO])

  /** The repository's own Argon2, at the repository's own parameters (§10 step 2: "use the same
    * `SecurityService` parameters; import `util`, do not re-implement").
    *
    * `Argon2Config.maxConcurrent` is set to the *same* number [[BulkHasher]] uses as its fiber
    * budget, from one config field, so the semaphore inside the service and the parallelism
    * outside it cannot disagree -- a lower semaphore would make the configured parallelism a
    * lie, and a higher one would let the fiber budget decide the heap footprint.
    */
  private def securityService(config: SeedConfig): ZIO[Scope, Throwable, SecurityService] =
    (SecureRandom.live >>> SecurityService.live(Argon2Config(maxConcurrent = config.hashParallelism))).build
      .map(_.get[SecurityService])

  private def secureRandom: ZIO[Scope, Throwable, SecureRandom] =
    SecureRandom.live.build.map(_.get[SecureRandom])

/** The three sinks and the two services one batch needs, bundled so [[Seeder.run]] takes one
  * parameter instead of five and so a test can substitute any of them.
  */
case class SeedServices(sut: SutWriter, store: CopySink, hasher: BulkHasher, storeQueries: StoreQueries)

case object MissingSeedConfig
    extends RuntimeException("role = seed requires a 'seed' configuration block")

case class InvalidPopulation(reason: String) extends RuntimeException(reason)

case class SutSchemaDrifted(report: String) extends RuntimeException(report)

case class ShortCopy(table: String, sent: Long, accepted: Long)
    extends RuntimeException(s"COPY into $table accepted $accepted of $sent rows")
