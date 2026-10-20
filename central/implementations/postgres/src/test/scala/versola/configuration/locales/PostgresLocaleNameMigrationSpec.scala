package versola.configuration.locales

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.{DbCon, sql}
import versola.util.postgres.PostgresSpec
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** V1026 names the seeded Russian locale in Russian, and only that: a name an administrator has set stays. */
object PostgresLocaleNameMigrationSpec extends PostgresSpec:

  private def buildRoot: Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt"))).getOrElse(Path.of("").toAbsolutePath)

  private val migration = buildRoot.resolve("central/implementations/postgres/migrations/V1026__locale_russian_name.sql")

  private def nameOf(code: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect(sql"SELECT name FROM locales WHERE code = $code".query[String].run().head)

  private def stored(code: String, name: String) =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      xa.connect:
        sql"DELETE FROM locales WHERE code = $code".update.run()
        sql"INSERT INTO locales (code, name, is_default, active) VALUES ($code, $name, false, true)".update.run()

  /** Runs the migration file itself, as Flyway would. */
  private val migrate =
    ZIO.serviceWithZIO[TransactorZIO]: xa =>
      ZIO.attemptBlocking(Files.readString(migration)).flatMap: statements =>
        xa.connect:
          val statement = summon[DbCon].connection.createStatement()
          try statement.execute(statements)
          finally statement.close()
        .unit

  override def spec = suite("V1026 locale name")(
    test("renames the seeded 'Russian' to Russian in Russian") {
      for
        _ <- stored("ru", "Russian")
        _ <- migrate
        name <- nameOf("ru")
      yield assertTrue(name == "Русский")
    },
    test("leaves a name an administrator has set") {
      for
        _ <- stored("ru", "Русский язык")
        _ <- migrate
        name <- nameOf("ru")
      yield assertTrue(name == "Русский язык")
    },
    test("leaves other locales, even one named 'Russian'") {
      for
        _ <- stored("be", "Russian")
        _ <- migrate
        name <- nameOf("be")
      yield assertTrue(name == "Russian")
    },
  ) @@ TestAspect.sequential
