package versola.loadgen.seed

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.loadgen.config.*
import versola.loadgen.model.*
import versola.loadgen.store.{LoadgenPostgresSpec, PostgresVirtualUserRepository}
import versola.util.{Argon2Config, MAC, Salt, SecureRandom, SecurityService, Secret}
import zio.*
import zio.prelude.EqualOps
import zio.test.*

import java.sql.Connection
import java.util.UUID

/** #277's smoke test: seed 1,000 users and then establish that the population is one the real
  * login flow can authenticate.
  *
  * **What is executed and what is not.** The full stack -- auth, central, edge, mockapi -- is not
  * available in this environment (no container runtime), so the literal round trip #277 asks for,
  * an `/authorize` conversation ending in a `userinfo.sub`, is not run here. What is run is the
  * step of it that the seeder can get wrong and nothing else would catch:
  *
  *   - the population is written into auth's and central's **real migrated schemas**, so every
  *     column, constraint and index is the production one;
  *   - a seeded password is verified with **`SecurityService.hashPassword` and the stored salt**,
  *     which is byte for byte the computation `PasswordService.check` performs on every login --
  *     the exact bug this track can introduce is a password the real Argon2 verify path rejects,
  *     and this is that path;
  *   - the row *shape* `PasswordService.verifyPassword` selects on is asserted (one permanent
  *     row, `expires_at` NULL), because a well-hashed password in a row shaped as a *temporary*
  *     one is rejected just as firmly;
  *   - the identity the `sub` claim carries is asserted to be the one `vu_users.sut_user_id`
  *     hands the drivers, since `sub` is `users.id`.
  *
  * The gap that remains, stated rather than papered over: nothing here proves auth's conversation
  * *reaches* the password check for a seeded user -- that it resolves the phone, finds the role,
  * and renders the password step. That needs the stack, and it is the one assertion of #277 this
  * spec replaces with a narrower one.
  */
object SeederSmokeSpec extends ZIOSpecDefault:

  override def aspects = super.aspects ++ Chunk(TestAspect.sequential, TestAspect.withLiveClock)

  private val population = PopulationConfig(
    target = 1_000L,
    classes = List(
      PopulationClassConfig("heavy", 0.15, 0.90, 4),
      PopulationClassConfig("regular", 0.45, 0.40, 2),
      PopulationClassConfig("light", 0.30, 0.10, 1),
      PopulationClassConfig("dormant", 0.10, 0.01, 1),
    ),
    platform = PlatformMixConfig(mobile = 0.88, web = 0.12),
    credentials = CredentialMixConfig(otp = 0.35, otpPassword = 0.40, passkey = 0.25),
    roles = RoleMixConfig(retailUser = 0.90, retailBasic = 0.10),
  )

  /** Not the all-zero pepper `TestEnvConfig` uses: a pepper that is all zeroes is also what an
    * uninitialised array is, so a seeder that dropped the pepper entirely would still verify.
    */
  private val pepper: Secret.Bytes16 = Secret.Bytes16(Array.tabulate(16)(index => (index * 7 + 3).toByte))

  private val shardCount = 8

  private val seedConfig = SeedConfig(
    auth = SutDatabaseConfig("unused-in-this-spec", "unused", Config.Secret("unused")),
    central = SutDatabaseConfig("unused-in-this-spec", "unused", Config.Secret("unused")),
    tenantId = "default",
    passwordsSecret = pepper,
    shardCount = shardCount,
    hashParallelism = 4,
    // Four batches over 1,000 users, so the batch loop, the per-batch transaction and the
    // resume boundary are all exercised rather than degenerating into one pass.
    batchSize = 250,
  )

  private case class Harness(
      services: SeedServices,
      store: TransactorZIO,
      auth: Connection,
      central: Connection,
      security: SecurityService,
  )

  private val harness: ZIO[Scope, Throwable, Harness] =
    for
      sut <- SutDatabases.sut
      store <- LoadgenPostgresSpec.transactor.build.map(_.get[TransactorZIO])
      security <- (SecureRandom.live >>> SecurityService.live(Argon2Config(maxConcurrent = 4))).build
        .map(_.get[SecurityService])
      random <- SecureRandom.live.build.map(_.get[SecureRandom])
      services = SeedServices(
        sut = SutWriter(CopySink.OfConnection(sut.auth), CopySink.OfConnection(sut.central)),
        store = CopySink.OfTransactor(store),
        hasher = BulkHasher(security, random, pepper, seedConfig.hashParallelism),
        storeQueries = StoreQueries(store),
      )
    yield Harness(services, store, sut.auth, sut.central, security)

  private def truncate(harness: Harness): Task[Unit] =
    for
      _ <- harness.store.connect(sql"TRUNCATE TABLE vu_users".update.run()).unit
      _ <- ZIO.foreachDiscard(List("user_passwords", "passkeys", "user_roles", "users"))(table =>
        SutDatabases.statement(harness.auth, s"TRUNCATE TABLE $table CASCADE"),
      )
      _ <- SutDatabases.statement(harness.central, "TRUNCATE TABLE user_index")
    yield ()

  private def count(connection: Connection, query: String): Task[Long] =
    ZIO.attemptBlocking:
      val statement = connection.prepareStatement(query)
      try
        val rows = statement.executeQuery()
        try
          rows.next()
          rows.getLong(1)
        finally rows.close()
      finally statement.close()

  /** The one place the seeded material is read back exactly as auth reads it. */
  private case class StoredPassword(hash: Array[Byte], salt: Salt, permanentRows: Int, temporaryRows: Int)

  private def storedPassword(connection: Connection, userId: UUID): Task[StoredPassword] =
    ZIO.attemptBlocking:
      val statement = connection.prepareStatement(
        "SELECT password, salt, expires_at FROM user_passwords WHERE user_id = ?",
      )
      try
        statement.setObject(1, userId)
        val rows = statement.executeQuery()
        try
          var hash = Array.emptyByteArray
          var salt = Array.emptyByteArray
          var permanent = 0
          var temporary = 0
          while rows.next() do
            if rows.getTimestamp("expires_at") == null then
              permanent += 1
              hash = rows.getBytes("password")
              salt = rows.getBytes("salt")
            else temporary += 1
          StoredPassword(hash, Salt(salt), permanent, temporary)
        finally rows.close()
      finally statement.close()

  private def cohort(credential: CredentialKind): Vector[VirtualUser] =
    (1L to population.target).toVector
      .map(PopulationPlan.userOf(population, shardCount, _))
      .filter(_.credential == credential)

  /** Fails the nth `COPY`, which is how a crash mid-batch is reproduced. Everything before it
    * has been committed; the batch in flight has not.
    */
  private final class Flaky(delegate: CopySink, failOnCall: Int, calls: Ref[Int]) extends CopySink:
    override def copyIn(statement: String, rows: Chunk[String]): Task[Long] =
      calls.updateAndGet(_ + 1).flatMap: call =>
        if call == failOnCall then ZIO.fail(RuntimeException("simulated crash mid-batch"))
        else delegate.copyIn(statement, rows)

    override def execute(statement: String): Task[Unit] = delegate.execute(statement)

    override def atomically[A](effect: Task[A]): Task[A] = delegate.atomically(effect)

  private def seedOnce(harness: Harness) =
    truncate(harness) *> Seeder.run(harness.services, population, seedConfig)

  def spec = suite("SeederSmokeSpec")(
    test("seeds 1,000 users into every table the campaign's login flows read") {
      ZIO.scoped:
        harness.flatMap: harness =>
          for
            _ <- seedOnce(harness)
            users <- count(harness.auth, "SELECT count(*) FROM users")
            passwords <- count(harness.auth, "SELECT count(*) FROM user_passwords")
            roles <- count(harness.auth, "SELECT count(*) FROM user_roles")
            passkeys <- count(harness.auth, "SELECT count(*) FROM passkeys")
            index <- count(harness.central, "SELECT count(*) FROM user_index")
            states <- PostgresVirtualUserRepository(harness.store).countByState
          yield assertTrue(
            users == population.target,
            roles == population.target,
            index == population.target,
            passwords == cohort(CredentialKind.OtpPassword).size.toLong,
            passkeys == cohort(CredentialKind.Passkey).size.toLong,
            states == Map(VirtualUserState.Registered -> population.target),
          )
    },
    // The assertion this whole track turns on. `SecurityService.hashPassword` with the stored
    // salt and auth's pepper *is* `PasswordService.check`; if this holds, the real verify path
    // accepts the seeded password, and if it does not, every password login in the campaign fails
    // with no indication that the seeder is why.
    test("a seeded password verifies through the real Argon2 path, with the stored salt") {
      ZIO.scoped:
        harness.flatMap: harness =>
          val user = cohort(CredentialKind.OtpPassword).head
          val userId = PopulationPlan.sutUserIdOf(user.id)
          for
            _ <- seedOnce(harness)
            stored <- storedPassword(harness.auth, userId)
            plaintext <- ZIO.fromOption(user.password).orElseFail(AssertionError("cohort user has no password"))
            recomputed <- harness.security.hashPassword(Secret.fromString(plaintext), stored.salt, pepper)
            wrong <- harness.security.hashPassword(Secret.fromString(plaintext + "x"), stored.salt, pepper)
          yield assertTrue(
            recomputed === MAC(stored.hash),
            // Not an incidental assertion: if the pepper were being ignored, or the salt
            // recomputed rather than stored, the comparison above could hold for the wrong
            // reason. This pins that the hash actually depends on its input.
            !(wrong === MAC(stored.hash)),
            // `verifyPassword` partitions on `isTemporary` and answers `Temporary` for anything
            // with an expiry. One permanent row and no temporary one is the only shape that
            // reaches the `Success` arm.
            stored.permanentRows == 1,
            stored.temporaryRows == 0,
          )
    },
    test("the identity the drivers hold is the identity auth would put in `sub`") {
      ZIO.scoped:
        harness.flatMap: harness =>
          val repository = PostgresVirtualUserRepository(harness.store)
          for
            _ <- seedOnce(harness)
            seeded <- repository.find(500L)
            sutUserId <- ZIO.fromOption(seeded.flatMap(_.sutUserId)).orElseFail(AssertionError("no SUT user id"))
            phone <- ZIO.attemptBlocking:
              val statement = harness.auth.prepareStatement("SELECT phone FROM users WHERE id = ?")
              try
                statement.setObject(1, sutUserId)
                val rows = statement.executeQuery()
                try Option.when(rows.next())(rows.getString(1))
                finally rows.close()
              finally statement.close()
          yield assertTrue(
            phone.contains(PopulationPlan.phoneOf(500L)),
            sutUserId == PopulationPlan.sutUserIdOf(500L),
          )
    },
    // The CSV `COPY` encoding against the read path it has to satisfy. `password` is a TEXT
    // column and `passkey_key` a BYTEA one, both read through magnum's derived codec -- so this
    // is where a mis-encoded `bytea` hex string or a quoting slip shows up, rather than at the
    // first driver that tries to sign an assertion.
    test("every vu_users column round-trips through the production repository's read path") {
      ZIO.scoped:
        harness.flatMap: harness =>
          val repository = PostgresVirtualUserRepository(harness.store)
          val passkeyUser = cohort(CredentialKind.Passkey).head
          val passwordUser = cohort(CredentialKind.OtpPassword).head
          for
            _ <- seedOnce(harness)
            withPasskey <- repository.find(passkeyUser.id)
            withPassword <- repository.find(passwordUser.id)
          yield assertTrue(
            withPasskey.exists(_.passkeyKey.exists(_.length > 0)),
            withPasskey.exists(_.passkeyCredId.exists(_.length == 43)),
            withPasskey.exists(_.credential == CredentialKind.Passkey),
            withPasskey.exists(_.password.isEmpty),
            withPassword.flatMap(_.password) == passwordUser.password,
            withPassword.exists(_.passkeyKey.isEmpty),
            withPasskey.map(_.shard) == Some(passkeyUser.shard),
            withPasskey.map(_.activityClass) == Some(passkeyUser.activityClass),
            withPasskey.map(_.platform) == Some(passkeyUser.platform),
            withPasskey.map(_.role) == Some(passkeyUser.role),
          )
    },
    test("the passkey cohort's stored COSE key is the key its stored private key signs with") {
      ZIO.scoped:
        harness.flatMap: harness =>
          val user = cohort(CredentialKind.Passkey).head
          val userId = PopulationPlan.sutUserIdOf(user.id)
          for
            _ <- seedOnce(harness)
            cose <- ZIO.attemptBlocking:
              val statement = harness.auth.prepareStatement("SELECT public_key FROM passkeys WHERE user_id = ?")
              try
                statement.setObject(1, userId)
                val rows = statement.executeQuery()
                try Option.when(rows.next())(rows.getBytes(1))
                finally rows.close()
              finally statement.close()
            stored <- PostgresVirtualUserRepository(harness.store).find(user.id)
            privateKey <- ZIO.fromOption(stored.flatMap(_.passkeyKey)).orElseFail(AssertionError("no passkey key"))
            verified <- ZIO.attemptBlocking(PasskeyAssertions.signsWith(privateKey, cose.get))
          yield assertTrue(cose.exists(_.length == 77), verified)
    },
    // §10 step 7. Without it the planner spends the first hour of every campaign on empty-table
    // statistics, and the campaign measures that rather than the SUT.
    //
    // The statistics counters are reset first and `last_analyze` is read rather than
    // `last_autoanalyze`. Both matter: `pg_stat_user_tables` is cumulative for the lifetime of
    // the database, so without the reset this assertion passes on a leftover value from an
    // earlier run -- it did, and removing the ANALYZE step failed nothing. And autovacuum can
    // set `last_autoanalyze` on its own schedule, which would make the same assertion pass for
    // a reason that has nothing to do with the seeder.
    test("ANALYZE has run on every seeded table, so the planner has statistics") {
      ZIO.scoped:
        harness.flatMap: harness =>
          def reset(connection: Connection, table: String) =
            SutDatabases.statement(
              connection,
              s"SELECT pg_stat_reset_single_table_counters('$table'::regclass::oid)",
            )

          def analyzed(connection: Connection, table: String) =
            count(
              connection,
              s"SELECT count(*) FROM pg_stat_user_tables WHERE relname = '$table' AND last_analyze IS NOT NULL",
            ).repeatUntil(_ == 1L).timeout(20.seconds).map(_.getOrElse(0L))

          val authTables = List("users", "user_passwords", "user_roles", "passkeys")
          for
            _ <- truncate(harness)
            _ <- ZIO.foreachDiscard(authTables)(reset(harness.auth, _))
            _ <- reset(harness.central, "user_index")
            _ <- harness.store.connect(
              sql"SELECT pg_stat_reset_single_table_counters('vu_users'::regclass::oid)".query[Option[String]].run(),
            )
            _ <- Seeder.run(harness.services, population, seedConfig)
            counts <- ZIO.foreach(authTables)(analyzed(harness.auth, _))
            index <- analyzed(harness.central, "user_index")
            // The emulator's own table too: the coordinator's population counts are a
            // `GROUP BY state` over it on a 60 s timer, which is a sequential scan of 10M rows
            // if the planner thinks the table is empty.
            vuUsers <- harness.store.connect:
              sql"""SELECT count(*) FROM pg_stat_user_tables WHERE relname = 'vu_users'
                      AND last_analyze IS NOT NULL""".query[Long].run().head
            .repeatUntil(_ == 1L).timeout(20.seconds).map(_.getOrElse(0L))
          yield assertTrue(counts == List(1L, 1L, 1L, 1L), index == 1L, vuUsers == 1L)
    },
    // Resume, which is what makes a run that dies at user 640,000 affordable. Deleting the last
    // batch's `vu_users` rows is exactly the state a crash between the SUT write and the store
    // write leaves behind: SUT rows with no `vu_users` counterpart.
    test("a resumed run rewrites only the interrupted range, and leaves no duplicate behind") {
      ZIO.scoped:
        harness.flatMap: harness =>
          for
            _ <- seedOnce(harness)
            _ <- harness.store.connect(sql"DELETE FROM vu_users WHERE id > 750".update.run()).unit
            resumed <- Seeder.run(harness.services, population, seedConfig).exit
            users <- count(harness.auth, "SELECT count(*) FROM users")
            index <- count(harness.central, "SELECT count(*) FROM user_index")
            passwords <- count(harness.auth, "SELECT count(*) FROM user_passwords")
            states <- PostgresVirtualUserRepository(harness.store).countByState
          yield assertTrue(
            resumed.isSuccess,
            users == population.target,
            index == population.target,
            passwords == cohort(CredentialKind.OtpPassword).size.toLong,
            states == Map(VirtualUserState.Registered -> population.target),
          )
    },
    // And the whole-range case, which is not hypothetical: `vu_users` is UNLOGGED, so a Postgres
    // crash *truncates it* while the SUT keeps every row the seeder wrote. Re-seeding then means
    // writing the whole population over a populated SUT, which must converge rather than die on
    // `users_phone_idx` -- otherwise recovering from a crash costs a manual truncate of somebody
    // else's database.
    test("a full re-run over an already-populated SUT converges instead of colliding on phone") {
      ZIO.scoped:
        harness.flatMap: harness =>
          for
            _ <- seedOnce(harness)
            // Only the emulator's table, exactly as crash recovery would leave it.
            _ <- harness.store.connect(sql"TRUNCATE TABLE vu_users".update.run()).unit
            again <- Seeder.run(harness.services, population, seedConfig).exit
            users <- count(harness.auth, "SELECT count(*) FROM users")
            roles <- count(harness.auth, "SELECT count(*) FROM user_roles")
            passwords <- count(harness.auth, "SELECT count(*) FROM user_passwords")
            index <- count(harness.central, "SELECT count(*) FROM user_index")
            states <- PostgresVirtualUserRepository(harness.store).countByState
          yield assertTrue(
            again.isSuccess,
            users == population.target,
            roles == population.target,
            passwords == cohort(CredentialKind.OtpPassword).size.toLong,
            index == population.target,
            states == Map(VirtualUserState.Registered -> population.target),
          )
    },
    // The write order *is* the resume protocol, and this is the only test that can tell. The
    // seeder writes the SUT first and `vu_users` last, so an id present in `vu_users` is an id
    // whose SUT rows are committed. Reverse the two and a crash between them leaves `vu_users`
    // claiming users auth has never heard of -- which resume then skips, and which the drivers
    // spend the campaign failing to log in as. Nothing else here notices: every count is
    // internally consistent and every row is individually valid.
    test("a crash between the SUT write and the store write loses no user on resume") {
      ZIO.scoped:
        harness.flatMap: harness =>
          for
            _ <- truncate(harness)
            calls <- Ref.make(0)
            // The third COPY of the second batch: `users` of batch one is call 1, and each batch
            // issues four against auth, so this lands inside batch two with batch one committed.
            crashing = harness.services.copy(
              sut = SutWriter(Flaky(CopySink.OfConnection(harness.auth), 7, calls), CopySink.OfConnection(harness.central)),
            )
            crashed <- Seeder.run(crashing, population, seedConfig).exit
            resumed <- Seeder.run(harness.services, population, seedConfig).exit
            users <- count(harness.auth, "SELECT count(*) FROM users")
            roles <- count(harness.auth, "SELECT count(*) FROM user_roles")
            index <- count(harness.central, "SELECT count(*) FROM user_index")
            states <- PostgresVirtualUserRepository(harness.store).countByState
          yield assertTrue(
            crashed.isFailure,
            resumed.isSuccess,
            users == population.target,
            roles == population.target,
            index == population.target,
            states == Map(VirtualUserState.Registered -> population.target),
          )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)
