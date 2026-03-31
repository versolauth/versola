package versola.oauth.client

import org.apache.commons.codec.digest.Blake3
import versola.oauth.client.model.{Claim, ClaimRecord, ClientId, ClientsWithPepper, OAuthClientRecord, ScopeRecord, ScopeToken}
import versola.util.*
import zio.*
import zio.durationInt
import zio.prelude.{EqualOps, NonEmptySet}
import zio.test.*

object OAuthClientServiceSpec extends UnitSpecBase:
  val clientId1 = ClientId("test-client-1")
  val clientId2 = ClientId("test-client-2")
  val publicClientId = ClientId("public-client-1")
  val pepperBytes = Array.fill(16)(10.toByte)
  val testPepper = Secret(pepperBytes)
  val testSecret = Secret(Array.fill(32)(5.toByte))
  val previousClientSecret = Secret(Array.fill(32)(6.toByte))
  val wrongClientSecret = Secret(Array.fill(32)(99.toByte))
  val salt1 = Array.fill(16)(1.toByte)
  val salt2 = Array.fill(16)(2.toByte)

  private def stored(secret: Secret, salt: Array[Byte]) =
    val mac = Array.ofDim[Byte](32)
    Blake3.initKeyedHash(salt ++ pepperBytes).update(secret).doFinalize(mac)
    Secret(MAC(mac) ++ salt)

  val privateClient1 = OAuthClientRecord(
    id = clientId1,
    clientName = "Private 1",
    redirectUris = NonEmptySet("https://example.com/callback"),
    scope = Set(ScopeToken("read"), ScopeToken("write")),
    externalAudience = Nil,
    secret = Some(stored(secret = testSecret, salt = salt1)),
    previousSecret = None,
    accessTokenTtl = 10.minutes,
  )
  val privateClient2 = OAuthClientRecord(
    id = clientId2,
    clientName = "Private 2",
    redirectUris = NonEmptySet("https://example2.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = Some(stored(secret = testSecret, salt = salt2)),
    previousSecret = Some(stored(secret = previousClientSecret, salt = salt1)),
    accessTokenTtl = 10.minutes,
  )
  val publicClient = OAuthClientRecord(
    id = publicClientId,
    clientName = "Public",
    redirectUris = NonEmptySet("https://public.example.com/callback"),
    scope = Set(ScopeToken("read")),
    externalAudience = Nil,
    secret = None,
    previousSecret = None,
    accessTokenTtl = 10.minutes,
  )
  val testClients = Map(clientId1 -> privateClient1, clientId2 -> privateClient2, publicClientId -> publicClient)
  val testScopes = Vector(
    ScopeRecord(
      scope = ScopeToken("read"),
      claims = Vector(ClaimRecord(claim = Claim("sub")), ClaimRecord(claim = Claim("name"))),
    ),
    ScopeRecord(
      scope = ScopeToken("write"),
      claims = Vector(ClaimRecord(claim = Claim("email"))),
    ),
  )

  final class Env(clientCache: ReloadingCache[ClientsWithPepper], scopeCache: ReloadingCache[Vector[ScopeRecord]]):
    val clientSync = stub[OAuthClientSyncClient]
    val scopeSync = stub[OAuthScopeSyncClient]
    val security = stub[SecurityService]
    security.mac.returns { (secret, key) =>
      ZIO.succeed:
        val mac = Array.ofDim[Byte](32)
        Blake3.initKeyedHash(key).update(secret).doFinalize(mac)
        MAC(mac)
    }
    val service: OAuthConfigurationService =
      OAuthConfigurationService.Impl(clientCache, clientSync, scopeCache, scopeSync, security)

  private def makeEnv(clients: Map[ClientId, OAuthClientRecord] = testClients, scopes: Vector[ScopeRecord] = testScopes) =
    for
      clientRef <- Ref.make(ClientsWithPepper(clients = clients, pepper = testPepper))
      scopeRef <- Ref.make(scopes)
    yield Env(clientCache = ReloadingCache(clientRef), scopeCache = ReloadingCache(scopeRef))

  val spec = suite("OAuthConfigurationService")(
    test("find returns existing client") {
      for
        env <- makeEnv()
        result <- env.service.find(clientId1)
      yield assertTrue(result === Some(privateClient1))
    },
    test("find returns None for missing client") {
      for
        env <- makeEnv()
        result <- env.service.find(ClientId("missing"))
      yield assertTrue(result.isEmpty)
    },
    test("verifySecret accepts public client only without secret") {
      for
        env <- makeEnv()
        ok <- env.service.verifySecret(publicClientId, None)
        bad <- env.service.verifySecret(publicClientId, Some(testSecret))
      yield assertTrue(ok === Some(publicClient), bad.isEmpty)
    },
    test("verifySecret accepts current and previous private secrets") {
      for
        env <- makeEnv()
        current <- env.service.verifySecret(clientId1, Some(testSecret))
        previous <- env.service.verifySecret(clientId2, Some(previousClientSecret))
      yield assertTrue(current === Some(privateClient1), previous === Some(privateClient2))
    },
    test("verifySecret rejects wrong or missing private secret") {
      for
        env <- makeEnv()
        wrong <- env.service.verifySecret(clientId1, Some(wrongClientSecret))
        missing <- env.service.verifySecret(clientId1, None)
      yield assertTrue(wrong.isEmpty, missing.isEmpty)
    },
    test("getScopes returns cached scope records") {
      for
        env <- makeEnv()
        result <- env.service.getScopes
      yield assertTrue(result == testScopes)
    },
  )
