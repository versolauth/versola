package versola.loadgen.store

import zio.test.*

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

/** Guards the one property of this schema that fails silently rather than loudly.
  *
  * `PostgresHikariDataSource` configures Flyway with `validateMigrationNaming(false)`, so a
  * migration whose filename Flyway does not recognise is skipped without complaint: the process
  * boots, passes its readiness check, and the first query fails against a table that was never
  * created. Flyway will therefore never catch a file named `L0001__*.sql` or `0001_*.sql` here
  * -- this test is the only thing that does. See [[LoadgenMigrations]].
  */
object MigrationNamingSpec extends ZIOSpecDefault:

  /** sbt runs these tests with the repository root as the working directory; the second
    * candidate covers being run with the module's own directory as the root instead.
    */
  private val migrations: Path =
    List(Path.of("loadgen", "migrations"), Path.of("migrations"))
      .find(Files.isDirectory(_))
      .getOrElse(Path.of("loadgen", "migrations"))

  private val flywayVersioned = raw"V\d{4}__[a-z0-9_]+\.sql".r

  private def sqlFiles: List[String] =
    Files.list(migrations).iterator().asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".sql"))
      .toList
      .sorted

  def spec = suite("loadgen/migrations")(
    test("every migration carries Flyway's default V prefix and a four-digit version") {
      val offenders = sqlFiles.filterNot(flywayVersioned.matches)
      assertTrue(offenders.isEmpty)
    },
    test("versions are unique and contiguous from 0001") {
      val versions = sqlFiles.map(_.substring(1, 5).toInt)
      assertTrue(versions == (1 to versions.size).toList)
    },
    test("the directory is not empty, so the two assertions above are not vacuous") {
      assertTrue(sqlFiles.nonEmpty)
    },
  )
