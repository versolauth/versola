package versola.central.configuration.themes

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.test.*

/** Conformance suite for any [[ThemeRepository]] implementation. A backend module extends
  * this and supplies the wiring -- see `PostgresThemeRepositorySpec` in `central-postgres-impl`
  * for the one binding that exists today.
  */
trait ThemeRepositorySpec extends DatabaseSpecBase[ThemeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val tenantId = TenantId("tenant-a")

  override def testCases(env: ThemeRepositorySpec.Env) =
    List(
      test("getAll returns nothing when the table is empty") {
        for all <- env.repository.getAll
        yield assertTrue(all.isEmpty)
      },
      test("create stores a global theme with no tenant") {
        val theme = ThemeRecord("dark", "body { color: white; }", None)
        for
          _ <- env.repository.create(theme)
          all <- env.repository.getAll
        yield assertTrue(all == Vector(theme))
      },
      test("create stores a theme scoped to a tenant") {
        val theme = ThemeRecord("brand", "body { color: blue; }", Some(tenantId))
        for
          _ <- env.repository.create(theme)
          all <- env.repository.getAll
        yield assertTrue(all == Vector(theme))
      },
      test("getAll orders by id and includes every theme") {
        for
          _ <- env.repository.create(ThemeRecord("zeta", "a", None))
          _ <- env.repository.create(ThemeRecord("alpha", "b", None))
          all <- env.repository.getAll
        yield assertTrue(all.map(_.id) == Vector("alpha", "zeta"))
      },
      test("update replaces the css but leaves the original tenant untouched") {
        val theme = ThemeRecord("dark", "body { color: white; }", Some(tenantId))
        for
          _ <- env.repository.create(theme)
          _ <- env.repository.update(theme.copy(css = "body { color: black; }", tenantId = None))
          all <- env.repository.getAll
        yield assertTrue(all == Vector(theme.copy(css = "body { color: black; }")))
      },
      test("delete removes the theme") {
        val theme = ThemeRecord("dark", "body { color: white; }", None)
        for
          _ <- env.repository.create(theme)
          _ <- env.repository.delete("dark")
          all <- env.repository.getAll
        yield assertTrue(all.isEmpty)
      },
    )

object ThemeRepositorySpec:
  case class Env(repository: ThemeRepository)
