package versola.oauth

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.model.*
import versola.util.{Argon2Hash, Argon2Salt, DatabaseSpecBase}
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

trait OAuthClientRepositorySpec extends DatabaseSpecBase[OAuthClientRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Test data
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val clientId3 = ClientId("public-client-1")

  val hash1 = Argon2Hash(Array.fill(32)(1.toByte))
  val salt1 = Argon2Salt(Array.fill(16)(1.toByte))
  val hash2 = Argon2Hash(Array.fill(32)(2.toByte))
  val salt2 = Argon2Salt(Array.fill(16)(2.toByte))
  val hash3 = Argon2Hash(Array.fill(32)(3.toByte))
  val salt3 = Argon2Salt(Array.fill(16)(3.toByte))

  val privateClient1 = OAuthClient(
    id = clientId1,
    clientName = "Test Private Client 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set("read", "write"),
    secretHash = Some(hash1),
    secretSalt = Some(salt1),
    previousSecretHash = None,
    previousSecretSalt = None,
  )

  val privateClient2 = OAuthClient(
    id = clientId2,
    clientName = "Test Private Client 2",
    redirectUris = NonEmptySet("https://example2.com/callback", "https://example2.com/callback2"),
    scope = Set("read"),
    secretHash = Some(hash2),
    secretSalt = Some(salt2),
    previousSecretHash = Some(hash1),
    previousSecretSalt = Some(salt1),
  )

  val publicClient = OAuthClient(
    id = clientId3,
    clientName = "Test Public Client",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set("read"),
    secretHash = None,
    secretSalt = None,
    previousSecretHash = None,
    previousSecretSalt = None,
  )

  def testCases(env: OAuthClientRepositorySpec.Env): List[Spec[OAuthClientRepositorySpec.Env & zio.Scope, Any]] =
    List(
      createTests(env),
      updateTests(env),
      getAllTests(env),
      rotateSecretTests(env),
      deletePreviousSecretTests(env),
      deleteTests(env),
    )

  def createTests(env: OAuthClientRepositorySpec.Env) =
    suite("create")(
      test("create private client successfully") {
        for
          _ <- env.repository.create(privateClient1)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.contains(clientId1),
          clients(privateClient1.id) == privateClient1
        )
      },
      test("create public client successfully") {
        for
          _ <- env.repository.create(publicClient)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.contains(clientId3),
          clients(clientId3) == publicClient
        )
      },
      test("create multiple clients") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.create(publicClient)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 2,
          clients.contains(clientId1),
          clients.contains(clientId3),
          clients(clientId1) == privateClient1,
          clients(clientId3) == publicClient
        )
      },
    )

  def updateTests(env: OAuthClientRepositorySpec.Env) =
    suite("update")(
      test("update client properties successfully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.update(clientId1, "Updated Client", Set("https://updated.example.com/callback"), Set("read", "write"))
          clients <- env.repository.getAll
          updatedClient = clients(clientId1)
        yield assertTrue(
          updatedClient.clientName == "Updated Client",
          updatedClient.redirectUris == NonEmptySet("https://updated.example.com/callback"),
          updatedClient.scope == Set("read", "write"),
        )
      },
    )

  def getAllTests(env: OAuthClientRepositorySpec.Env) =
    suite("getAll")(
      test("return empty map when no clients exist") {
        for
          clients <- env.repository.getAll
        yield assertTrue(clients.isEmpty)
      },
      test("return all registered clients") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.create(publicClient)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 3,
          clients(clientId1) == privateClient1,
          clients(clientId2) == privateClient2,
          clients(clientId3) ==  publicClient
        )
      },
    )

  def rotateSecretTests(env: OAuthClientRepositorySpec.Env) =
    suite("rotateSecret")(
      test("rotate secret successfully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.rotateSecret(clientId1, hash3, salt3)
          clients <- env.repository.getAll
          updatedClient = clients(clientId1)
        yield assertTrue(
          updatedClient.secretHash.map(_.toBase64Url).contains(hash3.toBase64Url),
          updatedClient.secretSalt.map(_.toBase64Url).contains(salt3.toBase64Url),
          updatedClient.previousSecretHash.map(_.toBase64Url).contains(hash1.toBase64Url),
          updatedClient.previousSecretSalt.map(_.toBase64Url).contains(salt1.toBase64Url)
        )
      },
      test("rotate secret when previous secret already exists") {
        for
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.rotateSecret(clientId2, hash3, salt3)
          clients <- env.repository.getAll
          updatedClient = clients(clientId2)
        yield assertTrue(
          updatedClient.secretHash.map(_.toBase64Url).contains(hash3.toBase64Url),
          updatedClient.secretSalt.map(_.toBase64Url).contains(salt3.toBase64Url),
          updatedClient.previousSecretHash.map(_.toBase64Url).contains(hash2.toBase64Url),
          updatedClient.previousSecretSalt.map(_.toBase64Url).contains(salt2.toBase64Url)
        )
      },
    )

  def deletePreviousSecretTests(env: OAuthClientRepositorySpec.Env) =
    suite("deletePreviousSecret")(
      test("delete previous secret successfully") {
        for
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.deletePreviousSecret(clientId2)
          clients <- env.repository.getAll
          updatedClient = clients(clientId2)
        yield assertTrue(
          updatedClient.secretHash.map(_.toBase64Url).contains(hash2.toBase64Url),
          updatedClient.secretSalt.map(_.toBase64Url).contains(salt2.toBase64Url),
          updatedClient.previousSecretHash.isEmpty,
          updatedClient.previousSecretSalt.isEmpty
        )
      },
      test("delete previous secret when none exists") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.deletePreviousSecret(clientId1)
          clients <- env.repository.getAll
          updatedClient = clients(clientId1)
        yield assertTrue(
          updatedClient.secretHash.map(_.toBase64Url).contains(hash1.toBase64Url),
          updatedClient.secretSalt.map(_.toBase64Url).contains(salt1.toBase64Url),
          updatedClient.previousSecretHash.isEmpty,
          updatedClient.previousSecretSalt.isEmpty
        )
      },
    )

  def deleteTests(env: OAuthClientRepositorySpec.Env) =
    suite("delete")(
      test("delete single client successfully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.delete(Vector(clientId1))
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 1,
          !clients.contains(clientId1),
          clients.contains(clientId2),
        )
      },
      test("delete multiple clients successfully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.create(publicClient)
          _ <- env.repository.delete(Vector(clientId1, clientId3))
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 1,
          !clients.contains(clientId1),
          clients.contains(clientId2),
          !clients.contains(clientId3),
        )
      },
      test("handle deleting non-existent client gracefully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.delete(Vector(clientId2))
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 1,
          clients.contains(clientId1),
        )
      },
      test("handle empty delete vector gracefully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.delete(Vector.empty)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.size == 1,
          clients.contains(clientId1),
        )
      },
    )

object OAuthClientRepositorySpec:
  case class Env(repository: OAuthClientRepository)
