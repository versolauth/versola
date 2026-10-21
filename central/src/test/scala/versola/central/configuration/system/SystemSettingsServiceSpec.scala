package versola.central.configuration.system

import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

object SystemSettingsServiceSpec extends UnitSpecBase:

  private val baseSettings = SystemSettingsRecord.default

  class Env:
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(baseSettings)))
    val repository = stub[SystemSettingsRepository]
    val service = SystemSettingsService.Impl(cache, repository)

  def spec = suite("SystemSettingsService")(
    test("upsertSettings stores the record as it is") {
      val env = Env()
      val settings = baseSettings.copy(passwordHistorySize = 5)
      for
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.upsertSettings(settings)
      yield assertTrue(env.repository.upsert.calls == List(settings))
    },
  )
