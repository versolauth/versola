package versola

import versola.antifraud.{AntifraudSettingsRepository, Tuning}
import versola.oauth.challenge.password.{OneTimeLink, PasswordRepository}
import versola.oauth.client.model.TenantId
import versola.oauth.revocation.{RevocationCause, RevocationTerms}
import versola.role.model.RoleId
import versola.user.UserRepository
import versola.user.model.{Login, UserId}
import versola.util.SequentialLayers.*
import versola.util.{CoreConfig, EnvName, Phone, Salt, Secret, SecureRandom, SecurityService}
import zio.{Clock, System, Task, UIO, ZIO, ZLayer}

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
    UserRepository & PasswordRepository & AntifraudSettingsRepository & SecurityService & SecureRandom & CoreConfig & EnvName,
    Throwable,
    AuthBootstrapService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _)) >++>
      ZLayer(ZIO.serviceWithZIO[AuthBootstrapService](_.bootstrap))

  private class Impl(
      userRepo: UserRepository,
      passwordRepo: PasswordRepository,
      antifraudSettings: AntifraudSettingsRepository,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      envName: EnvName,
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
          _ <- userRepo.updateRoles(
            userId = userId,
            tenantId = TenantId.default,
            add = Set(RoleId("oauth-admin")),
            remove = Set.empty,
            // Only adds a role, which revokes nothing: the terms are never written.
            revocation = RevocationTerms.widest(RevocationCause.RolesChanged, Map.empty),
          )
          // BOOTSTRAP_RESET=1 on a restart drops the still-valid temporary password and its link so that
          // new ones are issued below, instead of the admin waiting out the 24 hours. A permanent password
          // is never touched: the repository refuses, and the normal recovery flows apply.
          _ <- resetRequested.flatMap: requested =>
            ZIO.when(requested):
              passwordRepo.resetBootstrapCredential(userId).flatMap:
                case true => ZIO.logInfo(s"BOOTSTRAP_RESET: dropped the temporary credential of admin user '${cfg.login}'")
                case false => ZIO.logWarning(s"BOOTSTRAP_RESET ignored: admin user '${cfg.login}' already has a permanent password")
          now <- Clock.instant
          passwords <- passwordRepo.list(userId)
          // A usable credential is any permanent password or a still-valid temporary one.
          // An expired temporary password must not block recreation, otherwise the admin
          // stays locked out until cleanup removes the stale row.
          hasUsableCredential = passwords.exists: record =>
            record.expiresAt.forall(_.isAfter(now))
          // Cheap pre-check to skip the Argon2 hash on every restart; the repository checks again under
          // the per-user lock, which is what decides when replicas start together.
          _ <- ZIO.unless(hasUsableCredential):
            for
              salt <- secureRandom.nextBytes(16).map(Salt(_))
              hash <- securityService.hashPassword(Secret.fromString(cfg.password), salt, config.security.passwordsSecret)
              expiresAt = now.plusSeconds(BootstrapPasswordTtlSeconds)
              // `createBootstrapCredential` replaces the former `createTemporary(..., Untouched)`: a bootstrap
              // revokes nothing, and it also issues the one-time link under the same lock.
              // A blank `first-login-url` would print a link that cannot be opened: treat it as not configured.
              firstLoginUrl = cfg.firstLoginUrl.map(_.trim).filter(_.nonEmpty)
              _ <- ZIO.logWarning("bootstrap.first-login-url is blank: no first-login link is issued")
                .when(cfg.firstLoginUrl.exists(_.isBlank))
              link <- ZIO.foreach(firstLoginUrl)(url => OneTimeLink.generate(secureRandom).map(url -> _))
              created <- passwordRepo.createBootstrapCredential(
                userId = userId,
                passwordHash = Secret(hash),
                salt = salt,
                expiresAt = expiresAt,
                linkTokenHash = link.map((_, token) => OneTimeLink.hash(token)),
              )
              _ <- ZIO.when(created):
                ZIO.logInfo(s"Set temporary bootstrap password for admin user '${cfg.login}'") *>
                  // The only place the token appears: whoever deploys (versola-cli, #82) reads this line. It is a secret for up to
                  // 24 hours, single use, and bound to a password change; it is never written to a file. Logged at
                  // WARN, not INFO: only a hash is stored, so under LOG_LEVEL=warn an INFO line would be dropped and the
                  // link lost with nothing to say so. It is a one-off line, so WARN costs no noise.
                  ZIO.foreachDiscard(link): (url, token) =>
                    val separator = if url.contains("?") then "&" else "?"
                    ZIO.logWarning(s"first-login link for admin: $url$separator${OneTimeLink.ParamName}=$token")
            yield ()
        yield ()

    /** `1` or `true`. Any other non-empty value is reported rather than ignored in silence: an operator who believes a
      * leaked link was revoked must not be left with it working. */
    private val resetRequested: UIO[Boolean] =
      System.env("BOOTSTRAP_RESET").orElseSucceed(None).flatMap:
        case Some(value) if Set("1", "true").contains(value.trim.toLowerCase) => ZIO.succeed(true)
        case Some(value) if value.trim.nonEmpty =>
          ZIO.logWarning(s"BOOTSTRAP_RESET='$value' is not recognised (expected 1): nothing is reset").as(false)
        case _ => ZIO.succeed(false)
