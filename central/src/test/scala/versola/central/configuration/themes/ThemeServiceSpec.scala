package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

import java.sql.SQLException

object ThemeServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("t1")
  private val themeId = "theme-1"
  private val globalTheme = ThemeRecord(themeId, "body { color: red; }", None)
  private val tenantTheme = ThemeRecord("tenant-theme", "body { color: blue; }", Some(tenantId))

  class Env(initial: Vector[ThemeRecord] = Vector.empty):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ThemeRepository]
    val service = ThemeService.Impl(cache, repository)

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
    test("updateTheme delegates to repository") {
      val env = Env()
      for
        _ <- env.repository.update.succeedsWith(())
        _ <- env.service.updateTheme(globalTheme)
      yield assertTrue(env.repository.update.calls == List(globalTheme))
    },
    test("deleteTheme rejects deleting the default theme without calling the repository") {
      val env = Env()
      for result <- env.service.deleteTheme(ThemeService.DefaultThemeId).either
      yield assertTrue(
        result.swap.exists(_.isInstanceOf[IllegalArgumentException]),
        result.swap.exists(_.getMessage == "Cannot delete the default theme"),
        env.repository.delete.calls.isEmpty,
      )
    },
    test("deleteTheme maps a foreign key violation to ThemeInUseError") {
      val env = Env()
      val sqlException = new SQLException("fk violation", "23503")
      for
        _ <- env.repository.delete.failsWith(sqlException)
        result <- env.service.deleteTheme(themeId).either
      yield assertTrue(result.swap.exists(_.isInstanceOf[ThemeService.ThemeInUseError]))
    },
    test("deleteTheme maps a foreign key violation wrapped in another exception to ThemeInUseError") {
      val env = Env()
      val sqlException = new SQLException("fk violation", "23503")
      val wrapped = new RuntimeException("wrapped", sqlException)
      for
        _ <- env.repository.delete.failsWith(wrapped)
        result <- env.service.deleteTheme(themeId).either
      yield assertTrue(result.swap.exists(_.isInstanceOf[ThemeService.ThemeInUseError]))
    },
    test("deleteTheme propagates non-foreign-key repository errors unchanged") {
      val env = Env()
      val error = new RuntimeException("boom")
      for
        _ <- env.repository.delete.failsWith(error)
        result <- env.service.deleteTheme(themeId).either
      yield assertTrue(result == Left(error))
    },
    test("sync refreshes the cache from the repository") {
      val env = Env(Vector(globalTheme))
      for
        _ <- env.repository.getAll.succeedsWith(Vector(tenantTheme))
        _ <- env.service.sync()
        cached <- env.cache.get
      yield assertTrue(cached == Vector(tenantTheme))
    },
  )
