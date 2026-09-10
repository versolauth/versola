package versola.oauth.dpop

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

trait DpopProofRepositorySpec extends DatabaseSpecBase[DpopProofRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val ttl = 60.seconds

  def testCases(env: DpopProofRepositorySpec.Env): List[Spec[DpopProofRepositorySpec.Env & Scope, Any]] =
    List(
      test("records a new (jkt, jti) pair and reports it as fresh") {
        for fresh <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
        yield assertTrue(fresh)
      },
      test("reports a replay for the same (jkt, jti) pair recorded twice") {
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
          second <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
        yield assertTrue(first, !second)
      },
      test("treats the same jti under a different jkt as a distinct, fresh pair") {
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
          second <- env.repository.recordIfAbsent("jkt-2", "jti-1", ttl)
        yield assertTrue(first, second)
      },
      test("treats a different jti under the same jkt as a distinct, fresh pair") {
        for
          first <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
          second <- env.repository.recordIfAbsent("jkt-1", "jti-2", ttl)
        yield assertTrue(first, second)
      },
      test("an expired record no longer counts as a replay") {
        for
          _ <- env.repository.recordIfAbsent("jkt-1", "jti-1", 0.seconds)
          _ <- TestClock.adjust(1.second)
          fresh <- env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)
        yield assertTrue(fresh)
      },
      test("concurrent attempts to record the same pair -- only one should see it as fresh") {
        for
          results <- ZIO.collectAllPar(List.fill(10)(env.repository.recordIfAbsent("jkt-1", "jti-1", ttl)))
        yield assertTrue(results.count(identity) == 1)
      },
    )

object DpopProofRepositorySpec:
  case class Env(repository: DpopProofRepository)
