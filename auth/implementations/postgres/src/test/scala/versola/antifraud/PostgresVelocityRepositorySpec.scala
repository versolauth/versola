package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.util.UUID

object PostgresVelocityRepositorySpec extends PostgresSpec, VelocityRepositorySpec:

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield VelocityRepositorySpec.Env(PostgresVelocityRepository(xa))

  override def beforeEach(env: VelocityRepositorySpec.Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_velocity".update.run())
    yield ()

  override def implementationCases(env: VelocityRepositorySpec.Env) =
    List(
      test("every statement is under the statement timeout, forgetting included") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresVelocityRepository(xa, statementTimeout = 100.millis)
          holder <- xa.transact:
            sql"LOCK TABLE af_velocity IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(2500)
          .fork
          _ <- Live.live(ZIO.sleep(400.millis))
          forgotten <- impatient.forget(VelocityTarget.All, None).exit
          loaded <- impatient.loadOthers(UUID.randomUUID(), 0, 1000, 10).exit
          _ <- holder.join
        yield assertTrue(forgotten.isFailure, loaded.isFailure)
      },
      test("a value in the table that is not a key kind is left out, not a failure") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(
            sql"INSERT INTO af_velocity (key_type, key, minute, instance_id, count) VALUES ('future', 'x', 100, ${UUID.randomUUID()}, 1)".update.run(),
          )
          seen <- env.repository.loadOthers(UUID.randomUUID(), 0, Long.MaxValue, 100)
        yield assertTrue(seen.isEmpty)
      },
    )
