package versola.central.configuration.challenges

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.ZIO
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
    phoneRegions = List(PhoneRegion("US", "+1", 10, "[2-9]\\d{9}", true), PhoneRegion("GB", "+44", 10, "7\\d{9}", true)),
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
    submissionLimits = SubmissionLimits.empty,
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
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = None,
    logoDark = None,
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
      test("upsert reports that it wrote a tenant's settings") {
        val original = settingsFor(tenantA)
        for
          created <- env.repository.upsert(original)
          replaced <- env.repository.upsert(original.copy(otpLength = 8))
        yield assertTrue(created, replaced)
      },
      // The profile is fixed at creation; the write itself refuses to move it, so two creations
      // of one tenant racing under different profiles cannot both land.
      test("upsert leaves the settings of a tenant stored under another profile untouched") {
        val original = settingsFor(tenantA)
        for
          _ <- env.repository.upsert(original)
          written <- env.repository.upsert(original.copy(otpLength = 8, securityProfile = SecurityProfile.standard))
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(!written, found == Some(original))
      },
      test("upsert round-trips a configured mTLS certificate header and encoding") {
        val record = settingsFor(tenantA).copy(
          mtlsCertificateHeader = Some("ssl-client-cert"),
          mtlsCertificateEncoding = Some(MtlsCertificateEncoding.urlEncodedPem),
        )
        for
          _ <- env.repository.upsert(record)
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(found == Some(record))
      },
      test("patch applies the change to the stored row and returns the stored result") {
        val original = settingsFor(tenantA)
        for
          _ <- env.repository.upsert(original)
          result <- env.repository.patch(tenantA, record => Right(record.copy(otpLength = 8, sessionIdleTtlSeconds = None)))
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(
          result == Some(Right(original.copy(otpLength = 8, sessionIdleTtlSeconds = None))),
          found == Some(original.copy(otpLength = 8, sessionIdleTtlSeconds = None)),
        )
      },
      test("patch leaves the other tenant's settings alone") {
        val a = settingsFor(tenantA)
        val b = settingsFor(tenantB)
        for
          _ <- env.repository.upsert(a)
          _ <- env.repository.upsert(b)
          _ <- env.repository.patch(tenantA, record => Right(record.copy(otpLength = 8)))
          foundB <- env.repository.findByTenant(tenantB)
        yield assertTrue(foundB == Some(b))
      },
      test("patch returns None and writes nothing for a tenant with no settings") {
        for
          result <- env.repository.patch(tenantA, record => Right(record.copy(otpLength = 8)))
          all <- env.repository.getAll
        yield assertTrue(result.isEmpty, all.isEmpty)
      },
      test("patch writes nothing when the change refuses") {
        val original = settingsFor(tenantA)
        for
          _ <- env.repository.upsert(original)
          result <- env.repository.patch(tenantA, _ => Left("refused"))
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(result == Some(Left("refused")), found == Some(original))
      },
      test("patch does not move the tenant's security profile") {
        val original = settingsFor(tenantA)
        for
          _ <- env.repository.upsert(original)
          _ <- env.repository.patch(tenantA, record => Right(record.copy(otpLength = 8, securityProfile = SecurityProfile.standard)))
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(found == Some(original.copy(otpLength = 8)))
      },
      test("patch round-trips nested and optional members") {
        val acr = Some(Map("urn:acr:silver" -> List("pwd")))
        for
          _ <- env.repository.upsert(settingsFor(tenantA))
          _ <- env.repository.patch(
            tenantA,
            record =>
              Right(
                record.copy(
                  acrVocabulary = acr,
                  mtlsCertificateHeader = Some("ssl-client-cert"),
                  mtlsCertificateEncoding = Some(MtlsCertificateEncoding.base64Der),
                  passkeySettings = passkeySettings.copy(rpId = "example.com"),
                ),
              ),
          )
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(
          found.map(_.acrVocabulary) == Some(acr),
          found.flatMap(_.mtlsCertificateEncoding) == Some(MtlsCertificateEncoding.base64Der),
          found.map(_.passkeySettings.rpId) == Some("example.com"),
        )
      },
      // The reason `patch` locks the row: two admins patching different members at once. Without the lock both read the
      // row before either writes, and the second write puts back the member the first one changed.
      test("concurrent patches of different members both land") {
        val original = settingsFor(tenantA)
        val patches = List[ChallengeSettingsRecord => ChallengeSettingsRecord](
          _.copy(otpLength = 8),
          _.copy(ipHeader = "X-Real-IP"),
          _.copy(sessionTtlSeconds = 7200),
          _.copy(requireDpopNonce = true),
          _.copy(userAgentTtlSeconds = 1000),
          _.copy(otpResendAfter = 45),
        )
        for
          _ <- env.repository.upsert(original)
          _ <- ZIO.foreachParDiscard(patches)(change => env.repository.patch(tenantA, record => Right(change(record))))
          found <- env.repository.findByTenant(tenantA)
        yield assertTrue(
          found == Some(
            original.copy(
              otpLength = 8,
              ipHeader = "X-Real-IP",
              sessionTtlSeconds = 7200,
              requireDpopNonce = true,
              userAgentTtlSeconds = 1000,
              otpResendAfter = 45,
            ),
          ),
        )
      },
    )

object ChallengeSettingsRepositorySpec:
  case class Env(repository: ChallengeSettingsRepository)
