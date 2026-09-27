package versola.loadgen.seed

/** Every table and column the seeder writes into the system under test, declared as data
  * (versola-loadgen-dev-spec.md §10 steps 4-5).
  *
  * This is the seeder's whole coupling to auth's and central's schemas, and it is deliberately
  * one object rather than a set of hand-written `COPY` statements, because two consumers must
  * agree on it and the whole value of the §3.4 guard rests on their agreeing *by construction*:
  *
  *   1. [[copyStatement]] builds the `COPY` the seeder actually runs, and
  *   2. [[SutSchemaGuard]] checks this declaration against a live migrated database.
  *
  * A guard reading a second, separately maintained list of columns would pass while the `COPY`
  * wrote something else -- which is the failure mode that manufactures confidence rather than
  * catching anything. There is one list.
  *
  * Column order is the `COPY` order, and [[SeedRows]] emits fields in exactly this order.
  */
object SutSchema:

  /** Which of the SUT's two databases a table lives in. Also selects which migrations directory
    * [[MigrationFingerprint]] has to cover -- see [[fingerprintedMigrationDirectories]].
    */
  enum SchemaOwner(val service: String, val migrationsDirectory: String):
    case Auth extends SchemaOwner("auth", "auth/implementations/postgres/migrations")
    case Central extends SchemaOwner("central", "central/implementations/postgres/migrations")

  /** @param name
    *   the column, exactly as `information_schema.columns` spells it
    * @param udtName
    *   the underlying type name Postgres reports in `information_schema.columns.udt_name`, not
    *   `data_type`: `data_type` collapses every array to the string `ARRAY`, which would make
    *   `passkeys.transports` unverifiable, and reports `character varying` without its length.
    */
  case class SeededColumn(name: String, udtName: String)

  case class SeededTable(owner: SchemaOwner, name: String, columns: List[SeededColumn])

  /** `id` and `claims` are the only columns of `users` that are `NOT NULL` without a default, so
    * they are the only ones the seeder is obliged to write. `phone` is written because it is the
    * credential the campaign's login flows resolve the user by; `email`, `login`, `ui_locales`
    * and `last_version` are left out of the `COPY` and land as NULL, which is what auth's own
    * `register` produces for a phone-registered user.
    *
    * `claims` is `'{}'` rather than a generated profile: nothing in the campaign's flows reads a
    * claim, and 10M rows of synthetic JSONB would change the table's size and its TOAST
    * behaviour, which the capacity plan measures (design doc §5.3).
    */
  val users: SeededTable = SeededTable(
    owner = SchemaOwner.Auth,
    name = "users",
    columns = List(
      SeededColumn("id", "uuid"),
      SeededColumn("phone", "text"),
      SeededColumn("claims", "jsonb"),
    ),
  )

  /** `id` is `SERIAL` and therefore the one seeded column with a default -- it is left to the
    * sequence rather than written, and [[SutSchemaGuard]]'s "every required column is written"
    * rule skips it for exactly that reason.
    *
    * `expires_at` is nullable but written explicitly as NULL: a non-null value makes the row a
    * *temporary* password, and `PasswordService.verifyPassword` partitions on precisely that,
    * answering `TemporaryExpired` for the whole cohort the moment the seeded timestamp passes.
    * Naming the column in the `COPY` is what keeps that decision visible.
    */
  val userPasswords: SeededTable = SeededTable(
    owner = SchemaOwner.Auth,
    name = "user_passwords",
    columns = List(
      SeededColumn("user_id", "uuid"),
      SeededColumn("password", "bytea"),
      SeededColumn("salt", "bytea"),
      SeededColumn("created_at", "timestamptz"),
      SeededColumn("expires_at", "timestamptz"),
    ),
  )

  val userRoles: SeededTable = SeededTable(
    owner = SchemaOwner.Auth,
    name = "user_roles",
    columns = List(
      SeededColumn("user_id", "uuid"),
      SeededColumn("tenant_id", "text"),
      SeededColumn("role_id", "text"),
    ),
  )

  /** `public_key` carries the COSE encoding of the P-256 public key, which is what auth stores
    * (`WebAuthnService`: `publicKey = result.getPublicKeyCose.getBytes`) and what it hands back
    * to the yubico library as `publicKeyCose` on every assertion. `signature_counter` is 0
    * because [[versola.loadgen.protocol.SoftAuthenticator]] signs with a zero counter, and
    * `PostgresPasskeyRepository.updateUsage` has an explicit `0 = 0` arm for authenticators that
    * do not implement one.
    *
    * `attestation_object`, `client_data_json` and `aaguid` are nullable and not written: they are
    * the registration ceremony's evidence, which a seeded credential never produced, and nothing
    * on the assertion path reads them.
    */
  val passkeys: SeededTable = SeededTable(
    owner = SchemaOwner.Auth,
    name = "passkeys",
    columns = List(
      SeededColumn("id", "bytea"),
      SeededColumn("user_id", "uuid"),
      SeededColumn("public_key", "bytea"),
      SeededColumn("signature_counter", "int8"),
      SeededColumn("device_type", "text"),
      SeededColumn("backed_up", "bool"),
      SeededColumn("backup_eligible", "bool"),
      SeededColumn("transports", "_text"),
      SeededColumn("created_at", "timestamptz"),
      SeededColumn("updated_at", "timestamptz"),
    ),
  )

  /** A warm mobile session's server-side half (§10 step 6): `refresh_tokens.id` is what
    * `OAuthTokenService.refreshAccessToken` looks the presented token up by, and every other
    * column here is exactly what a real `authorization_code` exchange writes into the row --
    * see [[SeedRows.refreshTokens]] for where each value comes from.
    *
    * `rotated_at`, `idempotency_key`, `requested_claims`, `ui_locales`, `nonce`, `acr`, `cnf` and
    * `authorization_details` are all nullable and left NULL: they are either rotation/replay
    * bookkeeping a fresh chain has none of yet, or RFC 9396/9449 features this campaign's clients
    * never request (§B: Bearer tokens only, no DPoP, no `authorization_details` parameter).
    * `amr`/`auth_time` are `NOT NULL` and always written, matching what auth itself would have
    * recorded for the credential the user actually logged in with.
    */
  val refreshTokens: SeededTable = SeededTable(
    owner = SchemaOwner.Auth,
    name = "refresh_tokens",
    columns = List(
      SeededColumn("id", "bytea"),
      SeededColumn("family_id", "text"),
      SeededColumn("rotated_at", "timestamptz"),
      SeededColumn("idempotency_key", "bytea"),
      SeededColumn("session_id", "bytea"),
      SeededColumn("public_session_id", "text"),
      SeededColumn("user_id", "uuid"),
      SeededColumn("client_id", "text"),
      SeededColumn("audience", "_text"),
      SeededColumn("scope", "_text"),
      SeededColumn("issued_at", "timestamptz"),
      SeededColumn("expires_at", "timestamptz"),
      SeededColumn("requested_claims", "jsonb"),
      SeededColumn("ui_locales", "_text"),
      SeededColumn("nonce", "text"),
      SeededColumn("acr", "text"),
      SeededColumn("amr", "jsonb"),
      SeededColumn("auth_time", "timestamptz"),
      SeededColumn("cnf", "jsonb"),
      SeededColumn("authorization_details", "_jsonb"),
    ),
  )

  /** Central's routing index (§10 step 5). Written directly rather than through the outbox: the
    * outbox exists to carry a user central created to auth, and the seeder writes both sides
    * itself, so there is nothing left to dispatch.
    */
  val userIndex: SeededTable = SeededTable(
    owner = SchemaOwner.Central,
    name = "user_index",
    columns = List(
      SeededColumn("id", "uuid"),
      SeededColumn("phone", "text"),
    ),
  )

  /** Write order, and therefore also the reverse of the delete order. `users` first because
    * `user_roles.user_id` references it; `refreshTokens` last of auth's tables for the same
    * reason -- it references `users.id` too, just without a declared foreign key (see
    * `refresh_tokens`'s migration).
    */
  val all: List[SeededTable] = List(users, userPasswords, userRoles, passkeys, refreshTokens, userIndex)

  def tablesOwnedBy(owner: SchemaOwner): List[SeededTable] = all.filter(_.owner == owner)

  /** Derived from [[all]] rather than listed, so a seeded table in a service this object does not
    * yet name cannot be added without its migrations directory joining the fingerprint. That is
    * the reason `edge/implementations/postgres/migrations` is absent today despite dev spec §3.4
    * naming it: §10 step 6's warm-start sessions are implemented for the mobile/auth half only
    * (`refreshTokens` above) -- the web half, an edge-side `EDGE_SESSION`, is not, so fingerprinting
    * edge's directory would fail the guard on every unrelated edge migration. A guard that cries
    * wolf gets its expectation file regenerated without being read, which costs more than it buys.
    * Implementing the web half adds an `Edge` owner here and the directory follows.
    */
  val fingerprintedMigrationDirectories: List[String] =
    all.map(_.owner).distinct.sortBy(_.service).map(_.migrationsDirectory)

  /** The `COPY` the seeder runs, in CSV format. CSV rather than the default text format because
    * text format applies backslash de-escaping to every field, and three of the columns above
    * carry values that are themselves backslash-prefixed (`bytea` as `\x...`): the escaping would
    * have to be applied twice, in the right order, and the failure mode of getting it wrong is a
    * silently corrupted hash rather than an error. CSV does no backslash processing at all, so
    * `\x...`, `{...}` array literals and `{}` JSONB all pass through verbatim.
    *
    * `NULL ''` is CSV's default but stated explicitly, because the distinction it draws is
    * load-bearing: an *unquoted* empty field is NULL, a quoted one (`""`) is the empty string,
    * and [[SeedRows]] quotes every non-null value for exactly that reason.
    */
  def copyStatement(table: SeededTable): String =
    s"COPY ${table.name} (${table.columns.map(_.name).mkString(", ")}) FROM STDIN WITH (FORMAT csv, NULL '')"

  /** `ANALYZE` after the load (§10 step 7). Without it the planner spends the first hour of every
    * campaign on empty-table statistics, and the campaign measures that instead of the SUT.
    */
  def analyzeStatement(table: SeededTable): String = s"ANALYZE ${table.name}"
