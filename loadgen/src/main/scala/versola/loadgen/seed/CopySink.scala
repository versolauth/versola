package versola.loadgen.seed

import com.augustnagro.magnum.DbCon
import com.augustnagro.magnum.magzio.TransactorZIO
import org.postgresql.PGConnection
import versola.util.postgres.BasicCodecs
import zio.{Chunk, Task, ZIO}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection

/** A database the seeder bulk-loads into. Two operations, because those are the only two the
  * seeder performs at scale: stream rows in with `COPY`, and run a statement (the pre-write
  * deletes of the resume path, and `ANALYZE`).
  *
  * `COPY` rather than per-row `INSERT` is the whole point of §10: at 10M rows a batched
  * `INSERT` is ~10M parse/plan/execute cycles and one WAL record per row, where `COPY` is one
  * statement and one bulk path through the table's insert code. This trait exists so the two
  * sides of that -- the SUT's databases, which the seeder holds a bare connection to, and the
  * emulator's own store, which it reaches through the production `TransactorZIO` -- can share
  * the writer without sharing an ownership model.
  */
trait CopySink:
  /** @return the number of rows the server accepted, which the caller compares against what it
    *         sent: a short `COPY` is a silent data-loss bug, and `copyIn` does not report one.
    */
  def copyIn(statement: String, rows: Chunk[String]): Task[Long]

  def execute(statement: String): Task[Unit]

  /** Runs one batch's writes as a unit, so a crash leaves whole batches behind rather than a
    * user with a row in `users` and none in `user_passwords` -- which would be a user the real
    * flow cannot authenticate and which the resume path could not tell from a complete one.
    */
  def atomically[A](effect: Task[A]): Task[A]

object CopySink:

  /** The SUT's databases. A bare JDBC connection, deliberately: the seeder does not own the SUT's
    * schema and must neither migrate nor validate it, and `PostgresHikariDataSource.layer` always
    * runs one or the other -- against a database whose migrations are not in the `loadgen` image
    * at all, `validate()` is either a hard failure or a vacuous pass depending on how Flyway
    * resolves an absent location. Neither is a thing to build a seeder on.
    *
    * A pool would buy nothing besides: `COPY` is serial per table, and the seeder's parallelism
    * is in Argon2, not in the database. One connection per database is the whole requirement.
    */
  final class OfConnection(connection: Connection) extends CopySink:
    private val copyApi = connection.unwrap(classOf[PGConnection]).getCopyAPI

    override def copyIn(statement: String, rows: Chunk[String]): Task[Long] =
      if rows.isEmpty then ZIO.succeed(0L)
      else ZIO.attemptBlocking(copyApi.copyIn(statement, payload(rows)))

    override def execute(statement: String): Task[Unit] =
      ZIO.attemptBlocking:
        val prepared = connection.prepareStatement(statement)
        try prepared.execute()
        finally prepared.close()

    override def atomically[A](effect: Task[A]): Task[A] =
      ZIO.acquireReleaseWith(ZIO.attemptBlocking(connection.setAutoCommit(false)))(_ =>
        ZIO.attemptBlocking(connection.setAutoCommit(true)).orDie,
      ): _ =>
        effect
          .tap(_ => ZIO.attemptBlocking(connection.commit()))
          .tapError(_ => ZIO.attemptBlocking(connection.rollback()).ignore)

  /** The emulator's own store, through the same `TransactorZIO` every other repository uses --
    * this schema `loadgen` does own, so it goes through the wiring that migrates it.
    *
    * The `COPY` runs *inside* `connect`, not on a connection borrowed out of it, so the pool's
    * lifecycle is unchanged.
    */
  final class OfTransactor(xa: TransactorZIO) extends CopySink, BasicCodecs:
    override def copyIn(statement: String, rows: Chunk[String]): Task[Long] =
      if rows.isEmpty then ZIO.succeed(0L)
      else
        xa.connectMeasured("seed-copy-in"):
          summon[DbCon].connection.unwrap(classOf[PGConnection]).getCopyAPI.copyIn(statement, payload(rows))

    override def execute(statement: String): Task[Unit] =
      xa.connectMeasured("seed-execute"):
        val prepared = summon[DbCon].connection.prepareStatement(statement)
        try prepared.execute()
        finally prepared.close()
      .unit

    /** Runs the effect unwrapped, and the seeder's use of it is what makes that sound: the
      * emulator store gets exactly one `COPY` per batch, which is already one statement and
      * therefore already atomic. Spanning a real transaction across this block would mean
      * holding one pool connection open around arbitrary effects, which is exactly what
      * `TransactorZIO`'s `connect`/`transact` scoping exists to prevent.
      */
    override def atomically[A](effect: Task[A]): Task[A] = effect

  /** One batch, one buffer. The rows are already in memory (the batch is bounded by
    * `seed.batch-size`) so streaming them from a `PipedInputStream` would add a thread and a
    * failure mode to save a copy of a few megabytes.
    */
  private def payload(rows: Chunk[String]): ByteArrayInputStream =
    val out = StringBuilder()
    rows.foreach(row => out.append(row).append('\n'))
    ByteArrayInputStream(out.toString.getBytes(StandardCharsets.UTF_8))
