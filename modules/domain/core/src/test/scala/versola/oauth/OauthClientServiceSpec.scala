package versola.oauth

import org.apache.commons.codec.digest.Blake3
import versola.oauth.model.*
import versola.security.{MAC, Secret, SecureRandom, SecurityService}
import versola.util.*
import zio.*
import zio.prelude.NonEmptySet
import zio.test.*

import java.nio.charset.StandardCharsets

object OauthClientServiceSpec extends UnitSpecBase:

  // Test data
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val publicClientId = ClientId("public-client-1")
  val nonExistentClientId = ClientId("non-existent")

  // Generate proper 32-byte base64url encoded secrets
  val testSecretBytes = Array.fill(32)(5.toByte)
  val previousSecretBytes = Array.fill(32)(6.toByte)
  val wrongSecretBytes = Array.fill(32)(99.toByte)

  val testClientSecret = ClientSecret(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(testSecretBytes))
  val previousClientSecret = ClientSecret(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(previousSecretBytes))
  val wrongClientSecret = ClientSecret(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(wrongSecretBytes))

  // Test pepper (16 bytes) - base64url encoded
  val pepperBytes = Array.fill(16)(10.toByte)
  val testPepper = Secret.Bytes16(pepperBytes)

  val salt1 = Array.fill(16)(1.toByte)
  val salt2 = Array.fill(16)(2.toByte)
  val newSalt = Array.fill(16)(3.toByte)

  // Compute BLAKE3 MAC with salt||pepper as key
  // Returns: MAC (32 bytes) || salt (16 bytes) = 48 bytes
  def computeTestBlake3MAC(secretBytes: Array[Byte], salt: Array[Byte]): MAC =
    require(salt.length == 16)
    require(pepperBytes.length == 16)
    require(secretBytes.length == 32)
    val macKey = salt ++ pepperBytes // 16 + 16 = 32 bytes
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(macKey)
      .update(secretBytes)
      .doFinalize(mac)
    MAC(mac) // MAC (32 bytes) || salt (16 bytes)

  // Compute MACs that match our test secrets
  val mac1 = computeTestBlake3MAC(testSecretBytes, salt1)
  val secret1 = Secret(mac1 ++ salt1)
  val mac2 = computeTestBlake3MAC(testSecretBytes, salt2)
  val secret2 = Secret(mac2 ++ salt2)
  val previousMac = computeTestBlake3MAC(previousSecretBytes, salt1)
  val previousSecret = Secret(previousMac ++ salt1)
  val newMacWithSalt = Secret(Array.fill(48)(3.toByte))

  val privateClient1 = OAuthClient(
    id = clientId1,
    clientName = "Test Private Client 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set("read", "write"),
    secret = Some(secret1), // MAC for testSecret with salt1
    previousSecret = None,
  )

  val privateClientWithPrevious = OAuthClient(
    id = clientId2,
    clientName = "Test Private Client 2",
    redirectUris = NonEmptySet("https://example2.com/callback"),
    scope = Set("read"),
    secret = Some(secret2),
    previousSecret = Some(previousSecret),
  )

  val publicClient = OAuthClient(
    id = publicClientId,
    clientName = "Test Public Client",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set("read"),
    secret = None,
    previousSecret = None,
  )

  val testClients = Map(
    clientId1 -> privateClient1,
    clientId2 -> privateClientWithPrevious,
    publicClientId -> publicClient,
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
    val securityService = stub[SecurityService]
    securityService.macBlake3.returns { (secret, key) =>
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key)
          .update(secret)
          .doFinalize(mac)
        MAC(mac)
    }
    val service = OAuthClientService.Impl(
      ReloadingCache(cache),
      repository,
      ReloadingCache(scopeCache),
      scopeRepository,
      secureRandom,
      securityService,
      CoreConfig.Security.ClientSecrets(testPepper),
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
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.find(clientId1)
        yield assertTrue(result.contains(privateClient1))
      },
      test("return None when client does not exist") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.find(nonExistentClientId)
        yield assertTrue(result.isEmpty)
      },
      test("return None when cache is empty") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(Map.empty)
          result <- env.service.find(clientId1)
        yield assertTrue(result.isEmpty)
      },
    )

  def verifySecretTests =
    suite("verifySecret")(
      test("return true for public client without secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(publicClientId, None)
        yield assertTrue(result.isDefined)
      },
      test("return false for public client with secret provided") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(publicClientId, Some(testClientSecret))
        yield assertTrue(result.isEmpty)
      },
      test("return false for private client without secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, None)
        yield assertTrue(result.isEmpty)
      },
      test("return false for non-existent client") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(nonExistentClientId, Some(testClientSecret))
        yield assertTrue(result.isEmpty)
      },
      test("return true for private client with correct current secret") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, Some(testClientSecret))
        yield assertTrue(result.isDefined)
      },
      test("return true for private client with correct previous secret when current fails") {
        val env = Env()
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId2, Some(previousClientSecret))
        yield assertTrue(result.isDefined)
      },
      test("return false for private client with wrong secret") {
        val env = Env()
        val wrongMac = MAC(Array.fill(32)(99.toByte)) // Wrong MAC
        for
          _ <- (env.cache.get(using _: Trace)).succeedsWith(testClients)
          result <- env.service.verifySecret(clientId1, Some(wrongClientSecret))
        yield assertTrue(result.isEmpty)
      },
    )

  def registerTests =
    suite("register")(
      test("create private client with generated secret") {
        val env = Env()
        val secretBytes = Array.fill(32)(11.toByte)
        val saltBytes = Array.fill(16)(13.toByte)
        val testMac = MAC.fromBase64Url("jzaMcn2zEvgXA7UoCh86TKMrhSKCZ84LTEG3Xx4F0jc")
        val expectedSecret = Secret(testMac ++ saltBytes)
        val expectedClientSecret =
          ClientSecret(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(secretBytes))

        for
          _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
            case 1 => ZIO.succeed(secretBytes)
            case _ => ZIO.succeed(saltBytes)
          _ <- env.repository.create.succeedsWith(())
          result <- env.service.register(
            clientId1,
            "Test Client",
            NonEmptySet("https://example.com/callback"),
            Set("read", "write"),
          )
          registerCalls = env.repository.create.calls
        yield assertTrue(
          result == ClientSecret(expectedClientSecret),
          env.secureRandom.nextBytes.calls.length == 2, // Once for secret, once for salt
          registerCalls.length == 1,
          registerCalls.head.id == clientId1,
          registerCalls.head.clientName == "Test Client",
          registerCalls.head.redirectUris == NonEmptySet("https://example.com/callback"),
          registerCalls.head.scope == Set("read", "write"),
          registerCalls.head.secret.exists(_.sameElements(testMac ++ saltBytes)),
          registerCalls.head.previousSecret.isEmpty,
        )
      },
    )

  def rotateSecretTests =
    suite("rotateSecret")(
      test("generate new secret and update repository") {
        val env = Env()
        val secretBytes = Array.fill(32)(11.toByte)
        val saltBytes = Array.fill(16)(13.toByte)
        val testMac = MAC.fromBase64Url("jzaMcn2zEvgXA7UoCh86TKMrhSKCZ84LTEG3Xx4F0jc")
        val expectedSecret = Secret(testMac ++ saltBytes)
        val expectedClientSecret =
          ClientSecret(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(secretBytes))

        for
          _ <- env.secureRandom.nextBytes.returnsZIOOnCall:
              case 1 => ZIO.succeed(secretBytes)
              case _ => ZIO.succeed(saltBytes)

          _ <- env.repository.rotateSecret.succeedsWith(())

          result <- env.service.rotateSecret(clientId1)
          rotateCalls = env.repository.rotateSecret.calls
        yield assertTrue(
          result == expectedClientSecret,
          rotateCalls.head._1 == clientId1,
          rotateCalls.head._2.sameElements(expectedSecret),
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
