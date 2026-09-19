package versola.central.configuration.jwks

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.{DatabaseSpecBase, Secret}
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

  private val encryptedPrivateKey = Secret.fromString("encrypted-pkcs8")

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
          _ <- env.repository.create("key-1", jwk1, privateKey = None)
          found <- env.repository.find("key-1")
          all <- env.repository.getAll
        yield assertTrue(
          found == Some(JwksRecord("key-1", jwk1, None)),
          all == Vector(JwksRecord("key-1", jwk1, None)),
        )
      },
      // A key stored without its private half is verify-only, and a key stored with one can
      // sign: the column is what tells auth which of the two it received, so it has to
      // survive the round-trip byte for byte.
      test("create stores the encrypted private half alongside the published key") {
        for
          _ <- env.repository.create("key-1", jwk1, Some(encryptedPrivateKey))
          found <- env.repository.find("key-1")
        yield assertTrue(
          found.exists(_.privateKey.exists(_.sameElements(encryptedPrivateKey))),
          found.exists(_.canSign == false), // jwk1 has no `alg`, so it is still not signable
        )
      },
      test("getAll returns every key") {
        for
          _ <- env.repository.create("key-1", jwk1, privateKey = None)
          _ <- env.repository.create("key-2", jwk2, privateKey = None)
          all <- env.repository.getAll
        yield assertTrue(all.toSet == Set(JwksRecord("key-1", jwk1, None), JwksRecord("key-2", jwk2, None)))
      },
      test("update replaces the key material for an existing kid") {
        val updated = Json.Obj("kty" -> Json.Str("EC"), "kid" -> Json.Str("key-1"))
        for
          _ <- env.repository.create("key-1", jwk1, privateKey = None)
          _ <- env.repository.update("key-1", updated)
          found <- env.repository.find("key-1")
        yield assertTrue(found == Some(JwksRecord("key-1", updated, None)))
      },
      // The private half is written once, at creation. An update that could replace the
      // published half alone would let a kid come to name a different keypair than the one
      // signing under it.
      test("update leaves the private half as it was created") {
        val updated = Json.Obj("kty" -> Json.Str("RSA"), "kid" -> Json.Str("key-1"), "alg" -> Json.Str("PS256"))
        for
          _ <- env.repository.create("key-1", jwk1, Some(encryptedPrivateKey))
          _ <- env.repository.update("key-1", updated)
          found <- env.repository.find("key-1")
        yield assertTrue(
          found.map(_.jwk) == Some(updated),
          found.exists(_.privateKey.exists(_.sameElements(encryptedPrivateKey))),
        )
      },
      test("delete removes the key") {
        for
          _ <- env.repository.create("key-1", jwk1, privateKey = None)
          _ <- env.repository.delete("key-1")
          found <- env.repository.find("key-1")
        yield assertTrue(found.isEmpty)
      },
    )

object JwksRepositorySpec:
  case class Env(repository: JwksRepository)
