package versola

import versola.audit.AuditSupport.*
import versola.audit.{AuthAuditDetails, AuthAuditKinds}
import versola.util.SequentialLayers.*
import versola.oauth.challenge.password.PasswordRepository
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.antifraud.{AntifraudSettingsRepository, Tuning}
import versola.user.UserRepository
import versola.user.model.{Login, UserId}
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.{CoreConfig, EnvName, Phone, Salt, Secret, SecureRandom, SecurityService}
import zio.{Clock, Task, ZIO, ZLayer}

import java.util.UUID

trait AuthBootstrapService:
  def bootstrap: Task[Unit]

object AuthBootstrapService:
  /** TTL for the temporary bootstrap password. The admin must exchange it for a
    * permanent one on first login (via the set-password step). If it expires before
    * first login, a subsequent bootstrap run re-creates it.
    */
  private val BootstrapPasswordTtlSeconds = 24L * 60 * 60
  private val NonProdAdminPhone = Phone("+12025551234")

  private[versola] def adminPhone(envName: EnvName): Option[Phone] =
    Option.when(envName.isTest)(NonProdAdminPhone)

  val live: ZLayer[
    UserRepository & PasswordRepository & AntifraudSettingsRepository & SecurityService & SecureRandom & CoreConfig & EnvName &
      AuditEvents,
    Throwable,
    AuthBootstrapService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _)) >++>
      ZLayer(ZIO.serviceWithZIO[AuthBootstrapService](_.bootstrap))

  private class Impl(
      userRepo: UserRepository,
      passwordRepo: PasswordRepository,
      antifraudSettings: AntifraudSettingsRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      envName: EnvName,
      audit: AuditEvents,
  ) extends AuthBootstrapService:

    def bootstrap: Task[Unit] =
      seedAntifraudSettings *> bootstrapAdmin

    /** The first row of the antifraud settings, everything off, unless an operator has saved one already. */
    private val seedAntifraudSettings: Task[Unit] =
      Clock.instant.flatMap(antifraudSettings.seed(Tuning.off, "bootstrap", _)).unit

    private def bootstrapAdmin: Task[Unit] =
      ZIO.foreachDiscard(config.bootstrap): cfg =>
        for
          _ <- ZIO.logInfo(s"Bootstrapping admin user '${cfg.login}'...")
          existing <- userRepo.findByLogin(Login(cfg.login))
          userId <- existing match
            case Some(record) =>
              ZIO.when(record.phone.isEmpty && adminPhone(envName).isDefined):
                secureRandom.nextUUIDv7.flatMap: version =>
                  userRepo.upsert(record.id, version, record.email, adminPhone(envName), record.login)
              *> ZIO.logInfo(s"Admin user '${cfg.login}' already exists, skipping creation") *>
                ZIO.succeed(record.id)
            case None =>
              val adminUserId = UserId(cfg.adminUserId)
              for
                version <- secureRandom.nextUUIDv7
                _ <- userRepo.upsert(adminUserId, version, email = None, phone = adminPhone(envName), login = Some(Login(cfg.login)))
                _ <- ZIO.logInfo(s"Created admin user '${cfg.login}' with id $adminUserId")
              yield adminUserId
          // The service's own change at start-up, recorded like any other: the actor is the system, and a start that finds the role
          // already granted writes no row.
          _ <- audit.atomically(
            AuditDraft(
              kind = AuthAuditKinds.AccountRolesUpdated,
              outcome = AuditOutcome.success,
              userId = Some(userId),
              target = Some(("user", userId.toString)),
              details = AuthAuditDetails.RolesUpdated(tenantId = TenantId.default.toString, added = List("oauth-admin"), removed = Nil),
            ),
          )(event =>
            userRepo.updateRoles(
              userId = userId,
              tenantId = TenantId.default,
              add = Set(RoleId("oauth-admin")),
              remove = Set.empty,
              audit = event,
            ),
          )
          now <- Clock.instant
          passwords <- passwordRepo.list(userId)
          // A usable credential is any permanent password or a still-valid temporary one.
          // An expired temporary password must not block recreation, otherwise the admin
          // stays locked out until cleanup removes the stale row.
          hasUsableCredential = passwords.exists: record =>
            record.expiresAt.forall(_.isAfter(now))
          _ <- ZIO.unless(hasUsableCredential):
            for
              salt <- secureRandom.nextBytes(16).map(Salt(_))
              hash <- securityService.hashPassword(Secret.fromString(cfg.password), salt, config.security.passwordsSecret)
              expiresAt = now.plusSeconds(BootstrapPasswordTtlSeconds)
              _ <- audit.atomically(
                AuditDraft(
                  kind = AuthAuditKinds.AccountPasswordSet,
                  outcome = AuditOutcome.success,
                  userId = Some(userId),
                  target = Some(("user", userId.toString)),
                  details = AuthAuditDetails.Account(via = "bootstrap"),
                ),
              )(passwordRepo.createTemporary(userId, Secret(hash), salt, expiresAt, _))
              _ <- ZIO.logInfo(s"Set temporary bootstrap password for admin user '${cfg.login}'")
            yield ()
        yield ()
