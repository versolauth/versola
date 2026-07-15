package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

object ThemeServiceSpec extends UnitSpecBase:

  private val tenantId  = TenantId("t1")
  private val themeId   = "theme-1"
  private val globalTheme = ThemeRecord(themeId, "body { color: red; }", None)
  private val tenantTheme = ThemeRecord("tenant-theme", "body { color: blue; }", Some(tenantId))

  class Env(initial: Vector[ThemeRecord] = Vector.empty):
    val cache      = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ThemeRepository]
    val service    = ThemeService.Impl(cache, repository)

  def spec = suite("ThemeService")(
    test("getAllThemes returns all themes sorted by id") {
      val env = Env(Vector(tenantTheme, globalTheme))
      for result <- env.service.getAllThemes
      yield assertTrue(result == Vector(tenantTheme, globalTheme))
    },
    test("getThemes returns global and tenant-specific themes") {
      val env = Env(Vector(globalTheme, tenantTheme))
      for result <- env.service.getThemes(tenantId)
      yield assertTrue(result.length == 2)
    },
    test("getThemes excludes themes for other tenants") {
      val otherTheme = ThemeRecord("other", "body {}", Some(TenantId("other")))
      val env = Env(Vector(globalTheme, otherTheme))
      for result <- env.service.getThemes(tenantId)
      yield assertTrue(result == Vector(globalTheme))
    },
    test("createTheme delegates to repository") {
      val env = Env()
      for
        _ <- env.repository.create.succeedsWith(())
        _ <- env.service.createTheme(globalTheme)
      yield assertTrue(env.repository.create.calls == List(globalTheme))
    },
    test("deleteTheme delegates to repository") {
      val env = Env()
      for
        _ <- env.repository.delete.succeedsWith(())
        _ <- env.service.deleteTheme(themeId)
      yield assertTrue(env.repository.delete.calls == List(themeId))
    },
  )
