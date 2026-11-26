package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.util.UUID

object PostgresVelocityRepositorySpec extends PostgresSpec, VelocityRepositorySpec:

  private class PostgresFixtures(xa: TransactorZIO) extends VelocityRepositorySpec.Fixtures:
    override def bucketOfKind(keyType: String, key: String, minute: Long, instance: UUID, count: Int): Task[Unit] =
      xa.connect(
        sql"INSERT INTO af_velocity (key_type, key, minute, instance_id, count) VALUES ($keyType, $key, $minute, $instance, $count)".update.run(),
      ).unit

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield VelocityRepositorySpec.Env(PostgresVelocityRepository(xa), PostgresFixtures(xa))

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
    )
