package versola.edge.revocation

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.PostgresRevocationCursorRepository
import versola.util.postgres.PostgresSpec
import zio.*

object PostgresRevocationCursorRepositorySpec extends PostgresSpec, RevocationCursorRepositorySpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield RevocationCursorRepositorySpec.Env(
        repository = PostgresRevocationCursorRepository(xa),
        fixtures = new RevocationCursorRepositorySpec.Fixtures:
          override def expireLease(source: String) =
            xa.connect(sql"UPDATE revocation_cursors SET lease_until = NOW() - interval '1 second' WHERE source = $source".update.run()).unit

          override def cursorOf(source: String) =
            xa.connect(sql"SELECT cursor FROM revocation_cursors WHERE source = $source".query[String].run().headOption)

          override def leaseRemaining(source: String) =
            xa.connect(
              sql"SELECT (extract(epoch FROM (lease_until - NOW())) * 1000)::bigint FROM revocation_cursors WHERE source = $source"
                .query[Long].run().head,
            ).map(Duration.fromMillis),
      )

  override def beforeEach(env: RevocationCursorRepositorySpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE revocation_cursors".update.run())
    }.unit
