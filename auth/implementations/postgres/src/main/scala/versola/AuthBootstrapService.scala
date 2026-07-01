package versola

import versola.oauth.challenge.password.PasswordRepository
import versola.oauth.challenge.password.model.PasswordReuseError
import versola.oauth.client.model.TenantId
import versola.role.model.RoleId
import versola.user.model.{Login, UserId}
import versola.user.{UserRepository, UserRolesRepository}
import versola.util.{CoreConfig, Salt, Secret, SecureRandom, SecurityService}
import zio.{Task, ZIO, ZLayer}

import java.util.UUID

trait AuthBootstrapService:
  def bootstrap: Task[Unit]

object AuthBootstrapService:
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
            tenantId = TenantId.global,
            add = Set(RoleId("oauth-admin")),
            remove = Set.empty,
          )
          passwords <- passwordRepo.list(userId)
          _ <- ZIO.when(passwords.isEmpty):
            for
              salt <- secureRandom.nextBytes(16).map(Salt(_))
              hash <- securityService.hashPassword(Secret.fromString(cfg.password), salt, config.security.passwordsSecret)
              _ <- passwordRepo.create(userId, Secret(hash), salt, historySize = 1, numDifferent = 1)
                .mapError:
                  case e: PasswordReuseError => new IllegalStateException(s"Unexpected password reuse during bootstrap: $e")
                  case t: Throwable => t
              _ <- ZIO.logInfo(s"Set bootstrap password for admin user '${cfg.login}'")
            yield ()
        yield ()
