package versola.edge

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.attestation.{AttestedKeyRepositorySpec, IosEnvironment}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

/** The wiring of [[AttestedKeyRepositorySpec]] to Postgres, and what only the table promises: how long a key is remembered, the index
  * the cleanup of expired rows reads, and a counter wider than an int. */
object PostgresAttestedKeyRepositorySpec extends PostgresSpec, AttestedKeyRepositorySpec:

  given DbCodec[Instant] = DbCodec.InstantCodec

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield AttestedKeyRepositorySpec.Env(PostgresAttestedKeyRepository(xa), xa)

  override def beforeEach(env: AttestedKeyRepositorySpec.Env) =
    env.xa.connect(sql"TRUNCATE TABLE attested_keys".update.run()).unit

  override def implementationCases(env: AttestedKeyRepositorySpec.Env) =
    List(
      test("a key is remembered for a year from when it was recorded, and every assertion moves that forward") {
        def expiry =
          env.xa.connect(sql"SELECT expires_at FROM attested_keys WHERE client_id = 'mobile' AND key_id = 'key-1'".query[Instant].run().head)
        for
          _ <- env.repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
          recorded <- expiry
          _ <- env.repository.advance("mobile", "key-1", 1, t0.plusSeconds(100 * 86400))
          used <- expiry
        yield assertTrue(
          recorded == t0.plusSeconds(365 * 86400),
          used == t0.plusSeconds(465 * 86400),
        )
      },
      test("the table has what the cleanup of expired rows needs: an expires_at column and an index that leads with it") {
        for
          indexed <- env.xa.connect:
            sql"""
              SELECT COUNT(*) FROM pg_index i
              JOIN pg_attribute ia ON ia.attrelid = i.indrelid AND ia.attnum = i.indkey[0]
              WHERE i.indrelid = 'attested_keys'::regclass AND ia.attname = 'expires_at'
            """.query[Long].run().head
        yield assertTrue(indexed >= 1L)
      },
      test("a counter above what an int holds is kept as it is") {
        for
          _ <- env.repository.register("mobile", "key-1", key, IosEnvironment.production, t0)
          _ <- env.repository.advance("mobile", "key-1", 0xffffffffL, t0)
          found <- env.repository.find("mobile", "key-1")
        yield assertTrue(found.exists(_.counter == 0xffffffffL))
      },
    )
