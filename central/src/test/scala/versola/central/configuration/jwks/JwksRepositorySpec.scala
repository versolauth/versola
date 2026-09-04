package versola.central.configuration.jwks

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.json.ast.Json
import zio.test.*

/** Conformance suite for any [[JwksRepository]] implementation. A backend module extends
  * this and supplies the wiring -- see `PostgresJwksRepositorySpec` in `central-postgres-impl`
  * for the one binding that exists today.
  */
trait JwksRepositorySpec extends DatabaseSpecBase[JwksRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val jwk1 = Json.Obj("kty" -> Json.Str("RSA"), "kid" -> Json.Str("key-1"))
  private val jwk2 = Json.Obj("kty" -> Json.Str("RSA"), "kid" -> Json.Str("key-2"))

  override def testCases(env: JwksRepositorySpec.Env) =
    List(
      test("getAll returns nothing when the table is empty") {
        for all <- env.repository.getAll
        yield assertTrue(all.isEmpty)
      },
      test("find returns None for an unknown kid") {
        for found <- env.repository.find("missing")
        yield assertTrue(found.isEmpty)
      },
      test("create stores a key retrievable by find and getAll") {
        for
          _ <- env.repository.create("key-1", jwk1)
          found <- env.repository.find("key-1")
          all <- env.repository.getAll
        yield assertTrue(
          found == Some(JwksRecord("key-1", jwk1)),
          all == Vector(JwksRecord("key-1", jwk1)),
        )
      },
      test("getAll returns every key") {
        for
          _ <- env.repository.create("key-1", jwk1)
          _ <- env.repository.create("key-2", jwk2)
          all <- env.repository.getAll
        yield assertTrue(all.toSet == Set(JwksRecord("key-1", jwk1), JwksRecord("key-2", jwk2)))
      },
      test("update replaces the key material for an existing kid") {
        val updated = Json.Obj("kty" -> Json.Str("EC"), "kid" -> Json.Str("key-1"))
        for
          _ <- env.repository.create("key-1", jwk1)
          _ <- env.repository.update("key-1", updated)
          found <- env.repository.find("key-1")
        yield assertTrue(found == Some(JwksRecord("key-1", updated)))
      },
      test("delete removes the key") {
        for
          _ <- env.repository.create("key-1", jwk1)
          _ <- env.repository.delete("key-1")
          found <- env.repository.find("key-1")
        yield assertTrue(found.isEmpty)
      },
    )

object JwksRepositorySpec:
  case class Env(repository: JwksRepository)
