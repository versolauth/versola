package versola.central.configuration.challenges

import versola.central.configuration.jwks.{JwksRecord, JwksRepository}
import versola.central.configuration.roles.{RoleRecord, RoleRepository}
import versola.central.configuration.sync.SyncEvent
import versola.central.configuration.tenants.TenantId
import versola.util.{Patch, ReloadingCache, Secret, UnitSpecBase}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ChallengeSettingsServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("tenant-a")
  private val otherTenantId = TenantId("tenant-b")
  private val settings = ChallengeSettingsRecord(
    tenantId = tenantId,
    phoneRegions = List.empty,
    emailRegistration = EmailRegistration.Closed,
    defaultRoles = Set(ChallengeSettingsRecord.DefaultRoleId),
    submissionLimits = SubmissionLimits.empty,
    otpLength = 6,
    otpResendAfter = 60,
    passkeySettings = PasskeySettings("localhost", "Test", List("http://localhost"), "preferred"),
    authConversationTtlSeconds = 900,
    sessionTtlSeconds = 86400,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = 15552000,
    ipHeader = "X-Real-IP",
    acrVocabulary = None,
    postLogoutRedirectUris = List.empty,
    requireDpopNonce = false,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = 300,
    securityProfile = SecurityProfile.fapi2,
    logoLight = None,
    logoDark = None,
  )

  private val noChange = PatchChallengeSettingsRequest(
    tenantId = tenantId,
    phoneRegions = None,
    emailRegistration = None,
    defaultRoles = None,
    submissionLimits = None,
    otpLength = None,
    otpResendAfter = None,
    passkeySettings = None,
    authConversationTtlSeconds = None,
    sessionTtlSeconds = None,
    sessionIdleTtlSeconds = None,
    userAgentTtlSeconds = None,
    ipHeader = None,
    acrVocabulary = None,
    postLogoutRedirectUris = None,
    requireDpopNonce = None,
    mtlsCertificateHeader = None,
    mtlsCertificateEncoding = None,
    signingKeyId = None,
    clientAssertionMaxLifetimeSeconds = None,
    securityProfile = None,
    logoLight = None,
    logoDark = None,
  )

  private val userRole = RoleRecord(ChallengeSettingsRecord.DefaultRoleId, tenantId, Map("en" -> "User"), Set.empty, active = true)
  private val open = EmailRegistration(EmailRegistrationMode.open, Nil, blockSubaddress = false)

  /** A key as central stores one it generated: published with an `alg`, private half kept. */
  private def signableKey(kid: String, alg: String) = JwksRecord(
    kid = kid,
    jwk = Json.Obj("kid" -> Json.Str(kid), "kty" -> Json.Str("RSA"), "alg" -> Json.Str(alg)),
    privateKey = Some(Secret.fromString("encrypted-pkcs8")),
  )

  class Env(initial: Vector[ChallengeSettingsRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ChallengeSettingsRepository]
    val jwksRepository = stub[JwksRepository]
    val roleRepository = stub[RoleRepository]
    val service = ChallengeSettingsService.Impl(cache, repository, jwksRepository, roleRepository)

  def spec = suite("ChallengeSettingsService")(
    test("getSettings returns None when cache is empty and the repository has no row either") {
      val env = Env()
      for
        _ <- env.repository.findByTenant.succeedsWith(None)
        result <- env.service.getSettings(tenantId)
      yield assertTrue(result.isEmpty)
    },
    test("getSettings returns matching settings for tenant straight from the cache, without consulting the repository") {
      val env = Env(Vector(settings))
      for
        result <- env.service.getSettings(tenantId)
        lookups = env.repository.findByTenant.times
      yield assertTrue(result.contains(settings), lookups == 0)
    },
    test("getSettings returns None for an unknown tenant the repository holds no row for either") {
      val env = Env(Vector(settings))
      for
        _ <- env.repository.findByTenant.succeedsWith(None)
        result <- env.service.getSettings(otherTenantId)
      yield assertTrue(result.isEmpty)
    },
    // A tenant the cache missed -- seeded after the cache's last load, or simply not yet
    // picked up by its next one -- must not read as having no settings at all: bootstrap
    // seeds the default tenant's settings and registers a client against them moments later,
    // in the same process, well inside the cache's multi-minute refresh interval in a real
    // deployment.
    test("getSettings falls through to the repository on a cache miss") {
      val env = Env() // cache empty -- as it is before its first scheduled refresh
      for
        _ <- env.repository.findByTenant.succeedsWith(Some(settings))
        result <- env.service.getSettings(tenantId)
        calls = env.repository.findByTenant.calls
      yield assertTrue(result.contains(settings), calls == List(tenantId))
    },
    // #421/#428: bootstrap sets the tenant's mtlsCertificateHeader (through the repository)
    // and registers central-admin against it (through OAuthClientService, which reads this
    // method) in the same process, with no cache refresh in between. A read off the cache --
    // like getSettings above -- would still see no header and refuse that registration.
    suite("getMtlsCertificateHeader")(
      test("reads through the repository, not the stale cache") {
        val env = Env(Vector(settings)) // cache seeded with no header at all
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings.copy(mtlsCertificateHeader = Some("ssl-client-cert"))))
          result <- env.service.getMtlsCertificateHeader(tenantId)
        yield assertTrue(result.contains("ssl-client-cert"))
      },
      test("is None for a tenant the repository has no row for") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(None)
          result <- env.service.getMtlsCertificateHeader(tenantId)
        yield assertTrue(result.isEmpty)
      },
    ),
    test("upsertSettings delegates to repository") {
      val env = Env()
      for
        _ <- env.repository.upsert.succeedsWith(true)
        _ <- env.service.upsertSettings(settings)
      yield assertTrue(env.repository.upsert.calls == List(settings))
    },
    test("upsertSettings refuses when the repository kept a tenant's other stored profile") {
      val env = Env()
      for
        _ <- env.repository.upsert.succeedsWith(false)
        result <- env.service.upsertSettings(settings).either
      yield assertTrue(
        result == Left(ChallengeSettingsService.ValidationError.SecurityProfileFixed(settings.tenantId.toString)),
      )
    },
    // A tenant pointing at a key that cannot sign would not fail at write time and then not
    // fail at sign time either: auth would quietly fall back to its legacy configured key,
    // issuing tokens under an algorithm nobody selected. So the write is what has to fail.
    suite("signing key validation")(
      test("accepts a key central holds a private half and an alg for") {
        val env = Env()
        val selected = settings.copy(signingKeyId = Some("ps-kid"))
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(signableKey("ps-kid", "PS256")))
          _ <- env.repository.upsert.succeedsWith(true)
          _ <- env.service.upsertSettings(selected)
        yield assertTrue(env.repository.upsert.calls == List(selected))
      },
      test("rejects a kid that does not exist, without writing") {
        val env = Env()
        for
          _ <- env.jwksRepository.find.succeedsWith(None)
          _ <- env.repository.upsert.succeedsWith(true)
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("missing"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("rejects a verify-only kid, without writing") {
        val env = Env()
        val verifyOnly = signableKey("bootstrap-kid", "PS256").copy(privateKey = None)
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(verifyOnly))
          _ <- env.repository.upsert.succeedsWith(true)
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("bootstrap-kid"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("rejects a kid published without a usable alg, without writing") {
        val env = Env()
        val noAlg = JwksRecord(
          kid = "no-alg",
          jwk = Json.Obj("kid" -> Json.Str("no-alg"), "kty" -> Json.Str("RSA")),
          privateKey = Some(Secret.fromString("encrypted-pkcs8")),
        )
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(noAlg))
          _ <- env.repository.upsert.succeedsWith(true)
          result <- env.service.upsertSettings(settings.copy(signingKeyId = Some("no-alg"))).exit
        yield assertTrue(
          result.isFailure,
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("rejects an RS256 key for a fapi2 tenant, without writing") {
        val env = Env()
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(signableKey("rs-kid", "RS256")))
          _ <- env.repository.upsert.succeedsWith(true)
          result <- env.service.upsertSettings(
            settings.copy(signingKeyId = Some("rs-kid"), securityProfile = SecurityProfile.fapi2),
          ).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.Rs256SigningKey("rs-kid")),
          env.repository.upsert.calls.isEmpty,
        )
      },
      // OIDC Core makes RS256 the one algorithm every OP must support, so a `standard` tenant
      // has to be able to sign under it.
      test("accepts an RS256 key for a standard tenant") {
        val env = Env()
        val standard = settings.copy(signingKeyId = Some("rs-kid"), securityProfile = SecurityProfile.standard)
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(signableKey("rs-kid", "RS256")))
          _ <- env.repository.upsert.succeedsWith(true)
          result <- env.service.upsertSettings(standard).exit
        yield assertTrue(
          result.isSuccess,
          env.repository.upsert.calls == List(standard),
        )
      },
      test("still rejects a verify-only RS256 key for a standard tenant") {
        val env = Env()
        val verifyOnly = signableKey("rs-kid", "RS256").copy(privateKey = None)
        for
          _ <- env.jwksRepository.find.succeedsWith(Some(verifyOnly))
          result <- env.service.upsertSettings(
            settings.copy(signingKeyId = Some("rs-kid"), securityProfile = SecurityProfile.standard),
          ).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.VerifyOnlySigningKey("rs-kid")),
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("a cleared selection is not validated -- it is the legacy fallback, not a key") {
        val env = Env()
        for
          _ <- env.repository.upsert.succeedsWith(true)
          _ <- env.service.upsertSettings(settings.copy(signingKeyId = None))
        yield assertTrue(env.jwksRepository.find.calls.isEmpty)
      },
    ),
    suite("patchSettings")(
      test("writes the merge of the patch into the stored row, through the repository's locked transaction") {
        val env = Env()
        val patch = noChange.copy(otpLength = Some(Patch.Modified(8)))
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.Patched(settings.copy(otpLength = 8)))
          _ <- env.service.patchSettings(patch)
        yield assertTrue(
          // the repository gets the patch itself, to merge into the row it has locked, not a snapshot of the row read here
          env.repository.patch.calls == List((tenantId, patch)),
          env.repository.upsert.calls.isEmpty,
        )
      },
      test("fails with SettingsNotFound for a tenant with no settings, without writing") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(None)
          result <- env.service.patchSettings(noChange).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.SettingsNotFound(tenantId)),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("fails with SettingsNotFound when the row is gone by the time it is locked") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.NotFound)
          result <- env.service.patchSettings(noChange).either
        yield assertTrue(result == Left(ChallengeSettingsService.SettingsNotFound(tenantId)))
      },
      test("refuses a patch whose resulting record is invalid, without writing") {
        val env = Env()
        val patch = noChange.copy(clientAssertionMaxLifetimeSeconds = Some(Patch.Modified(5)))
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          result <- env.service.patchSettings(patch).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.Invalid("clientAssertionMaxLifetimeSeconds must be between 30 and 900")),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("refuses clearing a member that is not optional, without writing") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          result <- env.service.patchSettings(noChange.copy(ipHeader = Some(Patch.Deleted))).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.Invalid("ipHeader cannot be cleared: it is not optional")),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("refuses a security profile other than the stored one, without writing") {
        val env = Env()
        val patch = noChange.copy(securityProfile = Some(Patch.Modified(SecurityProfile.standard)))
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          result <- env.service.patchSettings(patch).either
        yield assertTrue(result.isLeft, env.repository.patch.calls.isEmpty)
      },
      test("reports the rule the locked row breaks, when the repository's pure check refuses") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.Invalid("logoLight and logoDark must be set or cleared together"))
          result <- env.service.patchSettings(noChange).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.Invalid("logoLight and logoDark must be set or cleared together")),
        )
      },
      test("refuses a signing key nothing can sign with, without writing") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.jwksRepository.find.succeedsWith(None)
          result <- env.service.patchSettings(noChange.copy(signingKeyId = Some(Patch.Modified("missing")))).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.UnknownSigningKey("missing")),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("refuses an RS256 key for a fapi2 tenant, without writing") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.jwksRepository.find.succeedsWith(Some(signableKey("rs-kid", "RS256")))
          result <- env.service.patchSettings(noChange.copy(signingKeyId = Some(Patch.Modified("rs-kid")))).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.Rs256SigningKey("rs-kid")),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("clearing the signing key validates no key") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings.copy(signingKeyId = Some("ps-kid"))))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.Patched(settings))
          _ <- env.service.patchSettings(noChange.copy(signingKeyId = Some(Patch.Deleted)))
        yield assertTrue(env.jwksRepository.find.calls.isEmpty, env.repository.patch.calls.size == 1)
      },
      test("refuses default roles that do not exist once some credential can register, without writing") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.roleRepository.findRole.succeedsWith(None)
          result <- env.service.patchSettings(noChange.copy(emailRegistration = Some(Patch.Modified(open)))).either
        yield assertTrue(
          result == Left(ChallengeSettingsService.ValidationError.UnknownDefaultRole("user", tenantId.toString)),
          env.repository.patch.calls.isEmpty,
        )
      },
      test("looks the default roles up when registration is possible and they exist") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.roleRepository.findRole.succeedsWith(Some(userRole))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.Patched(settings.copy(emailRegistration = open)))
          _ <- env.service.patchSettings(noChange.copy(emailRegistration = Some(Patch.Modified(open))))
        yield assertTrue(env.roleRepository.findRole.calls == List((tenantId, ChallengeSettingsRecord.DefaultRoleId)))
      },
      test("looks no role up while nobody can register") {
        val env = Env()
        for
          _ <- env.repository.findByTenant.succeedsWith(Some(settings))
          _ <- env.repository.patch.succeedsWith(ChallengeSettingsPatchResult.Patched(settings.copy(otpLength = 8)))
          _ <- env.service.patchSettings(noChange.copy(otpLength = Some(Patch.Modified(8))))
        yield assertTrue(env.roleRepository.findRole.calls.isEmpty)
      },
    ),
    test("refreshNow replaces the cache with the repository's settings") {
      val env = Env(Vector.empty) // loaded before bootstrap wrote the default tenant's row
      for
        _ <- env.repository.getAll.succeedsWith(Vector(settings))
        _ <- env.service.refreshNow
        cached <- env.cache.get
      yield assertTrue(cached == Vector(settings))
    },
    test("sync removes settings on delete event") {
      val env = Env(Vector(settings))
      val event = SyncEvent.ChallengeSettingsUpdated(tenantId, SyncEvent.Op.DELETE)
      for
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached.isEmpty)
    },
    test("sync upserts fetched settings on non-delete event") {
      val env = Env(Vector.empty)
      val event = SyncEvent.ChallengeSettingsUpdated(tenantId, SyncEvent.Op.UPDATE)
      for
        _ <- env.repository.findByTenant.succeedsWith(Some(settings))
        _ <- env.service.sync(event)
        cached <- env.cache.get
      yield assertTrue(cached == Vector(settings))
    },
  )
