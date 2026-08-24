package versola.edge.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.edge.PostgresRevocationRepository
import versola.util.postgres.PostgresSpec
import zio.*

/** Binds [[TokenRevocationServiceSyncSpec]] to a real Postgres database. */
object PostgresTokenRevocationServiceSyncSpec extends PostgresSpec, TokenRevocationServiceSyncSpec:

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield TokenRevocationServiceSyncSpec.Env(PostgresRevocationRepository(xa))

  override def beforeEach(env: TokenRevocationServiceSyncSpec.Env) =
    ZIO.serviceWithZIO[TransactorZIO] { xa =>
      xa.connect(sql"TRUNCATE TABLE revocations".update.run())
    }.unit
