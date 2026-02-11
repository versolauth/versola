package versola.edge.model

import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.{MAC, Secret}
import zio.test.*

import java.time.Instant

/**
 * Compilation test to verify edge models are properly defined
 */
object EdgeModelCompilationTest extends ZIOSpecDefault:
  def spec = suite("Edge Model Compilation")(
    test("EdgeSessionId can be created") {
      val sessionId = EdgeSessionId(Array[Byte](1, 2, 3))
      assertTrue(sessionId != null)
    },
    test("EdgeSession can be created") {
      val now = Instant.now()
      val session = EdgeSession(
        clientId = ClientId("test-client"),
        userIdentifier = "user123",
        state = Some("state123"),
        createdAt = now,
        expiresAt = now.plusSeconds(3600),
      )
      assertTrue(session.clientId == ClientId("test-client"))
    },
    test("EdgeCredentials can be created") {
      val now = Instant.now()
      val credentials = EdgeCredentials(
        clientId = ClientId("test-client"),
        clientSecretHash = Secret(Array[Byte](1, 2, 3)),
        providerUrl = "https://provider.example.com",
        scopes = Set(ScopeToken("read"), ScopeToken("write")),
        createdAt = now,
      )
      assertTrue(credentials.clientId == ClientId("test-client"))
    },
    test("EdgeTokenRecord can be created") {
      val now = Instant.now()
      val sessionId = MAC(Array[Byte](1, 2, 3))
      val tokenRecord = EdgeTokenRecord(
        sessionId = sessionId,
        clientId = ClientId("test-client"),
        accessTokenHash = MAC(Array[Byte](4, 5, 6)),
        refreshTokenHash = Some(MAC(Array[Byte](7, 8, 9))),
        scope = Set(ScopeToken("read")),
        issuedAt = now,
        expiresAt = now.plusSeconds(3600),
      )
      assertTrue(tokenRecord.clientId == ClientId("test-client"))
    },
    test("WithTtl can be created") {
      import zio.Duration
      val ttl = WithTtl(value = "test", ttl = Duration.fromSeconds(3600))
      assertTrue(ttl.value == "test")
    },
  )

