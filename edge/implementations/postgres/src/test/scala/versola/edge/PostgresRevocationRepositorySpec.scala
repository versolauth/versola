package versola.edge

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.revocation.{RevocationRepository, RevocationRepositoryContractSpec, RevocationRepositoryTestSupport}
import versola.util.postgres.PostgresSpec
import zio.*

/** Binds `RevocationRepositoryContractSpec` to a real Postgres database. What it does *not*
  * cover — whether `activeSince`'s query plan actually uses `revocations_revoked_at_key_idx`
  * rather than a sort of the whole table — was checked by hand with `EXPLAIN ANALYZE` against
  * a populated table, not here: that's a property of Postgres's own optimizer, and there is
  * nothing to abstract it against.
  */
object PostgresRevocationRepositorySpec extends RevocationRepositoryContractSpec:

  override def repositoryLayer: ZLayer[Any, Throwable, RevocationRepository & RevocationRepositoryTestSupport] =
    PostgresSpec.transactor >>> (
      ZLayer.fromFunction((xa: TransactorZIO) => PostgresRevocationRepository(xa): RevocationRepository) ++
        ZLayer.fromFunction: (xa: TransactorZIO) =>
          new RevocationRepositoryTestSupport:
            def reset = xa.connect(sql"DELETE FROM revocations".update.run())
    )
