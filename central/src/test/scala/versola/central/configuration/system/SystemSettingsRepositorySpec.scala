package versola.central.configuration.system

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.test.*

/** Conformance suite for any [[SystemSettingsRepository]] implementation. A backend module
  * extends this and supplies the wiring -- see `PostgresSystemSettingsRepositorySpec` in
  * `central-postgres-impl` for the one binding that exists today.
  */
trait SystemSettingsRepositorySpec extends DatabaseSpecBase[SystemSettingsRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val record = SystemSettingsRecord(
    passwordRegex = "^.{8,}$",
    passwordHistorySize = 3,
    passwordNumDifferent = 1,
    identityProviderLogo = Some("https://idp.example/logo.png"),
  )

  override def testCases(env: SystemSettingsRepositorySpec.Env) =
    List(
      test("getAll throws when no settings row has been stored") {
        for exit <- env.repository.getAll.exit
        yield assertTrue(exit.isFailure)
      },
      test("upsert then getAll returns the stored settings") {
        for
          _ <- env.repository.upsert(record)
          result <- env.repository.getAll
        yield assertTrue(result == record)
      },
      test("upsert overwrites the previously stored settings") {
        val updated = record.copy(passwordHistorySize = 5, identityProviderLogo = None)
        for
          _ <- env.repository.upsert(record)
          _ <- env.repository.upsert(updated)
          result <- env.repository.getAll
        yield assertTrue(result == updated)
      },
    )

object SystemSettingsRepositorySpec:
  case class Env(repository: SystemSettingsRepository)
