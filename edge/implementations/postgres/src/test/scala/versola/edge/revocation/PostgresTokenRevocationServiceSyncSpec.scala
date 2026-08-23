package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.PostgresRevocationRepository
import versola.util.postgres.PostgresSpec
import zio.*

/** Binds `TokenRevocationServiceSyncContractSpec` to a real Postgres database. */
object PostgresTokenRevocationServiceSyncSpec extends TokenRevocationServiceSyncContractSpec:

  override def repositoryLayer: ZLayer[Any, Throwable, RevocationRepository & RevocationRepositoryTestSupport] =
    PostgresSpec.transactor >>> (
      ZLayer.fromFunction((xa: TransactorZIO) => PostgresRevocationRepository(xa): RevocationRepository) ++
        ZLayer.fromFunction: (xa: TransactorZIO) =>
          new RevocationRepositoryTestSupport:
            def reset = xa.connect(sql"DELETE FROM revocations".update.run())
    )
