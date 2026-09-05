package versola.central.configuration.metadata

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.json.ast.Json
import zio.test.*

/** Conformance suite for any [[ServerMetadataRepository]] implementation. A backend module
  * extends this and supplies the wiring -- see `PostgresServerMetadataRepositorySpec` in
  * `central-postgres-impl` for the one binding that exists today.
  */
trait ServerMetadataRepositorySpec extends DatabaseSpecBase[ServerMetadataRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  override def testCases(env: ServerMetadataRepositorySpec.Env) =
    List(
      test("get returns nothing when no metadata has been stored") {
        for result <- env.repository.get
        yield assertTrue(result.isEmpty)
      },
      test("upsert then get returns the stored metadata") {
        val metadata = Json.Obj("service_documentation" -> Json.Str("https://docs.example"))
        for
          _ <- env.repository.upsert(metadata)
          result <- env.repository.get
        yield assertTrue(result == Some(ServerMetadataRecord("default", metadata)))
      },
      test("upsert overwrites the previously stored metadata") {
        val first = Json.Obj("a" -> Json.Str("1"))
        val second = Json.Obj("b" -> Json.Str("2"))
        for
          _ <- env.repository.upsert(first)
          _ <- env.repository.upsert(second)
          result <- env.repository.get
        yield assertTrue(result == Some(ServerMetadataRecord("default", second)))
      },
    )

object ServerMetadataRepositorySpec:
  case class Env(repository: ServerMetadataRepository)
