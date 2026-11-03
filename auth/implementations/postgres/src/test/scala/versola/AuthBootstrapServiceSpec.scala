package versola

import versola.antifraud.AntifraudSettingsRepository
import versola.auth.TestEnvConfig
import versola.oauth.challenge.password.{FirstLoginToken, PasswordRepository}
import versola.user.UserRepository
import versola.user.model.UserRecord
import versola.util.{CoreConfig, EnvName, MAC, Phone, SecureRandom, SecurityService, UnitSpecBase}
import zio.*
import zio.test.*

import java.util.{Arrays, UUID}

object AuthBootstrapServiceSpec extends UnitSpecBase:

  private val adminId = UUID.fromString("00000000-0000-7000-8000-000000000001")
  private val linkBase = "https://versola.example.com/login/central-admin"

  private val linkPrefix = s"first-login link for admin: $linkBase?${FirstLoginToken.ParamName}="

  /** The stubs of one bootstrap run. `credentialCreated` is what the repository answers: `true` for the
    * replica that won the per-user lock, `false` for one that found a usable credential already there.
    */
  private class Env(firstLoginUrl: Option[String], credentialCreated: Boolean, envName: EnvName = EnvName.Prod):
    val userRepository = stub[UserRepository]
    val passwordRepository = stub[PasswordRepository]
    val antifraudSettings = stub[AntifraudSettingsRepository]
    val securityService = stub[SecurityService]
    val secureRandom = stub[SecureRandom]
    val config: CoreConfig = TestEnvConfig.coreConfig.copy(
      bootstrap =
        Some(CoreConfig.BootstrapConfig(login = "admin", password = "bootstrap-secret", adminUserId = adminId, firstLoginUrl = firstLoginUrl)),
    )

    def run: ZIO[Any, Throwable, Unit] =
      for
        _ <- antifraudSettings.seed.succeedsWith(true)
        _ <- userRepository.findByLogin.succeedsWith(None)
        _ <- userRepository.upsert.succeedsWith(())
        _ <- userRepository.updateRoles.succeedsWith(())
        _ <- passwordRepository.list.succeedsWith(Vector.empty)
        _ <- passwordRepository.createBootstrapCredential.succeedsWith(credentialCreated)
        _ <- passwordRepository.resetBootstrapCredential.succeedsWith(true)
        _ <- securityService.hashPassword.succeedsWith(MAC(Array.fill(32)(9.toByte)))
        _ <- secureRandom.nextBytes.succeedsWith(Array.fill(32)(7.toByte))
        _ <- secureRandom.nextUUIDv7.succeedsWith(UUID.randomUUID())
        _ <- ZIO
          .scoped(AuthBootstrapService.live.build)
          .provide(
            ZLayer.succeed[UserRepository](userRepository),
            ZLayer.succeed[PasswordRepository](passwordRepository),
            ZLayer.succeed[AntifraudSettingsRepository](antifraudSettings),
            ZLayer.succeed[SecurityService](securityService),
            ZLayer.succeed[SecureRandom](secureRandom),
            ZLayer.succeed(config),
            ZLayer.succeed(envName),
          )
          .unit
      yield ()

  private def logged =
    ZTestLogger.logOutput.map(_.map(_.message()))

  def spec = suite("AuthBootstrapService")(
    test("uses the test admin phone outside production") {
      assertTrue(
        AuthBootstrapService.adminPhone(EnvName.Test("docker-local")) == Some(Phone("+12025551234")),
        AuthBootstrapService.adminPhone(EnvName.Prod).isEmpty,
      )
    },
    suite("first-login link (issue #824)")(
      test("the replica that creates the credential logs the link exactly once and stores only the token's hash") {
        val env = Env(firstLoginUrl = Some(linkBase), credentialCreated = true)
        for
          _ <- env.run
          lines <- logged
          linkLines = lines.filter(_.startsWith("first-login link for admin:"))
          token = linkLines.headOption.map(_.stripPrefix(linkPrefix))
          storedHash = env.passwordRepository.createBootstrapCredential.calls.head._5
        yield assertTrue(
          linkLines.size == 1,
          linkLines.head.startsWith(linkPrefix),
          token.exists(_.length >= 43), // 32 random bytes in base64url: 256 bits
          storedHash.exists(hash => token.exists(t => Arrays.equals(hash, FirstLoginToken.hash(t)))),
          // nothing else that is logged contains the token
          lines.filterNot(_.startsWith("first-login link for admin:")).forall(line => token.forall(t => !line.contains(t))),
        )
      },
      test("a replica that finds a usable credential already there logs no link") {
        val env = Env(firstLoginUrl = Some(linkBase), credentialCreated = false)
        for
          _ <- env.run
          lines <- logged
        yield assertTrue(!lines.exists(_.contains("first-login link")), !lines.exists(_.contains("temporary bootstrap password")))
      },
      test("without a first-login URL no token is issued and no link is logged: the temporary password stays the way in") {
        val env = Env(firstLoginUrl = None, credentialCreated = true)
        for
          _ <- env.run
          lines <- logged
          storedHash = env.passwordRepository.createBootstrapCredential.calls.head._5
        yield assertTrue(storedHash.isEmpty, !lines.exists(_.contains("first-login link")))
      },
      test("the temporary password itself is never logged") {
        val env = Env(firstLoginUrl = Some(linkBase), credentialCreated = true)
        for
          _ <- env.run
          lines <- logged
        yield assertTrue(!lines.exists(_.contains("bootstrap-secret")))
      },
      test("BOOTSTRAP_RESET=1 drops the unused temporary credential before a new one is issued") {
        val env = Env(firstLoginUrl = Some(linkBase), credentialCreated = true)
        for
          _ <- TestSystem.putEnv("BOOTSTRAP_RESET", "1")
          _ <- env.run
          resets = env.passwordRepository.resetBootstrapCredential.times
        yield assertTrue(resets == 1)
      },
      test("without BOOTSTRAP_RESET nothing is reset") {
        val env = Env(firstLoginUrl = Some(linkBase), credentialCreated = true)
        for
          _ <- env.run
          resets = env.passwordRepository.resetBootstrapCredential.times
        yield assertTrue(resets == 0)
      },
      // The log output is read from the test logger, which these specs would otherwise share while they run in parallel.
    ) @@ TestAspect.sequential,
  )
