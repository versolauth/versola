package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.theme.{BrandColor, Mode, ThemeOverrides, ThemeTokens}
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
    suite("a theme built from tokens")(
      test("is stored with its tokens and its own CSS") {
        val env = Env()
        val tokens = ThemeTokens("studio", ThemeOverrides(mode = Some(Mode.dark)))
        val theme = ThemeRecord("studio-dark", "", None, Some(tokens))
        for
          _ <- env.repository.create.succeedsWith(())
          _ <- env.service.createTheme(theme)
        yield assertTrue(env.repository.create.calls == List(theme))
      },
      test("is refused when its base is not a built-in theme, and nothing is stored") {
        val env = Env()
        for
          created <- env.service.createTheme(ThemeRecord("x", "", None, Some(ThemeTokens("nope")))).either
          updated <- env.service.updateTheme(ThemeRecord("x", "", None, Some(ThemeTokens("nope")))).either
        yield assertTrue(
          created.left.exists(_.isInstanceOf[IllegalArgumentException]),
          updated.left.exists(_.isInstanceOf[IllegalArgumentException]),
          env.repository.create.calls.isEmpty,
          env.repository.update.calls.isEmpty,
        )
      },
      test("cannot be the default theme, which every token theme is laid over") {
        val env = Env()
        for result <- env.service.updateTheme(ThemeRecord(ThemeService.DefaultThemeId, "", None, Some(ThemeTokens("studio")))).either
        yield assertTrue(result.left.exists(_.isInstanceOf[IllegalArgumentException]), env.repository.update.calls.isEmpty)
      },
      test("a theme without tokens is stored as before, whatever its CSS") {
        val env = Env()
        for
          _ <- env.repository.create.succeedsWith(())
          _ <- env.service.createTheme(ThemeRecord("plain", "", None))
        yield assertTrue(env.repository.create.calls == List(ThemeRecord("plain", "", None)))
      },
    ),
    suite("render")(
      test("lays the default stylesheet, what the tokens set and the theme's own CSS in that order") {
        val env = Env(Vector(ThemeRecord(ThemeService.DefaultThemeId, "DEFAULT-CSS", None)))
        for rendered <- env.service.render(RenderThemeRequest(ThemeTokens("studio"), "CUSTOM-CSS"))
        yield assertTrue(
          rendered.css.indexOf("DEFAULT-CSS") < rendered.css.indexOf("--brand:#ff6b4a"),
          rendered.css.indexOf("--brand:#ff6b4a") < rendered.css.indexOf("CUSTOM-CSS"),
          rendered.generated.contains("--brand:#ff6b4a"),
          !rendered.generated.contains("DEFAULT-CSS"),
        )
      },
      test("applies the overrides over the base") {
        val env = Env(Vector(ThemeRecord(ThemeService.DefaultThemeId, "D", None)))
        val tokens = ThemeTokens("studio", ThemeOverrides(brand = BrandColor.parse("#112233").toOption))
        for rendered <- env.service.render(RenderThemeRequest(tokens))
        yield assertTrue(rendered.generated.contains("--brand:#112233"), rendered.generated.contains("--rs:32px"))
      },
      test("is refused for a base that is not built in") {
        val env = Env()
        for result <- env.service.render(RenderThemeRequest(ThemeTokens("nope"))).either
        yield assertTrue(result.left.exists(_.isInstanceOf[IllegalArgumentException]))
      },
    ),
    test("sync refreshes the cache from the repository") {
      val env = Env(Vector(globalTheme))
      for
        _ <- env.repository.getAll.succeedsWith(Vector(tenantTheme))
        _ <- env.service.sync()
        cached <- env.cache.get
      yield assertTrue(cached == Vector(tenantTheme))
    },
  )
