package versola.central.configuration.themes

import versola.central.configuration.tenants.TenantId
import versola.util.theme.VariableValue.{Pair, Single}
import versola.util.theme.{Background, BrandColor, Mode, Surface, ThemeCss, ThemeOverrides, ThemePresets, ThemeTokens, VariableValue}
import versola.util.{ReloadingCache, UnitSpecBase}
import zio.*
import zio.test.*

import java.sql.SQLException

object ThemeServiceSpec extends UnitSpecBase:

  private val tenantId = TenantId("t1")
  private val themeId = "theme-1"
  private val globalTheme = ThemeRecord(themeId, "body { color: red; }", None, tokens = None)
  private val tenantTheme = ThemeRecord("tenant-theme", "body { color: blue; }", Some(tenantId), tokens = None)

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
      val otherTheme = ThemeRecord("other", "body {}", Some(TenantId("other")), tokens = None)
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
        val tokens =
          ThemeTokens(
            base = "studio",
            overrides = Some(ThemeOverrides(mode = Some(Mode.dark), brand = None, shape = None, surface = None, background = None)),
            variables = None,
          )
        val theme = ThemeRecord("studio-dark", "", None, Some(tokens))
        for
          _ <- env.repository.create.succeedsWith(())
          _ <- env.service.createTheme(theme)
        yield assertTrue(env.repository.create.calls == List(theme))
      },
      test("is refused when its base is not a built-in theme, and nothing is stored") {
        val env = Env()
        for
          created <- env.service.createTheme(ThemeRecord("x", "", None, Some(ThemeTokens(base = "nope", overrides = None, variables = None)))).either
          updated <- env.service.updateTheme(ThemeRecord("x", "", None, Some(ThemeTokens(base = "nope", overrides = None, variables = None)))).either
        yield assertTrue(
          created.left.exists(_.isInstanceOf[IllegalArgumentException]),
          updated.left.exists(_.isInstanceOf[IllegalArgumentException]),
          env.repository.create.calls.isEmpty,
          env.repository.update.calls.isEmpty,
        )
      },
      test("is stored with the variables it sets one by one") {
        val env = Env()
        val theme = ThemeRecord(
          "rounded",
          "",
          None,
          Some(ThemeTokens(
            base = "studio",
            overrides = None,
            variables = Some(Map("--rs" -> Single("8px"), "--brand" -> Single("#112233"), "--btn" -> Pair(light = "#112233", dark = "#ddeeff"))),
          )),
        )
        for
          _ <- env.repository.create.succeedsWith(())
          _ <- env.service.createTheme(theme)
        yield assertTrue(env.repository.create.calls == List(theme))
      },
      test("is refused, with the reason and nothing stored, for a variable outside the theming API or an unsafe value") {
        val env = Env()
        def themeWith(variables: Map[String, VariableValue]) =
          ThemeRecord("x", "", None, Some(ThemeTokens(base = "studio", overrides = None, variables = Some(variables))))
        for
          unknown <- env.service.createTheme(themeWith(Map("--page-bg" -> Single("#000")))).either
          unsafe <- env.service.createTheme(themeWith(Map("--rs" -> Single("8px; } body { display: none")))).either
          url <- env.service.updateTheme(themeWith(Map("--page-bg" -> Single("url(x)")))).either
          noExpression <- env.service.updateTheme(themeWith(Map("--brand" -> Single("expression(alert(1))")))).either
        yield assertTrue(
          unknown.left.exists(e => e.isInstanceOf[IllegalArgumentException] && e.getMessage.contains("--page-bg")),
          unsafe.left.exists(e => e.isInstanceOf[IllegalArgumentException] && e.getMessage.startsWith("--rs ")),
          url.left.exists(_.getMessage.contains("--page-bg")),
          noExpression.left.exists(_.getMessage.startsWith("--brand ")),
          env.repository.create.calls.isEmpty,
          env.repository.update.calls.isEmpty,
        )
      },
      test("may be the default theme: it is an ordinary visual theme, the base stylesheet is not stored in it") {
        val env = Env()
        val record = ThemeRecord(ThemeService.DefaultThemeId, "", None, Some(ThemeTokens(base = "studio", overrides = None, variables = None)))
        for
          _ <- env.repository.update.succeedsWith(())
          _ <- env.service.updateTheme(record)
        yield assertTrue(env.repository.update.calls == List(record))
      },
      test("a theme without tokens is stored as before, whatever its CSS") {
        val env = Env()
        for
          _ <- env.repository.create.succeedsWith(())
          _ <- env.service.createTheme(ThemeRecord("plain", "", None, tokens = None))
        yield assertTrue(env.repository.create.calls == List(ThemeRecord("plain", "", None, tokens = None)))
      },
    ),
    suite("render")(
      test("lays the token overrides after the five values and before the theme's own CSS") {
        val env = Env()
        val tokens = ThemeTokens(
          base = "studio",
          overrides = None,
          variables = Some(Map("--rs" -> Single("8px"), "--btn" -> Pair(light = "#112233", dark = "#ddeeff"))),
        )
        for rendered <- env.service.render(RenderThemeRequest(Some(tokens), css = Some("CUSTOM-CSS"), overrides = None))
        yield
          val after = ThemeCss.baseStylesheet.length
          val values = rendered.css.indexOf("--brand:#ff6b4a", after)
          val own = rendered.css.indexOf("--rs:8px;", after)
          assertTrue(
            values >= 0,
            values < own,
            own < rendered.css.indexOf("CUSTOM-CSS"),
            rendered.generated.contains("--rs:8px;"),
            rendered.generated.contains("--btn:light-dark(#112233,#ddeeff);"),
            rendered.css.contains(rendered.generated),
          )
      },
      test("refuses an unsafe token override instead of rendering it") {
        val env = Env()
        val tokens = ThemeTokens(base = "studio", overrides = None, variables = Some(Map("--rs" -> Single("8px;}"))))
        for result <- env.service.render(RenderThemeRequest(Some(tokens), css = None, overrides = None)).either
        yield assertTrue(result.left.exists(e => e.isInstanceOf[IllegalArgumentException] && e.getMessage.startsWith("--rs ")))
      },
      test("lays the base stylesheet, what the tokens set and the theme's own CSS in that order") {
        // The stored default theme is a token theme with no CSS (or, in an older store, a stylesheet): neither is the foundation.
        val env = Env(Vector(ThemeRecord(ThemeService.DefaultThemeId, "STORED-DEFAULT-CSS", None, tokens = None)))
        for rendered <- env.service.render(RenderThemeRequest(
            Some(ThemeTokens(base = "studio", overrides = None, variables = None)),
            Some("CUSTOM-CSS"),
            overrides = None,
          ))
        yield assertTrue(
          rendered.css.startsWith(ThemeCss.baseStylesheet),
          !rendered.css.contains("STORED-DEFAULT-CSS"),
          rendered.css.indexOf("--brand:#ff6b4a", ThemeCss.baseStylesheet.length) >= 0,
          rendered.css.indexOf("--brand:#ff6b4a", ThemeCss.baseStylesheet.length) < rendered.css.indexOf("CUSTOM-CSS"),
          rendered.generated.contains("--brand:#ff6b4a"),
          !rendered.generated.contains(ThemeCss.baseStylesheet),
        )
      },
      test("applies the overrides over the base") {
        val env = Env(Vector(ThemeRecord(ThemeService.DefaultThemeId, "D", None, tokens = None)))
        val tokens =
          ThemeTokens(
            base = "studio",
            overrides =
              Some(ThemeOverrides(brand = BrandColor.parse("#112233").toOption, shape = None, surface = None, mode = None, background = None)),
            variables = None,
          )
        for rendered <- env.service.render(RenderThemeRequest(Some(tokens), css = None, overrides = None))
        yield assertTrue(rendered.generated.contains("--brand:#112233"), rendered.generated.contains("--rs:32px"))
      },
      test("renders the background of the tokens, so the live preview shows what the forms will") {
        val env = Env(Vector(ThemeRecord(ThemeService.DefaultThemeId, "D", None, tokens = None)))
        val tokens =
          ThemeTokens(base = "studio", overrides = Some(ThemeOverrides(None, None, None, None, Some(Background.horizon))), variables = None)
        for
          horizon <- env.service.render(RenderThemeRequest(Some(tokens), css = None, overrides = None))
          preset <- env.service.render(RenderThemeRequest(
            Some(ThemeTokens(base = "fintech", overrides = None, variables = None)),
            css = None,
            overrides = None,
          ))
        yield assertTrue(
          horizon.generated.contains("--page-bg:radial-gradient(130% 60% at 50% 110%,var(--page-tint),transparent 72%),var(--page);"),
          horizon.css.contains(horizon.generated),
          preset.generated.contains("--page-bg:linear-gradient(160deg,"),
        )
      },
      test("lays a client's overrides after the theme, as the block ThemeCss.generatePartial writes for them") {
        val env = Env()
        val own = ThemeOverrides(BrandColor.parse("#112233").toOption, None, None, Some(Mode.dark), None)
        val tokens = ThemeTokens(base = "studio", overrides = None, variables = None)
        val base = ThemePresets.resolve(tokens).toOption.get
        for
          plain <- env.service.render(RenderThemeRequest(Some(tokens), Some("CUSTOM-CSS"), overrides = None))
          rendered <- env.service.render(RenderThemeRequest(Some(tokens), Some("CUSTOM-CSS"), Some(own)))
        yield assertTrue(
          rendered.css == plain.css + "\n" + ThemeCss.generatePartial(base, own),
          rendered.css.indexOf("CUSTOM-CSS") < rendered.css.indexOf("--brand:#112233"),
          rendered.generated == plain.generated,
        )
      },
      test("measures the overrides of a token theme against the tokens' own overrides, not the preset") {
        val env = Env()
        val themeOwn = ThemeOverrides(None, None, None, None, Some(Background.horizon))
        val tokens = ThemeTokens(base = "studio", overrides = Some(themeOwn), variables = None)
        val own = ThemeOverrides(None, None, Some(Surface.soft), None, None)
        val base = ThemePresets.resolve(tokens).toOption.get
        for rendered <- env.service.render(RenderThemeRequest(Some(tokens), css = None, Some(own)))
        yield assertTrue(rendered.css.endsWith(ThemeCss.generatePartial(base, own)))
      },
      test("an empty overrides object renders exactly what no overrides renders") {
        val env = Env()
        val tokens = ThemeTokens(base = "studio", overrides = None, variables = None)
        for
          none <- env.service.render(RenderThemeRequest(Some(tokens), Some("X{}"), overrides = None))
          empty <- env.service.render(RenderThemeRequest(Some(tokens), Some("X{}"), Some(ThemeOverrides.empty)))
          sheetNone <- env.service.render(RenderThemeRequest(None, Some("X{}"), overrides = None))
          sheetEmpty <- env.service.render(RenderThemeRequest(None, Some("X{}"), Some(ThemeOverrides.empty)))
        yield assertTrue(none == empty, sheetNone == sheetEmpty)
      },
      test("a theme with CSS only (no tokens) is its CSS alone, with no foundation and no generated block") {
        val env = Env()
        for rendered <- env.service.render(RenderThemeRequest(None, Some("STYLESHEET{}"), overrides = None))
        yield assertTrue(rendered.css == "STYLESHEET{}", rendered.generated == "")
      },
      test("a theme with CSS only takes the overrides on top, measured against the default preset as auth does") {
        val env = Env()
        val own = ThemeOverrides(BrandColor.parse("#112233").toOption, None, None, None, None)
        val base = ThemePresets.find(ThemePresets.DefaultId).map(_.tokens).get
        for rendered <- env.service.render(RenderThemeRequest(None, Some("STYLESHEET{}"), Some(own)))
        yield assertTrue(
          rendered.css == "STYLESHEET{}\n" + ThemeCss.generatePartial(base, own),
          !rendered.css.contains(ThemeCss.baseStylesheet),
          rendered.generated == "",
        )
      },
      test("neither tokens nor CSS is the foundation, as for a theme auth does not find") {
        val env = Env()
        for rendered <- env.service.render(RenderThemeRequest(None, css = None, overrides = None))
        yield assertTrue(rendered.css == ThemeCss.baseStylesheet)
      },
      test("is refused for a base that is not built in") {
        val env = Env()
        for result <- env.service.render(RenderThemeRequest(
            Some(ThemeTokens(base = "nope", overrides = None, variables = None)),
            css = None,
            overrides = None,
          )).either
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
