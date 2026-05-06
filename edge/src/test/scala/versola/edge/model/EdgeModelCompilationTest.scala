package versola.edge.model

import versola.util.MAC
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
    test("EdgeSession can be created with merged token fields") {
      val now = Instant.now()
      val session = EdgeSession(
        clientId = ClientId("test-client"),
        state = Some("state123"),
        accessTokenEncrypted = "encrypted-access-token",
        refreshTokenEncrypted = Some("encrypted-refresh-token"),
        tokenExpiresAt = now.plusSeconds(3600),
        scope = Set(ScopeToken("read")),
        createdAt = now,
        sessionExpiresAt = now.plusSeconds(86400),
      )
      assertTrue(session.clientId == ClientId("test-client"))
      assertTrue(session.accessTokenEncrypted == "encrypted-access-token")
    },
    test("WithTtl can be created") {
      import zio.Duration
      val ttl = WithTtl(value = "test", ttl = Duration.fromSeconds(3600))
      assertTrue(ttl.value == "test")
    },
  )

