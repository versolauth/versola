package versola.oauth.client

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.*
import versola.util.DatabaseSpecBase
import zio.test.*

trait ExternalOAuthClientRepositorySpec extends DatabaseSpecBase[ExternalOAuthClientRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  // Test data
  val googleProvider = OauthProviderName.google
  val githubProvider = OauthProviderName.github

  val googleClientId = ClientId("google-client-123")
  val githubClientId = ClientId("github-client-456")

  val googleSecret = ClientSecret("google-secret-abc".getBytes)
  val githubSecret = ClientSecret("github-secret-def".getBytes)

  override def testCases(env: ExternalOAuthClientRepositorySpec.Env) =
    List(
      registerTests(env),
      listAllTests(env),
      rotationTests(env),
      deletionTests(env),
      encryptionTests(env),
    )

  def registerTests(env: ExternalOAuthClientRepositorySpec.Env) =
    suite("register")(
      test("register new external OAuth client") {
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.provider == googleProvider,
          clients.head.clientId == googleClientId,
          clients.head.clientSecret == googleSecret,
        )
      },
      test("register multiple clients") {
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.register(githubProvider, githubClientId, githubSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 2,
          clients.exists(c => c.provider == googleProvider && c.clientId == googleClientId),
          clients.exists(c => c.provider == githubProvider && c.clientId == githubClientId),
        )
      }
    )

  def listAllTests(env: ExternalOAuthClientRepositorySpec.Env) =
    suite("listAll")(
      test("return empty vector when no clients exist") {
        for
          clients <- env.repository.listAll()
        yield assertTrue(clients.isEmpty)
      },
      test("return all registered clients") {
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.register(githubProvider, githubClientId, githubSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 2,
          clients.exists(c => c.provider == googleProvider && c.clientSecret == googleSecret),
          clients.exists(c => c.provider == githubProvider && c.clientSecret == githubSecret),
        )
      },
      test("clients are ordered by id") {
        for
          _ <- env.repository.register(githubProvider, githubClientId, githubSecret)
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 2,
          clients.head.id < clients.last.id,
        )
      },
    )

  def rotationTests(env: ExternalOAuthClientRepositorySpec.Env) =
    suite("secret rotation")(
      test("rotate secret moves current to old and sets new") {
        val newSecret = ClientSecret("rotated-secret".getBytes)
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.rotateSecret(googleProvider, googleClientId, newSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.clientSecret == newSecret,
          clients.head.oldClientSecret == Some(googleSecret),
        )
      },
      test("rotate secret multiple times") {
        val secret2 = ClientSecret("secret-2".getBytes)
        val secret3 = ClientSecret("secret-3".getBytes)
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.rotateSecret(googleProvider, googleClientId, secret2)
          _ <- env.repository.rotateSecret(googleProvider, googleClientId, secret3)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.clientSecret == secret3,
          clients.head.oldClientSecret == Some(secret2),
        )
      },
    )

  def deletionTests(env: ExternalOAuthClientRepositorySpec.Env) =
    suite("old secret deletion")(
      test("delete old secret removes old password") {
        val newSecret = ClientSecret("new-secret".getBytes)
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.rotateSecret(googleProvider, googleClientId, newSecret)
          _ <- env.repository.deleteOldSecret(googleProvider, googleClientId)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.clientSecret == newSecret,
          clients.head.oldClientSecret.isEmpty,
        )
      },
      test("delete old secret when no old secret exists") {
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          _ <- env.repository.deleteOldSecret(googleProvider, googleClientId)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.clientSecret == googleSecret,
          clients.head.oldClientSecret.isEmpty,
        )
      },
    )

  def encryptionTests(env: ExternalOAuthClientRepositorySpec.Env) =
    suite("encryption")(
      test("secrets are properly stored and retrieved") {
        for
          _ <- env.repository.register(googleProvider, googleClientId, googleSecret)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 1,
          clients.head.clientSecret == googleSecret,
        )
      },
      test("different secrets are stored correctly") {
        val secret1 = ClientSecret("secret1".getBytes)
        val secret2 = ClientSecret("secret2".getBytes)
        for
          _ <- env.repository.register(googleProvider, googleClientId, secret1)
          _ <- env.repository.register(githubProvider, githubClientId, secret2)
          clients <- env.repository.listAll()
        yield assertTrue(
          clients.length == 2,
          clients.exists(c => c.provider == googleProvider && c.clientSecret == secret1),
          clients.exists(c => c.provider == githubProvider && c.clientSecret == secret2),
        )
      },
    )

object ExternalOAuthClientRepositorySpec:
  case class Env(
      repository: ExternalOAuthClientRepository,
  )
