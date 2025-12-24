package versola.oauth.client

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.*
import versola.util.{DatabaseSpecBase, Secret}
import zio.durationInt
import zio.prelude.{EqualOps, NonEmptySet}
import zio.test.*

trait OAuthClientRepositorySpec extends DatabaseSpecBase[OAuthClientRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Test data
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val clientId3 = ClientId("public-client-1")

  // BLAKE3 MAC (32 bytes) || salt (16 bytes) = 48 bytes
  val macWithSalt1 = Secret(Array.fill(32)(1.toByte) ++ Array.fill(16)(1.toByte))
  val macWithSalt2 = Secret(Array.fill(32)(2.toByte) ++ Array.fill(16)(2.toByte))
  val macWithSalt3 = Secret(Array.fill(32)(3.toByte) ++ Array.fill(16)(3.toByte))

  val privateClient1 = OAuthClientRecord(
    id = clientId1,
    clientName = "Test Private Client 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set("read", "write"),
    secret = Some(macWithSalt1),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    accessTokenType = AccessTokenType.Opaque,
  )

  val privateClient2 = OAuthClientRecord(
    id = clientId2,
    clientName = "Test Private Client 2",
    redirectUris = NonEmptySet("https://example2.com/callback", "https://example2.com/callback2"),
    scope = Set("read"),
    secret = Some(macWithSalt2),
    previousSecret = Some(macWithSalt1),
    accessTokenTtl = 10.minutes,
    accessTokenType = AccessTokenType.Opaque,
  )

  val publicClient = OAuthClientRecord(
    id = clientId3,
    clientName = "Test Public Client",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set("read"),
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
    accessTokenType = AccessTokenType.Opaque,
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
          clients(privateClient1.id) === privateClient1
        )
      },
      test("create public client successfully") {
        for
          _ <- env.repository.create(publicClient)
          clients <- env.repository.getAll
        yield assertTrue(
          clients.contains(clientId3),
          clients(clientId3) === publicClient
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
          clients(clientId1) === privateClient1,
          clients(clientId3) === publicClient
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
          clients(clientId1) === privateClient1,
          clients(clientId2) === privateClient2,
          clients(clientId3) ===  publicClient
        )
      },
    )

  def rotateSecretTests(env: OAuthClientRepositorySpec.Env) =
    suite("rotateSecret")(
      test("rotate secret successfully") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.rotateSecret(clientId1, macWithSalt3)
          clients <- env.repository.getAll
          updatedClient = clients(clientId1)
        yield assertTrue(
          updatedClient.secret.exists(java.util.Arrays.equals(_, macWithSalt3)),
          updatedClient.previousSecret.exists(java.util.Arrays.equals(_, macWithSalt1))
        )
      },
      test("rotate secret when previous secret already exists") {
        for
          _ <- env.repository.create(privateClient2)
          _ <- env.repository.rotateSecret(clientId2, macWithSalt3)
          clients <- env.repository.getAll
          updatedClient = clients(clientId2)
        yield assertTrue(
          updatedClient.secret.exists(java.util.Arrays.equals(_, macWithSalt3)),
          updatedClient.previousSecret.exists(java.util.Arrays.equals(_, macWithSalt2))
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
          updatedClient.secret.exists(java.util.Arrays.equals(_, macWithSalt2)),
          updatedClient.previousSecret.isEmpty
        )
      },
      test("delete previous secret when none exists") {
        for
          _ <- env.repository.create(privateClient1)
          _ <- env.repository.deletePreviousSecret(clientId1)
          clients <- env.repository.getAll
          updatedClient = clients(clientId1)
        yield assertTrue(
          updatedClient.secret.exists(java.util.Arrays.equals(_, macWithSalt1)),
          updatedClient.previousSecret.isEmpty
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
