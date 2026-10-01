package versola.oauth.clientauth

import zio.*

import java.time.Instant

/** The replay guard's contract without Postgres: a `(clientId, jti)` pair is fresh exactly
  * once. Real rather than stubbed in the specs that use it, because whether a second
  * presentation is refused is the behaviour under test. */
final class InMemoryClientAssertionRepository(seen: Ref[Set[(String, String)]]) extends ClientAssertionRepository:
  override def recordIfAbsent(clientId: String, jti: String, expiresAt: Instant): Task[Boolean] =
    seen.modify(recorded => (!recorded((clientId, jti)), recorded + ((clientId, jti))))

object InMemoryClientAssertionRepository:
  def make: InMemoryClientAssertionRepository =
    InMemoryClientAssertionRepository(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(Set.empty[(String, String)])))
