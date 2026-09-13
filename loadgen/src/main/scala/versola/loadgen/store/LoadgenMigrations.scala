package versola.loadgen.store

/** Where the emulator's own schema lives, for whoever wires the store's `TransactorZIO`.
  *
  * Two things about this schema differ from every other one in the repository:
  *
  *   1. The directory is `loadgen/migrations`, not `<service>/implementations/postgres/
  *      migrations`, so `PostgresHikariDataSource`'s auto-detection (which matches paths ending
  *      in `postgres/migrations`) does not find it. [[locations]] must be passed explicitly as
  *      that function's `migrationLocations` -- which is also what keeps this schema out of any
  *      service's Flyway run, and every service's schema out of this one's.
  *   2. It lives in its own database, so its versions do not share a schema-history table with
  *      auth's or central's and cannot collide with them.
  *
  * Dev spec §6 named these files `L0001__*.sql`. They are `V0001__*.sql` instead, because an
  * `L` prefix fails silently: Flyway's default `sqlMigrationPrefix` is `V`, and
  * `PostgresHikariDataSource` sets `validateMigrationNaming(false)`, so an `L`-prefixed file is
  * **skipped rather than rejected** -- the process boots, reports itself ready, and the first
  * query fails against a table that was never created. The prefix bought nothing that (1) and
  * (2) above do not already provide, so it was not worth a new `sqlMigrationPrefix` parameter
  * on a function every service depends on. `MigrationNamingSpec` guards the naming, since
  * `validateMigrationNaming(false)` means Flyway itself never will.
  */
object LoadgenMigrations:
  val locations: Seq[String] = Seq("filesystem:./loadgen/migrations")
