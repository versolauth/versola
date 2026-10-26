package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.nio.file.{Files, Paths}
import java.time.Instant

object PostgresAntifraudSettingsRepositorySpec extends PostgresSpec:

  private val at = Instant.parse("2026-10-26T10:00:00Z")
  private val tenant = TenantId("tenant-a")

  private def patch(blockAt: Int) =
    TuningPatch(
      delayAt = None,
      challengeAt = None,
      reviewAt = None,
      blockAt = Some(blockAt),
      delayMillis = None,
      fakeSendDelayMillis = None,
      thresholds = None,
      smsLimits = Some(SmsLimits(SmsLimit(10, 0, 0.1), Map("49" -> SmsLimit(2, 5, 0.2)))),
      ruleWeights = Some(Map("velocity_phone_1h" -> 40)),
      shadowRules = Some(Set("velocity_ip_1m")),
    )

  /** The statement of the migration that seeds the global row, as written there. */
  private val seed: String =
    val file = "auth/implementations/postgres/migrations/V0024__antifraud_settings.sql"
    val path = List(file, s"../../../$file", "migrations/V0024__antifraud_settings.sql").map(Paths.get(_)).find(Files.exists(_)).get
    "(?s)INSERT INTO af_settings \\(.*?\\);".r.findFirstIn(Files.readString(path)).get

  private def seeded(xa: TransactorZIO): Task[Unit] =
    xa.connect(summon[DbCon].connection.createStatement().execute(seed)).unit

  /** Empty, with the seeded global row where the next spec of this suite expects the migration to have left it. */
  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudSettingsRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_settings".update.run())
      _ <- xa.connect(sql"DELETE FROM af_settings_audit".update.run())
      _ <- seeded(xa)
    yield PostgresAntifraudSettingsRepository(xa)

  def spec = suite("PostgresAntifraudSettingsRepository")(
    test("the global row the migration seeds is Tuning.off") {
      for
        repository <- clean
        global <- repository.find(None)
      yield assertTrue(global.contains(TuningPatch.of(Tuning.off)), global.flatMap(_.complete).contains(Tuning.off))
    },
    test("stores the global row and a tenant's apart, and reads them back") {
      for
        repository <- clean
        _ <- repository.put(None, patch(100), "alice", at)
        _ <- repository.put(Some(tenant), patch(50), "alice", at)
        global <- repository.find(None)
        own <- repository.find(Some(tenant))
        none <- repository.find(Some(TenantId("tenant-b")))
        all <- repository.loadAll
      yield assertTrue(
        global.contains(patch(100)),
        own.contains(patch(50)),
        none.isEmpty,
        all.toMap == Map(None -> patch(100), Some(tenant) -> patch(50)),
      )
    },
    test("a put replaces the row, and every change moves the version and is audited with before and after") {
      for
        repository <- clean
        v0 <- repository.version
        _ <- repository.put(None, patch(100), "alice", at)
        _ <- repository.put(None, patch(80), "bob", at.plusSeconds(60))
        removed <- repository.delete(None, "carol", at.plusSeconds(120))
        missing <- repository.delete(None, "carol", at.plusSeconds(180))
        v1 <- repository.version
        audit <- repository.audit(None, 10)
        ofTenant <- repository.audit(Some(Some(tenant)), 10)
        ofGlobal <- repository.audit(Some(None), 1)
      yield assertTrue(
        removed,
        !missing,
        v1 == v0 + 3,
        audit.map(_.actor) == List("carol", "bob", "alice"),
        audit.map(r => (r.before.flatMap(_.blockAt), r.after.flatMap(_.blockAt))) == List((Some(80), None), (Some(100), Some(80)), (Some(0), Some(100))),
        audit.forall(_.tenantId.isEmpty),
        ofTenant.isEmpty,
        ofGlobal.size == 1,
      )
    },
  ) @@ TestAspect.sequential
