package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.time.Instant

object PostgresAntifraudSettingsRepositorySpec extends PostgresSpec, AntifraudSettingsRepositorySpec:

  import AntifraudSettingsRepositorySpec.*

  private given DbCodec[Instant] = DbCodec.InstantCodec

  override lazy val environment =
    ZLayer:
      for
        xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresAntifraudSettingsRepository(xa))

  /** Empty, as the migration leaves it; the version row is kept, since a change creates it. */
  override def beforeEach(env: Env) =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_settings".update.run())
      _ <- xa.connect(sql"DELETE FROM af_settings_version".update.run())
    yield ()

  override def implementationCases(env: Env) =
    List(
      test("a value that is not a Tuning fails the read the way the codec reports it, and the row is left as it is") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(
            sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"challengeAt": "many"}'::jsonb, 'hand', $at)""".update.run(),
          )
          failure <- env.repository.find.flip
          raw <- xa.connect(sql"SELECT settings::text FROM af_settings WHERE id = 1".query[String].run().head)
        yield assertTrue(failure.isInstanceOf[SqlException], failure.getMessage.contains("challengeAt(expected an Int)"), raw.contains("many"))
      },
      test("a row written before a key existed reads with the key's default: shadow for the mode, off for the rest") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(
            sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"blockAt": 80, "shadowRules": ["velocity_ip_1m"]}'::jsonb, 'old', $at)""".update.run(),
          )
          stored <- env.repository.find
        yield assertTrue(
          stored.map(_.tuning).contains(Tuning.off.copy(blockAt = 80, shadowRules = Set("velocity_ip_1m"))),
          stored.exists(_.tuning.mode == TuningMode.shadow),
        )
      },
      test("a row that names a key retired by an earlier build still reads; one that names a key nothing knows fails the read") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- xa.connect(
            sql"""INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (1, '{"blockAt": 80, "delayAt": 3, "delayMillis": 2000}'::jsonb, 'old', $at)""".update.run(),
          )
          retired <- env.repository.find
          _ <- xa.connect(sql"""UPDATE af_settings SET settings = '{"blokAt": 80}'::jsonb WHERE id = 1""".update.run())
          unknown <- env.repository.find.flip
        yield assertTrue(
          retired.map(_.tuning).contains(Tuning.off.copy(blockAt = 80)),
          unknown.isInstanceOf[SqlException],
          unknown.getMessage.contains("invalid extra field"),
        )
      },
      test("every method fails on its statement timeout instead of waiting for a lock") {
        for
          xa <- ZIO.service[TransactorZIO]
          _ <- env.repository.seed(Tuning.off, "bootstrap", at)
          impatient = PostgresAntifraudSettingsRepository(xa, statementTimeout = 200.millis)
          // A transaction that holds the table, as a long migration or a stuck writer would.
          holder <- xa.transact:
            sql"LOCK TABLE af_settings IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(3000)
          .fork
          _ <- Live.live(ZIO.sleep(500.millis))
          find <- Live.live(impatient.find.exit)
          put <- Live.live(impatient.put(tuned, "alice", at, Some(1)).exit)
          seed <- Live.live(impatient.seed(Tuning.off, "bootstrap", at).exit)
          _ <- holder.join
          healthy <- impatient.find
        yield assertTrue(find.isFailure, put.isFailure, seed.isFailure, healthy.map(_.version).contains(1L))
      },
      test("the timeout covers the version read, which sees a table that is not locked") {
        for
          xa <- ZIO.service[TransactorZIO]
          impatient = PostgresAntifraudSettingsRepository(xa, statementTimeout = 200.millis)
          holder <- xa.transact:
            sql"LOCK TABLE af_settings_version IN ACCESS EXCLUSIVE MODE".update.run()
            Thread.sleep(3000)
          .fork
          _ <- Live.live(ZIO.sleep(500.millis))
          version <- Live.live(impatient.version.exit)
          _ <- holder.join
        yield assertTrue(version.isFailure)
      },
    )
