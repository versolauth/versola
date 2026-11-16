package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

object PostgresAntifraudCounterResetRepositorySpec extends PostgresSpec, AntifraudCounterResetRepositorySpec:

  import AntifraudCounterResetRepositorySpec.*

  private class PostgresFixtures(xa: TransactorZIO) extends Fixtures:
    override def resetOfScope(generation: Long, scope: String, requestedBy: String): Task[Unit] =
      xa.connect(
        sql"INSERT INTO af_counter_reset (generation, scope, requested_by, at) VALUES ($generation, $scope, $requestedBy, now())".update.run(),
      ).unit

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresAntifraudCounterResetRepository(xa), PostgresFixtures(xa))

  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_counter_reset".update.run())
    yield ()

  override def implementationCases(env: Env) =
    List(
      test("it is under the statement timeout") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudCounterResetRepository(xa, statementTimeout = 100.millis)
          holder <- xa.transact:
            sql"LOCK TABLE af_counter_reset IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(2000)
          .fork
          _ <- Live.live(ZIO.sleep(400.millis))
          recorded <- impatient.record(whole, "x", at).exit
          latest <- impatient.latest.exit
          _ <- holder.join
        yield assertTrue(recorded.isFailure, latest.isFailure)
      },
    )
