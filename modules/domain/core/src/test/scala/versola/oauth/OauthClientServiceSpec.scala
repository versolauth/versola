package versola.oauth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import versola.oauth.model.*
import versola.util.*
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

object OauthClientServiceSpec extends UnitSpecBase:

  // Test data
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val clientId3 = ClientId("public-client-1")
  val nonExistentClientId = ClientId("non-existent")

  val testSecret = ClientSecret("test-secret-123")
  val previousSecret = ClientSecret("previous-secret-456")
  val wrongSecret = ClientSecret("wrong-secret")

  val salt1 = Argon2Salt(Array.fill(16)(1.toByte))
  val salt2 = Argon2Salt(Array.fill(16)(2.toByte))
  val newSalt = Argon2Salt(Array.fill(16)(3.toByte))

  // Compute actual Argon2 hashes for test secrets
  def computeTestArgon2Hash(secret: ClientSecret, salt: Argon2Salt): Argon2Hash =
    val secretBytes = secret.getBytes
    val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withIterations(2)
      .withMemoryAsKB(19 * 1024) // 19 MB
      .withParallelism(1)
      .withSalt(salt)
      .build()

    val generator = new Argon2BytesGenerator()
    generator.init(params)

    val hash = new Array[Byte](32)
    generator.generateBytes(secretBytes, hash)
    Argon2Hash(hash)

  // Compute hashes that match our test secrets
  val hash1 = computeTestArgon2Hash(testSecret, salt1)  // hash for testSecret with salt1
  val hash2 = computeTestArgon2Hash(testSecret, salt2)  // hash for testSecret with salt2
  val previousHash = computeTestArgon2Hash(previousSecret, salt1)  // hash for previousSecret with salt1
  val newHash = Argon2Hash(Array.fill(32)(3.toByte))

  val privateClient1 = OAuthClient(
    id = clientId1,
    clientName = "Test Private Client 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set("read", "write"),
    secretHash = Some(hash1),  // hash for testSecret with salt1
    secretSalt = Some(salt1),
    previousSecretHash = None,
    previousSecretSalt = None,
  )

  val privateClientWithPrevious = OAuthClient(
    id = clientId2,
    clientName = "Test Private Client 2",
    redirectUris = NonEmptySet("https://example2.com/callback"),
    scope = Set("read"),
    secretHash = Some(hash2),  // hash for testSecret with salt2
    secretSalt = Some(salt2),
    previousSecretHash = Some(previousHash),  // hash for previousSecret with salt1
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

  val testClients = Map(
    clientId1 -> privateClient1,
    clientId2 -> privateClientWithPrevious,
    clientId3 -> publicClient,
  )

  // Test scope data
  val scopeName1 = ScopeToken("read")
  val scopeName2 = ScopeToken("write")
  val scopeName3 = ScopeToken("admin")

  val scope1 = versola.oauth.model.Scope(
    claims = Set(Claim("sub"), Claim("name"), Claim("email")),
    description = ScopeDescription("Read access to user data"),
  )

  val scope2 = versola.oauth.model.Scope(
    claims = Set(Claim("sub")),
    description = ScopeDescription("Write access to user data"),
  )

  val scope3 = versola.oauth.model.Scope(
    claims = Set(Claim("sub"), Claim("role")),
    description = ScopeDescription("Administrative access"),
  )

  val testScopes = Map(
    scopeName1 -> scope1,
    scopeName2 -> scope2,
    scopeName3 -> scope3,
  )

  class Env:
    val cache = stub[Ref[Map[ClientId, OAuthClient]]]
    val repository = stub[OAuthClientRepository]
    val scopeCache = stub[Ref[Map[ScopeToken, versola.oauth.model.Scope]]]
    val scopeRepository = stub[OAuthScopeRepository]
    val secureRandom = stub[SecureRandom]
    val service = OauthClientService.Impl(
      ReloadingCache(cache),
      repository,
      ReloadingCache(scopeCache),
      scopeRepository,
      secureRandom
    )

  val spec = suite("OauthClientService")(
    getAllTests,
    findTests,
    verifySecretTests,
    registerTests,
    rotateSecretTests,
    deleteClientTests,
    scopeManagementTests,
  )

  def getAllTests =
    suite("getAll")(
      test("return all clients from db") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(testClients)
          result <- env.service.getAll
        yield assertTrue(result == testClients)
      },
      test("return empty map when db is empty") {
        val env = Env()
        for
          _ <- env.repository.getAll.succeedsWith(Map.empty)
          result <- env.service.getAll
        yield assertTrue(result.isEmpty)
      },
    )

  def findTests =
    suite("find")(
      test("return Some when client exists") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.find(clientId1)
        yield assertTrue(result.contains(privateClient1))
      },
      test("return None when client does not exist") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.find(nonExistentClientId)
        yield assertTrue(result.isEmpty)
      },
      test("return None when cache is empty") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(Map.empty)
          result <- env.service.find(clientId1)
        yield assertTrue(result.isEmpty)
      },
    )

  def verifySecretTests =
    suite("verifySecret")(
      test("return true for public client without secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId3, None)
        yield assertTrue(result)
      },
      test("return false for public client with secret provided") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId3, Some(testSecret))
        yield assertTrue(!result)
      },
      test("return false for private client without secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, None)
        yield assertTrue(!result)
      },
      test("return false for non-existent client") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(nonExistentClientId, Some(testSecret))
        yield assertTrue(!result)
      },
      test("return true for private client with correct current secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, Some(testSecret))
        yield assertTrue(result)
      },
      test("return true for private client with correct previous secret when current fails") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId2, Some(previousSecret))
        yield assertTrue(result)
      },
      test("return false for private client with wrong secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using
            _: Trace,
          )).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, Some(wrongSecret))
        yield assertTrue(!result)
      },

    )

  def registerTests =
    suite("register")(
      test("create private client with generated secret") {
        val env = Env()
        for
          _ <- env.secureRandom.nextHex.succeedsWith("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
          _ <- env.secureRandom.nextBytes.succeedsWith(Array.fill(16)(5.toByte))
          _ <- env.repository.create.succeedsWith(())
          result <- env.service.register(
            clientId1,
            "Test Client",
            NonEmptySet("https://example.com/callback"),
            Set("read", "write"),
          )
          registerCalls = env.repository.create.calls
        yield assertTrue(
          result == ClientSecret("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"),
          env.secureRandom.nextHex.calls.length == 1,
          env.secureRandom.nextBytes.calls.length == 1,
          registerCalls.length == 1,
          registerCalls.head.id == clientId1,
          registerCalls.head.clientName == "Test Client",
          registerCalls.head.redirectUris == NonEmptySet("https://example.com/callback"),
          registerCalls.head.scope == Set("read", "write"),
          registerCalls.head.secretHash.isDefined,
          registerCalls.head.secretSalt.isDefined,
          registerCalls.head.previousSecretHash.isEmpty,
          registerCalls.head.previousSecretSalt.isEmpty,
        )
      },
    )

  def rotateSecretTests =
    suite("rotateSecret")(
      test("generate new secret and update repository") {
        val env = Env()
        val newSecretBytes = Array.fill(16)(7.toByte)
        for
          _ <- env.secureRandom.nextHex.succeedsWith("newfedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321")
          _ <- env.secureRandom.nextBytes.succeedsWith(newSecretBytes)
          _ <- env.repository.rotateSecret.succeedsWith(())
          result <- env.service.rotateSecret(clientId1)
          rotateCalls = env.repository.rotateSecret.calls
        yield assertTrue(
          result == ClientSecret("newfedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"),
          env.secureRandom.nextHex.calls.length == 1,
          env.secureRandom.nextBytes.calls.length == 1,
          rotateCalls.length == 1,
          rotateCalls.head._1 == clientId1,
          // Note: We can't easily verify the exact hash/salt values due to Argon2 computation
          // but we can verify the method was called with the right client ID
        )
      },
    )

  def deleteClientTests =
    suite("deleteClients")(
      test("delete single client successfully") {
        val env = Env()
        for
          _ <- env.repository.delete.succeedsWith(())
          _ <- env.service.deleteClients(Vector(clientId1))
          deleteCalls = env.repository.delete.calls
        yield assertTrue(
          deleteCalls.length == 1,
          deleteCalls.head == Vector(clientId1),
        )
      },
      test("delete multiple clients successfully") {
        val env = Env()
        for
          _ <- env.repository.delete.succeedsWith(())
          _ <- env.service.deleteClients(Vector(clientId1, clientId2))
          deleteCalls = env.repository.delete.calls
        yield assertTrue(
          deleteCalls.length == 1,
          deleteCalls.head == Vector(clientId1, clientId2),
        )
      },
    )

  def scopeManagementTests =
    suite("scope management")(
      suite("getAllScopes")(
        test("return all scopes from db") {
          val env = Env()
          for
            _ <- env.scopeRepository.getAll.succeedsWith(testScopes)
            result <- env.service.getAllScopes
          yield assertTrue(result == testScopes)
        },
        test("return empty map when no data in db") {
          val env = Env()
          for
            _ <- env.scopeRepository.getAll.succeedsWith(Map.empty)
            result <- env.service.getAllScopes
          yield assertTrue(result.isEmpty)
        },
      ),
      suite("getAllScopesCached")(
        test("return cached scopes successfully") {
          val env = Env()
          for
            _ <- (env.scopeCache.get(using
              _: Trace,
            )).succeedsWith(testScopes)
            result <- env.service.getAllScopesCached
          yield assertTrue(result == testScopes)
        },
        test("return empty map when cache is empty") {
          val env = Env()
          for
            _ <- (env.scopeCache.get(using
              _: Trace,
            )).succeedsWith(Map.empty)
            result <- env.service.getAllScopesCached
          yield assertTrue(result.isEmpty)
        },
      ),
      suite("registerScopes")(
        test("register multiple scopes successfully") {
          val env = Env()
          val scopesToRegister = Vector(
            (scopeName1, scope1),
            (scopeName2, scope2),
          )
          for
            _ <- env.scopeRepository.createOrUpdate.succeedsWith(())
            _ <- env.service.registerScopes(scopesToRegister)
            createCalls = env.scopeRepository.createOrUpdate.calls
          yield assertTrue(
            createCalls.length == 1,
            createCalls.head.length == 2,
            createCalls.head.map(_.name).toSet == Set(scopeName1, scopeName2),
          )
        },
      ),
      suite("deleteScopes")(
        test("delete multiple scopes successfully") {
          val env = Env()
          val scopesToDelete = Vector(scopeName1, scopeName3)
          for
            _ <- env.scopeRepository.delete.succeedsWith(())
            _ <- env.service.deleteScopes(scopesToDelete)
            deleteCalls = env.scopeRepository.delete.calls
          yield assertTrue(
            deleteCalls.length == 1,
            deleteCalls.head == scopesToDelete,
          )
        },
      ),
    )
