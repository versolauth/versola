package versola.central.configuration.challenges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.test.*

/** Conformance suite for any [[ChallengeSettingsRepository]] implementation. A backend module
  * extends this and supplies the wiring -- see `PostgresChallengeSettingsRepositorySpec` in
  * `central-postgres-impl` for the one binding that exists today.
  */
trait ChallengeSettingsRepositorySpec extends DatabaseSpecBase[ChallengeSettingsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val tenantA = TenantId("tenant-a")
  private val tenantB = TenantId("tenant-b")

  private val passkeySettings = PasskeySettings(
    rpId = "localhost",
    rpName = "Versola",
    origins = List("http://localhost:8080"),
    userVerification = "preferred",
  )

  private def settingsFor(tenantId: TenantId) = ChallengeSettingsRecord(
    tenantId = tenantId,
    allowedPrefixes = List("+1", "+44"),
    submissionLimits = SubmissionLimits(),
    otpLength = 6,
    otpResendAfter = 30,
    passkeySettings = passkeySettings,
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 3600,
    sessionIdleTtlSeconds = Some(1800),
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Forwarded-For",
    acrVocabulary = None,
    postLogoutRedirectUris = List("https://example.com/logout"),
  )

  override def testCases(env: ChallengeSettingsRepositorySpec.Env) =
    List(
      test("getAll returns nothing when the table is empty") {
        for all <- env.repository.getAll
        yield assertTrue(all.isEmpty)
      },
      test("findByTenant returns None for an unknown tenant") {
        for found <- env.repository.findByTenant(tenantA)
        yield assertTrue(found.isEmpty)
      },
      test("upsert stores settings retrievable by findByTenant and getAll") {
        val record = settingsFor(tenantA)
        for
          _ <- env.repository.upsert(record)
          found <- env.repository.findByTenant(tenantA)
          all <- env.repository.getAll
        yield assertTrue(found == Some(record), all == Vector(record))
      },
      test("upsert on an existing tenant replaces the settings") {
        val original = settingsFor(tenantA)
        val updated = original.copy(otpLength = 8, ipHeader = "X-Real-IP")
        for
          _ <- env.repository.upsert(original)
          _ <- env.repository.upsert(updated)
          found <- env.repository.findByTenant(tenantA)
          all <- env.repository.getAll
        yield assertTrue(found == Some(updated), all == Vector(updated))
      },
      test("getAll orders by tenant id") {
        for
          _ <- env.repository.upsert(settingsFor(tenantB))
          _ <- env.repository.upsert(settingsFor(tenantA))
          all <- env.repository.getAll
        yield assertTrue(all.map(_.tenantId) == Vector(tenantA, tenantB))
      },
    )

object ChallengeSettingsRepositorySpec:
  case class Env(repository: ChallengeSettingsRepository)
