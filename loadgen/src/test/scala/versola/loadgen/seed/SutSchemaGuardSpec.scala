package versola.loadgen.seed

import versola.loadgen.seed.SutSchema.SchemaOwner
import zio.*
import zio.test.*

/** The dev spec §3.4 guard, both layers, and -- more importantly -- proof that each one *fires*.
  *
  * A guard that passes when it should fail is worse than no guard, because it manufactures
  * confidence. So the green-path assertions here are the cheap half; the ones that matter are the
  * cases that apply a real schema change to a real migrated database and assert the guard
  * refuses it.
  */
object SutSchemaGuardSpec extends ZIOSpecDefault:

  override def aspects = super.aspects ++ Chunk(TestAspect.sequential, TestAspect.withLiveClock)

  def spec = suite("SutSchemaGuard")(
    suite("the recorded migration fingerprint (layer 1: the tripwire)")(
      test("matches auth's and central's migrations as checked in") {
        // Both directories are on disk here, so this is also the assertion that the check ran at
        // all: an empty `mismatches` beside a non-empty `absent` would mean it skipped them.
        MigrationFingerprint.check.map(check => assertTrue(check.mismatches.isEmpty, check.checked))
      },
      // Derived from SutSchema, so seeding a table in a service whose migrations are not
      // fingerprinted cannot be added without the guard failing first.
      test("covers exactly the services the seeder writes into") {
        assertTrue(
          SutSchema.fingerprintedMigrationDirectories == List(
            "auth/implementations/postgres/migrations",
            "central/implementations/postgres/migrations",
          ),
        )
      },
      test("fires on a new migration file, and on an edit to an existing one") {
        ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("fingerprint")).flatMap: directory =>
          def write(name: String, content: String) =
            ZIO.attemptBlocking(java.nio.file.Files.writeString(directory.resolve(name), content))

          for
            _ <- write("V0001__first.sql", "CREATE TABLE a (id INT);\n")
            baseline <- MigrationFingerprint.of(directory.toString)
            _ <- write("V0002__second.sql", "CREATE TABLE b (id INT);\n")
            afterAdd <- MigrationFingerprint.of(directory.toString)
            // The case a listing hash -- which is what §3.4 literally asks for -- would miss.
            // An `ALTER TABLE ... NOT NULL` appended to an existing migration changes no
            // filename at all.
            _ <- write("V0002__second.sql", "CREATE TABLE b (id INT NOT NULL);\n")
            afterEdit <- MigrationFingerprint.of(directory.toString)
            repeated <- MigrationFingerprint.of(directory.toString)
          yield assertTrue(
            baseline != afterAdd,
            afterAdd != afterEdit,
            afterEdit == repeated,
          )
      },
      // The one failure mode that would make layer 1 useless rather than noisy: a fingerprint of
      // nothing is a stable value that matches itself, so the check would pass in any environment
      // where the migrations are not on disk.
      test("fails rather than answering for a directory that is absent or holds no migrations") {
        for
          absent <- MigrationFingerprint.of("no/such/migrations").exit
          empty <- ZIO
            .attemptBlocking(java.nio.file.Files.createTempDirectory("empty"))
            .flatMap(directory => MigrationFingerprint.of(directory.toString).exit)
        yield assertTrue(absent.isFailure, empty.isFailure)
      },
    ),
    suite("the live schema check (layer 2: the part that bites)")(
      test("passes against auth's and central's migrations as they stand") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            for
              auth <- SutSchemaGuard.check(sut.auth, SchemaOwner.Auth)
              central <- SutSchemaGuard.check(sut.central, SchemaOwner.Central)
            yield assertTrue(auth.isEmpty, central.isEmpty)
      },
      // #277's "CI guard fires on an auth schema change". This is the change the guard exists
      // for: a NOT NULL column with no default that the seeder knows nothing about. Without the
      // guard the seeder still builds, still runs, and fails mid-campaign -- or worse, someone
      // adds a DEFAULT to get the COPY through and the population silently carries a value auth
      // never intended.
      test("fires when auth gains a required column the seeder does not write") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.auth,
              List(
                "ALTER TABLE users ADD COLUMN tenant_id TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE users ALTER COLUMN tenant_id DROP DEFAULT",
              ),
              List("ALTER TABLE users DROP COLUMN tenant_id"),
            ):
              SutSchemaGuard.check(sut.auth, SchemaOwner.Auth).map: findings =>
                assertTrue(
                  findings.contains(SutSchemaGuard.Finding.RequiredColumnNotWritten("users", "tenant_id")),
                  SutSchemaGuard.report(SchemaOwner.Auth, findings).contains("users.tenant_id"),
                )
      },
      // The complement: a *nullable* addition is not a finding, because the database can fill it
      // in for a seeded row. Asserted so the rule above cannot be "fires on any change", which
      // would make the guard noise and get it disabled.
      test("stays silent when auth gains a column a seeded row does not have to carry") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.auth,
              List("ALTER TABLE users ADD COLUMN nickname TEXT"),
              List("ALTER TABLE users DROP COLUMN nickname"),
            ):
              SutSchemaGuard.check(sut.auth, SchemaOwner.Auth).map(findings => assertTrue(findings.isEmpty))
      },
      test("fires when a column the seeder writes is renamed away") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.auth,
              List("ALTER TABLE users RENAME COLUMN phone TO msisdn"),
              List("ALTER TABLE users RENAME COLUMN msisdn TO phone"),
            ):
              SutSchemaGuard.check(sut.auth, SchemaOwner.Auth).map: findings =>
                assertTrue(findings.contains(SutSchemaGuard.Finding.MissingColumn("users", "phone")))
      },
      // The dangerous class: a type change the `COPY` may well accept, storing something the
      // login path cannot read back. `udt_name` is compared rather than `data_type` precisely so
      // this is visible -- `data_type` reports every array as `ARRAY`.
      test("fires when a column the seeder writes changes type") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.auth,
              List("ALTER TABLE user_roles ALTER COLUMN role_id TYPE VARCHAR(64)"),
              List("ALTER TABLE user_roles ALTER COLUMN role_id TYPE TEXT"),
            ):
              SutSchemaGuard.check(sut.auth, SchemaOwner.Auth).map: findings =>
                assertTrue(
                  findings.contains(
                    SutSchemaGuard.Finding.TypeChanged("user_roles", "role_id", "text", "varchar"),
                  ),
                )
      },
      test("fires when a table the seeder writes is gone entirely") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.central,
              List("ALTER TABLE user_index RENAME TO user_index_old"),
              List("ALTER TABLE user_index_old RENAME TO user_index"),
            ):
              SutSchemaGuard.check(sut.central, SchemaOwner.Central).map: findings =>
                assertTrue(findings == Chunk(SutSchemaGuard.Finding.MissingTable("user_index")))
      },
      // The pre-flight is where the guard earns its keep: it runs before the Argon2 bill, so a
      // schema change costs a failed startup rather than two hours and an unusable population.
      test("Seeder.preflight refuses to start on a finding, naming the table") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            SutDatabases.withSchemaChange(
              sut.auth,
              List(
                "ALTER TABLE user_passwords ADD COLUMN algorithm TEXT NOT NULL DEFAULT 'argon2id'",
                "ALTER TABLE user_passwords ALTER COLUMN algorithm DROP DEFAULT",
              ),
              List("ALTER TABLE user_passwords DROP COLUMN algorithm"),
            ):
              Seeder.preflight(sut.auth, sut.central).exit.map: exit =>
                assertTrue(
                  exit.isFailure,
                  exit.causeOption.exists(_.failures.exists:
                    case SutSchemaDrifted(report) => report.contains("user_passwords.algorithm")
                    case _ => false,
                  ),
                )
      },
      // The tripwire, through the pre-flight, against a real change to auth's migrations
      // directory -- which is what dev spec §3.4 actually asks the guard to notice. Writing a
      // file into the repository is a heavier test than it looks, and it is deliberate: the only
      // other ways to reach this branch are to make the fingerprint source injectable (contorting
      // production code so a test can lie to it) or to leave the branch untested, which is how a
      // guard ends up not firing. The file is created and removed under `acquireRelease`, so an
      // interrupt still cleans up; if a hard kill ever leaves it behind, the failure is this same
      // suite complaining loudly about a fingerprint mismatch.
      test("Seeder.preflight refuses to start when auth's migrations directory has changed") {
        ZIO.scoped:
          SutDatabases.sut.flatMap: sut =>
            val probe = java.nio.file.Path
              .of(SchemaOwner.Auth.migrationsDirectory)
              .resolve("V9999__loadgen_guard_probe.sql")

            ZIO.acquireReleaseWith(
              ZIO.attemptBlocking(java.nio.file.Files.writeString(probe, "-- not a real migration\n")),
            )(_ => ZIO.attemptBlocking(java.nio.file.Files.deleteIfExists(probe)).orDie): _ =>
              Seeder.preflight(sut.auth, sut.central).exit.map: exit =>
                assertTrue(
                  exit.isFailure,
                  exit.causeOption.exists(_.failures.exists:
                    case SutSchemaDrifted(report) =>
                      report.contains("auth/implementations/postgres/migrations") &&
                        report.contains("sut-migrations.sha256")
                    case _ => false,
                  ),
                )
      },
      test("Seeder.preflight passes against the schema as it stands") {
        ZIO.scoped:
          SutDatabases.sut.flatMap(sut => Seeder.preflight(sut.auth, sut.central).exit.map(exit => assertTrue(exit.isSuccess)))
      },
    ),
  )
