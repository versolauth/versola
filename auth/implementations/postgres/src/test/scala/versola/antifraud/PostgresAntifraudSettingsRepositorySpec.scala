package versola.antifraud

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

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

  private val clean: ZIO[TransactorZIO, Throwable, PostgresAntifraudSettingsRepository] =
    for
      xa <- ZIO.service[TransactorZIO]
      _ <- xa.connect(sql"DELETE FROM af_settings".update.run())
      _ <- xa.connect(sql"DELETE FROM af_settings_audit".update.run())
    yield PostgresAntifraudSettingsRepository(xa)

  def spec = suite("PostgresAntifraudSettingsRepository")(
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
        audit.map(r => (r.before.flatMap(_.blockAt), r.after.flatMap(_.blockAt))) == List((Some(80), None), (Some(100), Some(80)), (None, Some(100))),
        audit.forall(_.tenantId.isEmpty),
        ofTenant.isEmpty,
        ofGlobal.size == 1,
      )
    },
  ) @@ TestAspect.sequential
