package versola

import versola.oauth.challenge.password.PasswordRepository
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.{Login, UserId}
import versola.user.{UserRepository, UserRolesRepository}
import versola.util.{CoreConfig, Salt, Secret, SecureRandom, SecurityService}
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

  val live: ZLayer[
    UserRepository & UserRolesRepository & PasswordRepository & SecurityService & SecureRandom & CoreConfig,
    Throwable,
    AuthBootstrapService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _)) >+>
      ZLayer(ZIO.serviceWithZIO[AuthBootstrapService](_.bootstrap))

  private class Impl(
      userRepo: UserRepository,
      userRolesRepo: UserRolesRepository,
      passwordRepo: PasswordRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
  ) extends AuthBootstrapService:

    def bootstrap: Task[Unit] =
      ZIO.foreachDiscard(config.bootstrap): cfg =>
        for
          _ <- ZIO.logInfo(s"Bootstrapping admin user '${cfg.login}'...")
          existing <- userRepo.findByLogin(Login(cfg.login))
          userId <- existing match
            case Some(record) =>
              ZIO.logInfo(s"Admin user '${cfg.login}' already exists, skipping creation") *>
                ZIO.succeed(record.id)
            case None =>
              val adminUserId = UserId(cfg.adminUserId)
              for
                version <- secureRandom.nextUUIDv7
                _ <- userRepo.upsert(adminUserId, version, email = None, phone = None, login = Some(Login(cfg.login)))
                _ <- ZIO.logInfo(s"Created admin user '${cfg.login}' with id $adminUserId")
              yield adminUserId
          _ <- userRolesRepo.updateRoles(
            userId = userId,
            tenantId = TenantId.default,
            add = Set(RoleId("oauth-admin")),
            remove = Set.empty,
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
              _ <- passwordRepo.createTemporary(userId, Secret(hash), salt, expiresAt)
              _ <- ZIO.logInfo(s"Set temporary bootstrap password for admin user '${cfg.login}'")
            yield ()
        yield ()
