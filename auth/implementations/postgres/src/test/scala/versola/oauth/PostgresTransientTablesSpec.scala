package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.postgres.PostgresSpec
import zio.ZIO
import zio.test.*

/** `UNLOGGED` is a property of the table, and nothing but WAL volume notices when it is lost: a
  * table recreated or copied in a later migration is logged again and every test still passes.
  * These three hold minutes-old login state; see V0014__unlogged_transient_auth_tables.sql.
  */
object PostgresTransientTablesSpec extends PostgresSpec:

  private val tables = List("auth_conversations", "authorization_codes", "pushed_authorization_requests")

  override def spec =
    suite("PostgresTransientTablesSpec")(
      test("holds the short-lived login tables unlogged") {
        for
          xa <- ZIO.service[TransactorZIO]
          logged <- xa.connect:
            sql"""
              SELECT relname FROM pg_class
              WHERE relname IN ('auth_conversations', 'authorization_codes', 'pushed_authorization_requests')
                AND relkind = 'r' AND relpersistence <> 'u'
            """.query[String].run()
          present <- xa.connect:
            sql"""
              SELECT relname FROM pg_class
              WHERE relname IN ('auth_conversations', 'authorization_codes', 'pushed_authorization_requests')
                AND relkind = 'r'
            """.query[String].run()
        yield assertTrue(logged.isEmpty, present.toSet == tables.toSet)
      },
    )
