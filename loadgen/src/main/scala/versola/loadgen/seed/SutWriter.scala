package versola.loadgen.seed

import versola.loadgen.seed.SutSchema.SchemaOwner
import zio.{Chunk, Task, ZIO}

import java.time.Instant

/** Writes one batch of seeded users into the SUT's two databases (versola-loadgen-dev-spec.md
  * §10 steps 4-5), and runs the `ANALYZE` of step 7.
  *
  * Each database's writes are one transaction, so a batch is all-or-nothing per database. There
  * is no transaction spanning both: auth's and central's schemas live in separate databases, and
  * two-phase commit across them to make a load-test population marginally more consistent is not
  * a trade anyone would take. The consequence is bounded and stated: a crash between the two
  * commits leaves one batch of users in auth that central's routing index does not know about,
  * and the resume path's [[deleteRange]] removes them before rewriting the range.
  */
final class SutWriter(auth: CopySink, central: CopySink):

  def write(
      seeded: Chunk[SeededUser],
      tenantId: String,
      now: Instant,
      from: Long,
      until: Long,
  ): Task[Unit] =
    for
      _ <- auth.atomically:
        for
          _ <- deleteRange(auth, SchemaOwner.Auth, from, until)
          _ <- copy(auth, SutSchema.users, seeded.map(SeedRows.users))
          _ <- copy(auth, SutSchema.userPasswords, withPasswords(seeded, now))
          _ <- copy(auth, SutSchema.userRoles, seeded.map(SeedRows.userRoles(_, tenantId)))
          _ <- copy(auth, SutSchema.passkeys, withPasskeys(seeded, now))
        yield ()
      _ <- central.atomically:
        for
          _ <- deleteRange(central, SchemaOwner.Central, from, until)
          _ <- copy(central, SutSchema.userIndex, seeded.map(SeedRows.userIndex))
        yield ()
    yield ()

  def analyze: Task[Unit] =
    ZIO.foreachDiscard(SutSchema.all): table =>
      sinkFor(table.owner).execute(SutSchema.analyzeStatement(table))

  private def sinkFor(owner: SchemaOwner): CopySink = owner match
    case SchemaOwner.Auth => auth
    case SchemaOwner.Central => central

  private def copy(sink: CopySink, table: SutSchema.SeededTable, rows: Chunk[String]): Task[Unit] =
    sink.copyIn(SutSchema.copyStatement(table), rows).flatMap: accepted =>
      ZIO
        .fail(ShortCopy(table.name, rows.size.toLong, accepted))
        .when(accepted != rows.size.toLong)
        .unit

  private def withPasswords(seeded: Chunk[SeededUser], now: Instant): Chunk[String] =
    seeded.flatMap(user => user.password.map(SeedRows.userPasswords(user, _, now)))

  private def withPasskeys(seeded: Chunk[SeededUser], now: Instant): Chunk[String] =
    seeded.flatMap(user => user.passkey.map(SeedRows.passkeys(user, _, now)))

  /** Clears the id range this batch is about to write, so a rerun and a resume are both safe.
    *
    * By **phone range**, not by a list of user ids. `PopulationPlan.phoneOf` is fixed-width, so
    * the id range `[from, until)` is a contiguous, lexicographically ordered `BETWEEN` -- one
    * sargable predicate against `users_phone_idx` rather than a `= ANY($array)` carrying ten
    * thousand UUID literals per batch. The dependent tables key on `user_id` and reach it through
    * a subquery on that same index.
    *
    * Correct because `users` is written first inside the transaction above: if a `users` row for
    * an id exists at all, this finds it, and if it does not, there is nothing dependent on it to
    * find. Each `COPY` is one statement, so there is no partially-written `users` batch.
    *
    * The bounds are interpolated rather than bound as parameters. They are not input: they are
    * `PopulationPlan.phoneOf` of a `Long`, which is `+49151` followed by eight digits and can
    * carry no quote, and [[CopySink.execute]] takes no parameters precisely so that the two
    * statements it is given (this and `ANALYZE`) stay visible as literals.
    */
  private[seed] def deleteRange(sink: CopySink, owner: SchemaOwner, from: Long, until: Long): Task[Unit] =
    val low = PopulationPlan.phoneOf(from)
    val high = PopulationPlan.phoneOf(until - 1)
    val range = s"phone BETWEEN '$low' AND '$high'"
    owner match
      case SchemaOwner.Auth =>
        val owned = s"(SELECT id FROM users WHERE $range)"
        ZIO.foreachDiscard(
          List(
            s"DELETE FROM user_passwords WHERE user_id IN $owned",
            s"DELETE FROM passkeys WHERE user_id IN $owned",
            s"DELETE FROM user_roles WHERE user_id IN $owned",
            s"DELETE FROM users WHERE $range",
          ),
        )(sink.execute)
      case SchemaOwner.Central =>
        sink.execute(s"DELETE FROM user_index WHERE $range")
