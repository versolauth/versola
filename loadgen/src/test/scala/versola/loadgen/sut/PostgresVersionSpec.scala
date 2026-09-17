package versola.loadgen.sut

import zio.test.*

/** Which columns the reader asks a given server for. Pinned as a table rather than left to the
  * `SELECT`s, because the failure it prevents is not subtle: a column list written for one major
  * does not return less on another, it fails the statement and takes the campaign's whole
  * database section with it.
  */
object PostgresVersionSpec extends ZIOSpecDefault:

  private def version(major: Int): PostgresVersion = PostgresVersion(major * 10_000 + 4)

  def spec = suite("PostgresVersion")(
    test("reads the major out of server_version_num") {
      assertTrue(PostgresVersion(180_004).major == 18, PostgresVersion(150_012).major == 15)
    },
    test("pg_stat_wal from 14, pg_stat_checkpointer from 17") {
      assertTrue(
        !version(13).hasStatWal,
        version(14).hasStatWal,
        !version(16).hasStatCheckpointer,
        version(17).hasStatCheckpointer,
      )
    },
    // `pg_stat_io` itself exists from 16, but not yet with `object = 'wal'` rows: those arrived
    // in 18. `hasStatIo` gates that query, so it has to track the row's arrival and not the
    // view's -- on 16 or 17 the query would run and return nothing, and its own `coalesce`
    // would then report a real zero rather than the `None` those majors actually mean.
    test("pg_stat_io's own WAL rows are asked for only from 18, not from 16 where the view first exists") {
      assertTrue(
        !version(16).hasStatIo,
        !version(17).hasStatIo,
        version(18).hasStatIo,
      )
    },
    // 18's own additions: the WAL byte volumes that replaced `op_bytes`, the two checkpointer
    // totals, and `total_autovacuum_time`.
    test("the columns 18 added are asked for only from 18") {
      assertTrue(
        !version(17).hasStatIoBytes,
        version(18).hasStatIoBytes,
        !version(17).hasCheckpointerTotals,
        version(18).hasCheckpointerTotals,
        !version(17).hasVacuumTimes,
        version(18).hasVacuumTimes,
      )
    },
    // A major nobody has written a column list for yet is read with the newest one known here.
    // The alternative is a release of this binary per release of Postgres.
    test("a newer major is read with 18's shape") {
      assertTrue(
        version(19).hasStatWal,
        version(19).hasStatIo,
        version(19).hasStatCheckpointer,
        version(19).hasStatIoBytes,
      )
    },
  )
