package versola.oauth.client

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.*
import versola.util.DatabaseSpecBase
import zio.test.*

trait OAuthScopeRepositorySpec extends DatabaseSpecBase[OAuthScopeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Test data
  val scopeName1 = ScopeToken("read")
  val scopeName2 = ScopeToken("write")
  val scopeName3 = ScopeToken("admin")
  val scopeName4 = ScopeToken("profile")

  val scope1 = ScopeRecord(
    name = scopeName1,
    description = ScopeDescription("Read access to user data"),
    claims = Set(Claim("sub"), Claim("name"), Claim("email")),
  )

  val scope2 = ScopeRecord(
    name = scopeName2,
    description = ScopeDescription("Write access to user data"),
    claims = Set(Claim("sub")),
  )

  val scope3 = ScopeRecord(
    name = scopeName3,
    description = ScopeDescription("Administrative access"),
    claims = Set(Claim("sub"), Claim("name"), Claim("email"), Claim("role"), Claim("permissions")),
  )

  val scope4 = ScopeRecord(
    name = scopeName4,
    description = ScopeDescription("Access to profile information"),
    claims = Set(Claim("name"), Claim("picture"), Claim("email")),
  )

  def testCases(env: OAuthScopeRepositorySpec.Env): List[Spec[OAuthScopeRepositorySpec.Env & zio.Scope, Any]] =
    List(
      createOrUpdateTests(env),
      getAllTests(env),
      deleteTests(env),
    )

  def createOrUpdateTests(env: OAuthScopeRepositorySpec.Env) =
    suite("createOrUpdate")(
      test("create new scope successfully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.contains(scopeName1),
          scopes(scopeName1).description == scope1.description,
          scopes(scopeName1).claims == scope1.claims.toSet,
        )
      },
      test("create multiple scopes successfully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1, scope2, scope3))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 3,
          scopes.contains(scopeName1),
          scopes.contains(scopeName2),
          scopes.contains(scopeName3),
          scopes(scopeName1).claims == scope1.claims.toSet,
          scopes(scopeName2).claims == scope2.claims.toSet,
          scopes(scopeName3).claims == scope3.claims.toSet,
        )
      },
      test("update existing scope successfully") {
        val updatedScope1 = scope1.copy(
          description = ScopeDescription("Updated read access"),
          claims = Set(Claim("sub"), Claim("email")),
        )
        for
          _ <- env.repository.createOrUpdate(Vector(scope1))
          _ <- env.repository.createOrUpdate(Vector(updatedScope1))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 1,
          scopes(scopeName1).description == updatedScope1.description,
          scopes(scopeName1).claims == updatedScope1.claims.toSet,
        )
      },
      test("handle empty vector gracefully") {
        for
          _ <- env.repository.createOrUpdate(Vector.empty)
          scopes <- env.repository.getAll
        yield assertTrue(scopes.isEmpty)
      },
    )

  def getAllTests(env: OAuthScopeRepositorySpec.Env) =
    suite("getAll")(
      test("return empty map when no scopes exist") {
        for
          scopes <- env.repository.getAll
        yield assertTrue(scopes.isEmpty)
      },
      test("return all scopes") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1, scope2, scope3))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 3,
          scopes.keySet == Set(scopeName1, scopeName2, scopeName3),
        )
      },
    )

  def deleteTests(env: OAuthScopeRepositorySpec.Env) =
    suite("delete")(
      test("delete single scope successfully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1, scope2))
          _ <- env.repository.delete(Vector(scopeName1))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 1,
          !scopes.contains(scopeName1),
          scopes.contains(scopeName2),
        )
      },
      test("delete multiple scopes successfully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1, scope2, scope3, scope4))
          _ <- env.repository.delete(Vector(scopeName1, scopeName3))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 2,
          !scopes.contains(scopeName1),
          scopes.contains(scopeName2),
          !scopes.contains(scopeName3),
          scopes.contains(scopeName4),
        )
      },
      test("handle deleting non-existent scope gracefully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1))
          _ <- env.repository.delete(Vector(scopeName2))
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 1,
          scopes.contains(scopeName1),
        )
      },
      test("handle empty delete vector gracefully") {
        for
          _ <- env.repository.createOrUpdate(Vector(scope1))
          _ <- env.repository.delete(Vector.empty)
          scopes <- env.repository.getAll
        yield assertTrue(
          scopes.size == 1,
          scopes.contains(scopeName1),
        )
      },
    )

object OAuthScopeRepositorySpec:
  case class Env(repository: OAuthScopeRepository)
