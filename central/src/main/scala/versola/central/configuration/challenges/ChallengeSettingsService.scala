package versola.central.configuration.challenges

import versola.central.CentralConfig
import versola.central.configuration.jwks.{JwksRepository, SigningKeyReferences}
import versola.central.configuration.roles.RoleRepository
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.TenantId
import versola.util.{JWT, ReloadingCache}
import zio.{Scope, Task, UIO, ZIO, ZLayer}

trait ChallengeSettingsService:
  /** A cache miss falls through to the repository rather than reading as "no settings for this
    * tenant": the cache is seeded once and refreshed on a fixed interval (minutes, in a real
    * deployment), so a tenant whose settings were written after that load -- bootstrap seeding
    * its own tenant at startup, a tenant created moments ago, a GET right after a PUT that
    * raced the next refresh -- would otherwise read as having none at all until the interval
    * next elapses. A cache *hit* is still served from the cache unconditionally: this closes
    * the false negative, not the staleness a hit can still carry (same tradeoff
    * [[getSecurityProfile]] and [[getMtlsCertificateHeader]] opt out of entirely, for the
    * narrower set of callers that cannot tolerate even that).
    */
  def getSettings(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]]
  def getAllSettings: Task[Vector[ChallengeSettingsRecord]]

  /** #353: the profile the tenant's clients are held to, read from the repository rather
    * than the cache -- a registration that follows a switch has to be held to the profile the
    * switch just stored, not to the one the cache last saw. A tenant with no settings row reads
    * as [[ChallengeSettingsRecord.DefaultSecurityProfile]], what it would be created with. */
  def getSecurityProfile(tenantId: TenantId): Task[SecurityProfile]

  /** The header an `mtlsAuth` registration is validated against, read from the repository
    * rather than the cache -- for the same reason as [[getSecurityProfile]]: bootstrap sets
    * this header and registers `central-admin` against it in the same process, and the
    * registration has to see what bootstrap just stored, not what the cache last saw. */
  def getMtlsCertificateHeader(tenantId: TenantId): Task[Option[String]]
  def upsertSettings(record: ChallengeSettingsRecord): Task[Unit]

  /** Merges `patch` into the tenant's stored settings and writes the result, under the same rules as
    * [[upsertSettings]]'s `PUT` (the record that would be stored is validated, its roles and signing key must exist).
    * The merge runs on the row locked in one transaction (see [[ChallengeSettingsRepository.patch]]), so concurrent
    * patches of different members all land.
    *
    * Fails with [[ChallengeSettingsService.SettingsNotFound]] when the tenant has no settings yet, with
    * [[ChallengeSettingsService.ValidationError]] when the result cannot be stored; nothing is written then.
    */
  def patchSettings(patch: PatchChallengeSettingsRequest): Task[Unit]
  def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit]

  /** Reloads the whole cache from the repository now -- for bootstrap, which writes the
    * default tenant's settings after this cache has loaded and before central listens for
    * the change notifications (see `OAuthClientService.refreshNow`). */
  def refreshNow: Task[Unit]

object ChallengeSettingsService:
  /** Rejected before the row is written, so a tenant cannot be left pointing at a key nothing
    * can sign with -- auth would silently fall back to its legacy key and issue tokens under
    * an algorithm the operator did not choose -- nor at one signing under an algorithm this
    * server will not issue tenant tokens with.
    */
  enum ValidationError(val message: String) extends RuntimeException(message):
    case UnknownSigningKey(kid: String)
      extends ValidationError(s"No JWKS key with kid '$kid'")
    case VerifyOnlySigningKey(kid: String)
      extends ValidationError(
        s"Key '$kid' has no private key in central, so nothing can sign with it. " +
          "It is published for verification only -- generate a key instead.",
      )
    case UnusableSigningKey(kid: String)
      extends ValidationError(
        s"Key '$kid' is published without a usable 'alg', so the algorithm to sign under is unknown.",
      )
    case Rs256SigningKey(kid: String)
      extends ValidationError(
        s"Key '$kid' signs RS256, whose PKCS#1 v1.5 padding FAPI 2.0 disallows, and the tenant is " +
          "on the fapi2 security profile. Select a PS256 or ES256 key instead.",
      )
    case Invalid(reason: String) extends ValidationError(reason)
    case UnknownDefaultRole(roleId: String, tenantId: String)
      extends ValidationError(s"defaultRoles names role '$roleId', which does not exist in tenant '$tenantId'")
    case SecurityProfileFixed(tenantId: String)
      extends ValidationError(
        s"tenant '$tenantId' already has settings under another securityProfile, " +
          "which cannot be changed after the tenant is created",
      )

  /** `PATCH` of a tenant that has no settings: there is nothing to merge into, and the first write is a `PUT`. */
  case class SettingsNotFound(tenantId: TenantId) extends RuntimeException(s"tenant '$tenantId' has no challenge settings")

  def live: ZLayer[
    ChallengeSettingsRepository & JwksRepository & RoleRepository & Scope & CentralConfig,
    Throwable,
    ChallengeSettingsService,
  ] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[ChallengeSettingsRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _, _))

  /** Backs [[SigningKeyReferences]] off the repository rather than this service, so the key
    * service can refuse to delete a key in use while this one validates kids against the
    * key store -- without the two services depending on each other.
    */
  def signingKeyReferences: ZLayer[ChallengeSettingsRepository, Nothing, SigningKeyReferences] =
    ZLayer.fromFunction { (repository: ChallengeSettingsRepository) =>
      new SigningKeyReferences:
        override def tenantsSigningWith(kid: String): UIO[Vector[String]] =
          repository.getAll
            .map(_.collect { case row if row.signingKeyId.contains(kid) => row.tenantId.toString })
            .orDie
    }

  class Impl(
      cache: ReloadingCache[Vector[ChallengeSettingsRecord]],
      repository: ChallengeSettingsRepository,
      jwksRepository: JwksRepository,
      roleRepository: RoleRepository,
  ) extends ChallengeSettingsService:

    override def getSettings(tenantId: TenantId): Task[Option[ChallengeSettingsRecord]] =
      cache.get.map(_.find(_.tenantId == tenantId)).flatMap:
        case found: Some[ChallengeSettingsRecord] => ZIO.succeed(found)
        case None => repository.findByTenant(tenantId)

    override def getAllSettings: Task[Vector[ChallengeSettingsRecord]] =
      cache.get

    override def getSecurityProfile(tenantId: TenantId): Task[SecurityProfile] =
      repository.findByTenant(tenantId)
        .map(_.fold(ChallengeSettingsRecord.DefaultSecurityProfile)(_.securityProfile))

    override def getMtlsCertificateHeader(tenantId: TenantId): Task[Option[String]] =
      repository.findByTenant(tenantId).map(_.flatMap(_.mtlsCertificateHeader))

    override def upsertSettings(record: ChallengeSettingsRecord): Task[Unit] =
      validateSigningKey(record.signingKeyId, record.securityProfile) *> repository.upsert(record).flatMap: written =>
        ZIO.fail(ValidationError.SecurityProfileFixed(record.tenantId.toString)).unless(written).unit

    override def patchSettings(patch: PatchChallengeSettingsRequest): Task[Unit] =
      for
        stored <- repository.findByTenant(patch.tenantId).someOrFail(SettingsNotFound(patch.tenantId))
        // What needs another table is checked on the record this patch would produce from the row as read now. A patch
        // landing in between could change `defaultRoles` or the signing key under it; the pure rules are enforced
        // again on the locked row below, and a role or key deleted that same instant is a race `PUT` has too.
        candidate <- ZIO.fromEither(patch.applyTo(stored)).mapError(ValidationError.Invalid(_))
        _ <- validateSigningKey(candidate.signingKeyId, candidate.securityProfile)
        _ <- validateDefaultRoles(candidate)
        patched <- repository.patch(patch.tenantId, patch.applyTo)
        _ <- patched match
          case None => ZIO.fail(SettingsNotFound(patch.tenantId))
          case Some(Left(reason)) => ZIO.fail(ValidationError.Invalid(reason))
          case Some(Right(_)) => ZIO.unit
      yield ()

    override def sync(event: SyncEvent.ChallengeSettingsUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        repository.findByTenant(event.tenantId),
      )

    override def refreshNow: Task[Unit] =
      repository.getAll.flatMap(cache.set(_))

    private def validateDefaultRoles(record: ChallengeSettingsRecord): Task[Unit] =
      val roles = if ChallengeSettingsRecord.registrationPossible(record) then record.defaultRoles else Set.empty
      ZIO.foreachDiscard(roles): roleId =>
        roleRepository.findRole(record.tenantId, roleId).flatMap:
          case Some(_) => ZIO.unit
          case None => ZIO.fail(ValidationError.UnknownDefaultRole(roleId.toString, record.tenantId.toString))

    /** Read through the repository, not the key service's cache: a key generated moments ago
      * must be selectable immediately, rather than after the next cache refresh.
      */
    private def validateSigningKey(signingKeyId: Option[String], profile: SecurityProfile): Task[Unit] =
      ZIO.foreachDiscard(signingKeyId): kid =>
        jwksRepository.find(kid).flatMap:
          case None => ZIO.fail(ValidationError.UnknownSigningKey(kid))
          case Some(key) if key.privateKey.isEmpty => ZIO.fail(ValidationError.VerifyOnlySigningKey(kid))
          case Some(key) if key.algorithm.isEmpty => ZIO.fail(ValidationError.UnusableSigningKey(kid))
          // FAPI 2.0 disallows RS256's PKCS#1 v1.5 padding, so a `fapi2` tenant may not sign
          // under it. A `standard` tenant may: OpenID Connect Core makes RS256 the one
          // algorithm every OP must support, and the OpenID Foundation's conformance suite (the
          // basis of OP certification) fails an OP that cannot sign with it. The profile is
          // fixed when the tenant is created, so this cannot be sidestepped by editing it later.
          case Some(key) if key.algorithm.contains(JWT.Algorithm.RS256) && profile == SecurityProfile.fapi2 =>
            ZIO.fail(ValidationError.Rs256SigningKey(kid))
          case Some(_) => ZIO.unit
