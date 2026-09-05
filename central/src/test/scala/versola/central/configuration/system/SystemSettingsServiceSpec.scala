package versola.central.configuration.system

import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

object SystemSettingsServiceSpec extends UnitSpecBase:

  private val baseSettings = SystemSettingsRecord.default

  class Env:
    val cache      = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(baseSettings)))
    val repository = stub[SystemSettingsRepository]
    val service    = SystemSettingsService.Impl(cache, repository)

  def spec = suite("SystemSettingsService")(
    test("accepts and trims absolute HTTP(S) logo URLs") {
      val env = Env()
      val expected = baseSettings.copy(identityProviderLogo = Some("https://assets.example/logo.svg"))
      for
        _      <- env.repository.upsert.succeedsWith(())
        result <- env.service.upsertSettings(expected.copy(identityProviderLogo = Some("  https://assets.example/logo.svg  ")))
      yield assertTrue(
        result == Right(()),
        env.repository.upsert.calls == List(expected),
      )
    },
    test("normalizes a blank logo URL to no configured logo") {
      val env = Env()
      val expected = baseSettings.copy(identityProviderLogo = None)
      for
        _      <- env.repository.upsert.succeedsWith(())
        result <- env.service.upsertSettings(baseSettings.copy(identityProviderLogo = Some("  ")))
      yield assertTrue(
        result == Right(()),
        env.repository.upsert.calls == List(expected),
      )
    },
    test("rejects malformed, relative, and non-HTTP logo URLs") {
      val env = Env()
      val invalid = List(
        "/logo.svg",
        "javascript:alert(1)",
        "https:logo.svg",
        "https://example.com:99999/logo.svg",
        "https://example.com/<script>",
      )
      for results <- ZIO.foreach(invalid)(url => env.service.upsertSettings(baseSettings.copy(identityProviderLogo = Some(url))))
      yield assertTrue(
        results.forall(_ == Left(SystemSettingsValidationError.InvalidIdentityProviderLogo)),
        env.repository.upsert.calls.isEmpty,
      )
    },
  )