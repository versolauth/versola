package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.revocation.RevocationRepositorySpec
import versola.util.postgres.PostgresSpec
import zio.*

/** Binds [[RevocationRepositorySpec]] to a real Postgres database. What it does *not* cover —
  * whether `activeSince`'s query plan actually uses `revocations_revoked_at_key_idx` rather
  * than a sort of the whole table — was checked by hand with `EXPLAIN ANALYZE` against a
  * populated table, not here: that's a property of Postgres's own optimizer, and there is
  * nothing to abstract it against.
  */
object PostgresRevocationRepositorySpec extends PostgresSpec, RevocationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield RevocationRepositorySpec.Env(PostgresRevocationRepository(xa))

  override def beforeEach(env: RevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE revocations".update.run())
    }.unit
