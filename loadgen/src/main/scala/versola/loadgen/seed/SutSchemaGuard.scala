package versola.loadgen.seed

import versola.loadgen.seed.SutSchema.{SchemaOwner, SeededTable}
import zio.{Chunk, Task, ZIO}

import java.sql.Connection

/** The semantic half of the dev spec §3.4 anti-drift guard: [[SutSchema]] checked against a
  * *live, migrated* SUT database.
  *
  * §3.4 asks for "a checked-in hash of the migrations directory listing", which
  * [[MigrationFingerprint]] provides. That alone is a tripwire, not a guard -- it tells a human
  * that auth's schema moved, and is silenced by regenerating one file. This is the part that
  * bites without a human in the loop, and it answers the question the tripwire cannot:
  * *does the population the seeder writes still satisfy the schema the real login flow reads?*
  *
  * Three findings, chosen because each corresponds to a way the seeder breaks silently rather
  * than loudly:
  *
  *   - [[Finding.MissingTable]] / [[Finding.MissingColumn]] -- the `COPY` would fail at campaign
  *     time, after the Argon2 bill has been paid. Cheap to catch, loud when it happens.
  *   - [[Finding.TypeChanged]] -- the `COPY` may well *succeed* and store something the login
  *     path cannot read back. This is the dangerous one.
  *   - [[Finding.RequiredColumnNotWritten]] -- auth gained a `NOT NULL` column with no default.
  *     The `COPY` fails, which is fine, but the point of catching it here is that the decision
  *     "what value should a seeded user have for this?" is auth's to answer, not something to
  *     discover at 3am halfway through a seed.
  *
  * What it does not catch is stated plainly because the gap matters: it sees columns, so it is
  * blind to a new unique index, a `CHECK` constraint, a trigger, and to any *semantic*
  * requirement that is not expressed in the column list -- a claim the login flow starts
  * requiring inside `users.claims`, say. Those are what [[MigrationFingerprint]] and the
  * end-to-end smoke test are for, in that order.
  */
object SutSchemaGuard:

  enum Finding(val message: String):
    case MissingTable(table: String) extends Finding(s"table '$table' does not exist")

    case MissingColumn(table: String, column: String)
        extends Finding(s"$table.$column is written by the seeder but does not exist")

    case TypeChanged(table: String, column: String, declared: String, actual: String)
        extends Finding(s"$table.$column is declared as '$declared' but is '$actual'")

    case RequiredColumnNotWritten(table: String, column: String)
        extends Finding(
          s"$table.$column is NOT NULL with no default, and the seeder does not write it",
        )

  /** One column as `information_schema` describes it. */
  private case class ActualColumn(name: String, udtName: String, nullable: Boolean, hasDefault: Boolean)

  /** Checks every table [[SutSchema]] declares for this owner. The connection must be open
    * against the database that owns those tables -- auth's and central's schemas live in
    * separate databases, so one call per owner.
    *
    * Deliberately returns findings rather than failing: the caller decides whether an empty list
    * is an assertion or a log line, and a seeder that refuses to start needs all of them in one
    * message, not the first.
    */
  def check(connection: Connection, owner: SchemaOwner): Task[Chunk[Finding]] =
    ZIO.attemptBlocking:
      Chunk.fromIterable(SutSchema.tablesOwnedBy(owner).flatMap(check(connection, _)))

  private def check(connection: Connection, table: SeededTable): List[Finding] =
    val actual = describe(connection, table.name)
    if actual.isEmpty then List(Finding.MissingTable(table.name))
    else
      val byName = actual.map(column => column.name -> column).toMap
      val written = table.columns.map(_.name).toSet

      val declared = table.columns.flatMap: column =>
        byName.get(column.name) match
          case None => Some(Finding.MissingColumn(table.name, column.name))
          case Some(found) if found.udtName != column.udtName =>
            Some(Finding.TypeChanged(table.name, column.name, column.udtName, found.udtName))
          case Some(_) => None

      // The rule that catches an *added* requirement rather than a removed one. A column that is
      // nullable, or that has a default, is one the database can fill in for a seeded row; one
      // that is neither is a value auth expects every user to carry, and a seeded user that does
      // not carry it is not a user the real flow can authenticate.
      val required = actual
        .filter(column => !column.nullable && !column.hasDefault && !written.contains(column.name))
        .map(column => Finding.RequiredColumnNotWritten(table.name, column.name))

      declared ++ required

  private def describe(connection: Connection, table: String): List[ActualColumn] =
    val statement = connection.prepareStatement(
      """SELECT column_name, udt_name, is_nullable, column_default
         FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = ?
         ORDER BY ordinal_position""",
    )
    try
      statement.setString(1, table)
      val rows = statement.executeQuery()
      try
        val builder = List.newBuilder[ActualColumn]
        while rows.next() do
          builder += ActualColumn(
            name = rows.getString("column_name"),
            udtName = rows.getString("udt_name"),
            nullable = rows.getString("is_nullable") == "YES",
            hasDefault = rows.getString("column_default") != null,
          )
        builder.result()
      finally rows.close()
    finally statement.close()

  /** What the seeder prints and dies with when the schema it was built against is not the schema
    * in front of it. Named rather than inlined so the wording is identical in the pre-flight
    * check and in the test that proves the check fires.
    */
  def report(owner: SchemaOwner, findings: Chunk[Finding]): String =
    s"The seeder is coupled to ${owner.service}'s schema (dev spec §3.4) and that schema has " +
      s"moved:\n" + findings.map(finding => "  - " + finding.message).mkString("\n") +
      "\nUpdate versola.loadgen.seed.SutSchema and SeedRows to match, then re-record " +
      s"${MigrationFingerprint.expectationResource}."
