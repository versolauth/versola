package versola.oauth.revocation

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.client.model.TenantId
import versola.util.DatabaseSpecBase
import versola.util.revocation.{Revocation, RevocationCursor, RevocationKey}
import zio.*
import zio.test.*

import java.time.Instant

/** What every [[AuthRevocationRepository]] promises beyond the shared `RevocationRepositorySpec` (which a storage's runner binds
  * to the same table): the cause is stored with the revocation, and the feed an edge pulls serves a tenant's revocations and
  * those of no tenant, resumes after a cursor, and leaves out what has expired. The stored cause, which no method of the trait
  * reads, comes through [[AuthRevocationRepositorySpec.Fixtures]]. */
trait AuthRevocationRepositorySpec extends DatabaseSpecBase[AuthRevocationRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  def testCases(env: AuthRevocationRepositorySpec.Env): List[Spec[AuthRevocationRepositorySpec.Env & Scope, Any]] =
    val repository = env.repository
    List(
      test("stores why the revocation was written") {
        for
          _ <- repository.revoke(
            Revocation(RevocationKey.Jti("token-1"), Instant.now().plusSeconds(3600), None),
            RevocationCause.CodeReplay,
            None,
          )
          cause <- env.fixtures.storedCause(RevocationKey.Jti("token-1"))
        yield assertTrue(cause == Some("code_replay"))
      } @@ TestAspect.withLiveClock,
      test("the feed serves a tenant's rows and the rows of no tenant, and not another tenant's") {
        val expiry = Instant.now().plusSeconds(3600)
        for
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Jti("mine"), expiresAt = expiry, issuedBefore = None),
            RevocationCause.Revoke,
            Some(TenantId("tenant-a")),
          )
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Fam("theirs"), expiresAt = expiry, issuedBefore = None),
            RevocationCause.Revoke,
            Some(TenantId("tenant-b")),
          )
          _ <-
            repository.revoke(Revocation(key = RevocationKey.Sid("session"), expiresAt = expiry, issuedBefore = None), RevocationCause.Logout, None)
          page <- repository.feedSince(RevocationCursor.Beginning, 10, Set(TenantId("tenant-a")))
          nobody <- repository.feedSince(RevocationCursor.Beginning, 10, Set.empty)
        yield assertTrue(
          page.revocations.map(_.key).toSet == Set(RevocationKey.Jti("mine"), RevocationKey.Sid("session")),
          nobody.revocations.map(_.key) == List(RevocationKey.Sid("session")),
        )
      } @@ TestAspect.withLiveClock,
      test("the feed resumes after a cursor and reports a full page") {
        val expiry = Instant.now().plusSeconds(3600)
        for
          _ <- ZIO.foreachDiscard(1 to 3)(n =>
            repository.revoke(Revocation(key = RevocationKey.Sid(s"s$n"), expiresAt = expiry, issuedBefore = None), RevocationCause.Logout, None),
          )
          first <- repository.feedSince(RevocationCursor.Beginning, 2, Set.empty)
          second <- repository.feedSince(first.last.get, 2, Set.empty)
          third <- repository.feedSince(second.last.get, 2, Set.empty)
        yield assertTrue(
          first.revocations.size == 2,
          first.hasMore,
          second.revocations.size == 1,
          !second.hasMore,
          (first.revocations ++ second.revocations).map(_.key).toSet == Set(1, 2, 3).map(n => RevocationKey.Sid(s"s$n")),
          third.revocations.isEmpty,
          third.last.isEmpty,
        )
      } @@ TestAspect.withLiveClock,
      test("the feed leaves out rows that have expired") {
        for
          _ <- repository.revoke(
            Revocation(key = RevocationKey.Sid("old"), expiresAt = Instant.now().minusSeconds(5), issuedBefore = None),
            RevocationCause.Logout,
            None,
          )
          page <- repository.feedSince(RevocationCursor.Beginning, 10, Set.empty)
        yield assertTrue(page.revocations.isEmpty)
      } @@ TestAspect.withLiveClock,
    )

object AuthRevocationRepositorySpec:
  case class Env(repository: AuthRevocationRepository, fixtures: Fixtures)

  /** What a case reads of what the repository wrote and no method of the trait returns: the storage's runner supplies it. */
  trait Fixtures:
    /** The cause recorded with `key`, by the name it is stored under; `None` when the key is not recorded. */
    def storedCause(key: RevocationKey): Task[Option[String]]
