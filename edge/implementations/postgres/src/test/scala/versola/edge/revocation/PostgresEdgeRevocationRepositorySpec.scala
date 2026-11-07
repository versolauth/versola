package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.PostgresEdgeRevocationRepository
import versola.util.postgres.PostgresSpec
import versola.util.revocation.{RevocationCacheSyncSpec, RevocationRepositorySpec}
import zio.*

/** Binds [[RevocationRepositorySpec]] to edge's `revocations` table. What it does *not* cover:
  * whether `activeSince`'s query plan actually uses `revocations_revoked_at_key_idx` rather than
  * a sort of the whole table was checked by hand with `EXPLAIN ANALYZE` against a populated
  * table, not here: that's a property of Postgres's own optimizer, and there is nothing to
  * abstract it against.
  */
object PostgresEdgeRevocationRepositorySpec extends PostgresSpec, RevocationRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresEdgeRevocationRepository(xa)
        RevocationRepositorySpec.Env(repository = repository, revoke = revocation => repository.revokeAll(List(revocation)))

  override def beforeEach(env: RevocationRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE revocations".update.run())
    }.unit

/** Binds [[RevocationCacheSyncSpec]] to edge's `revocations` table. */
object PostgresEdgeRevocationCacheSyncSpec extends PostgresSpec, RevocationCacheSyncSpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield
        val repository = PostgresEdgeRevocationRepository(xa)
        RevocationCacheSyncSpec.Env(repository = repository, revoke = revocation => repository.revokeAll(List(revocation)))

  override def beforeEach(env: RevocationCacheSyncSpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE revocations".update.run())
    }.unit
