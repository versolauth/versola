package versola.oauth.consent

import versola.oauth.authorize.model.Prompt
import versola.oauth.client.model.{ClientId, ConsentFlow, OAuthClientRecord, ScopeToken, TenantId}
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import versola.util.UnitSpecBase
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.time.Instant
import java.util.UUID

object ConsentServiceSpec extends UnitSpecBase:

  private val clientId = ClientId("test-client")
  private val userId = UserId(UUID.fromString("f077fb08-9935-4a6d-8643-bf97c073bf0f"))
  private val now = Instant.parse("2024-01-01T00:00:00Z")

  private val openid = ScopeToken.OpenId
  private val offline = ScopeToken.OfflineAccess
  private val profile = ScopeToken("profile")
  private val email = ScopeToken("email")

  private val baseClient = OAuthClientRecord(
    id = clientId,
    tenantId = TenantId("default"),
    clientName = Map("en" -> "Test Client"),
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(openid, profile, email),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    refreshTokenTtl = 7776000.seconds,
    theme = "default",
    authFlow = None,
    registrationFlow = None,
    otpTemplateId = "default",
    frontChannelLogoutUri = None,
    frontChannelLogoutSessionRequired = false,
    backChannelLogoutUri = None,
    logoUri = None,
    policyUri = None,
    tosUri = None,
    consentFlow = None,
  )

  private def clientWith(
      allowPartial: Boolean = false,
      rememberDuration: Option[Duration] = None,
  ) = baseClient.copy(consentFlow = Some(ConsentFlow(allowPartial, rememberDuration)))

  private def grant(scope: Set[ScopeToken], expiresAt: Option[Instant] = None) =
    ConsentRecord(userId, clientId, scope, grantedAt = now, expiresAt = expiresAt)

  final class Env:
    val consentRepository = stub[ConsentRepository]
    val service: ConsentService = ConsentService.Impl(consentRepository)

  /** `decide` reads the wall clock to expire stored grants, so tests pin it. */
  private val atNow = TestAspect.before(TestClock.setTime(now))

  val spec = suite("ConsentService")(
    suite("decide")(
      test("never prompt when the client has no consent flow") {
        val env = Env()
        for
          decision <- env.service.decide(userId, baseClient, Set(openid, profile), Set.empty)
          // The repository must not even be consulted: legacy clients have no grants on file.
          lookups = env.consentRepository.find.times
        yield assertTrue(
          decision == ConsentDecision.Satisfied(Set(openid, profile)),
          lookups == 0,
        )
      },
      test("prompt on the first authorization when a consent flow is configured") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(None)
          decision <- env.service.decide(userId, clientWith(), Set(openid, profile), Set.empty)
        yield assertTrue(decision == ConsentDecision.Required(Set(openid, profile)))
      },
      test("reuse a stored grant that covers the request") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid, profile))))
          decision <- env.service.decide(userId, clientWith(), Set(openid, profile), Set.empty)
        yield assertTrue(decision == ConsentDecision.Satisfied(Set(openid, profile)))
      },
      test("carry only the requested scope when the stored grant is wider") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid, profile, email))))
          decision <- env.service.decide(userId, clientWith(), Set(openid), Set.empty)
        yield assertTrue(decision == ConsentDecision.Satisfied(Set(openid)))
      },
      test("re-prompt when the request widens beyond the stored grant") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid))))
          decision <- env.service.decide(userId, clientWith(), Set(openid, profile), Set.empty)
        yield assertTrue(decision == ConsentDecision.Required(Set(openid, profile)))
      },
      test("re-prompt on prompt=consent even with a covering grant") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid, profile))))
          decision <- env.service.decide(userId, clientWith(), Set(openid, profile), Set(Prompt.consent))
          // Short-circuits before the lookup: the answer cannot depend on the stored grant.
          lookups = env.consentRepository.find.times
        yield assertTrue(
          decision == ConsentDecision.Required(Set(openid, profile)),
          lookups == 0,
        )
      },
      test("re-prompt when the stored grant has expired") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid), expiresAt = Some(now.minusSeconds(1)))))
          decision <- env.service.decide(userId, clientWith(), Set(openid), Set.empty)
        yield assertTrue(decision == ConsentDecision.Required(Set(openid)))
      },
      test("reuse a stored grant that has not expired yet") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid), expiresAt = Some(now.plusSeconds(60)))))
          decision <- env.service.decide(userId, clientWith(), Set(openid), Set.empty)
        yield assertTrue(decision == ConsentDecision.Satisfied(Set(openid)))
      },
      test("re-prompt when the grant expires exactly now") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(Some(grant(Set(openid), expiresAt = Some(now))))
          decision <- env.service.decide(userId, clientWith(), Set(openid), Set.empty)
        yield assertTrue(decision == ConsentDecision.Required(Set(openid)))
      },
      test("prompt=none does not by itself satisfy consent") {
        val env = Env()
        for
          _ <- env.consentRepository.find.succeedsWith(None)
          decision <- env.service.decide(userId, clientWith(), Set(openid), Set(Prompt.none))
        yield assertTrue(decision == ConsentDecision.Required(Set(openid)))
      },
    ) @@ atNow,
    suite("validateSubmission")(
      test("accept the full requested scope") {
        val env = Env()
        val result = env.service.validateSubmission(clientWith(), Set(openid, profile), Set(openid, profile))
        assertTrue(result == Right(Set(openid, profile)))
      },
      test("reject a subset when the client does not allow partial grants") {
        val env = Env()
        val result = env.service.validateSubmission(clientWith(), Set(openid, profile), Set(openid))
        assertTrue(result.isLeft)
      },
      test("accept a subset when the client allows partial grants") {
        val env = Env()
        val result = env.service.validateSubmission(
          clientWith(allowPartial = true),
          Set(openid, profile, email),
          Set(openid, profile),
        )
        assertTrue(result == Right(Set(openid, profile)))
      },
      test("reject dropping openid even with partial grants allowed") {
        val env = Env()
        val result = env.service.validateSubmission(
          clientWith(allowPartial = true),
          Set(openid, profile),
          Set(profile),
        )
        assertTrue(result.isLeft)
      },
      test("reject dropping offline_access even with partial grants allowed") {
        val env = Env()
        val result = env.service.validateSubmission(
          clientWith(allowPartial = true),
          Set(openid, offline, profile),
          Set(openid, profile),
        )
        assertTrue(result.isLeft)
      },
      test("reject a submission that exceeds the requested scope") {
        val env = Env()
        val result = env.service.validateSubmission(
          clientWith(allowPartial = true),
          Set(openid),
          Set(openid, profile),
        )
        assertTrue(result.isLeft)
      },
      test("accept an empty submission when nothing mandatory was requested") {
        val env = Env()
        val result = env.service.validateSubmission(clientWith(allowPartial = true), Set(profile), Set.empty)
        assertTrue(result == Right(Set.empty[ScopeToken]))
      },
    ),
    suite("grant")(
      test("store the grant without an expiry when the client remembers until revoked") {
        val env = Env()
        for
          _ <- env.consentRepository.upsert.succeedsWith(())
          _ <- env.service.grant(userId, clientWith(), Set(openid, profile))
        yield assertTrue(
          env.consentRepository.upsert.calls == List(
            ConsentRecord(userId, clientId, Set(openid, profile), grantedAt = now, expiresAt = None),
          ),
        )
      },
      test("expire the grant after the client's remember duration") {
        val env = Env()
        for
          _ <- env.consentRepository.upsert.succeedsWith(())
          _ <- env.service.grant(userId, clientWith(rememberDuration = Some(30.days)), Set(openid))
        yield assertTrue(
          env.consentRepository.upsert.calls == List(
            ConsentRecord(userId, clientId, Set(openid), grantedAt = now, expiresAt = Some(now.plusSeconds(30 * 86400))),
          ),
        )
      },
    ) @@ atNow,
    suite("revoke")(
      test("delete the grant for the user and client") {
        val env = Env()
        for
          _ <- env.consentRepository.delete.succeedsWith(())
          _ <- env.service.revoke(userId, baseClient)
        yield assertTrue(env.consentRepository.delete.calls == List((userId, clientId)))
      },
    ),
  )
