package versola.edge.login

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.model.{CodeVerifier, PresetId, State}
import versola.util.DatabaseSpecBase
import zio.*
import zio.test.*

/** Conformance suite for any [[LoginRepository]] implementation. A backend module extends
  * this and supplies the wiring -- see `PostgresLoginRepositorySpec` in `edge-postgres-impl`
  * for the one binding that exists today.
  */
trait LoginRepositorySpec extends DatabaseSpecBase[LoginRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val state1 = State("state-1")
  private val state2 = State("state-2")

  private def record(state: State, codeVerifier: String, preset: String) =
    LoginRecord(codeVerifier = CodeVerifier(codeVerifier), presetId = PresetId(preset), state = state)

  override def testCases(env: LoginRepositorySpec.Env) =
    List(
      test("findByState returns None for an unknown state") {
        for found <- env.repository.findByState(state1)
        yield assertTrue(found.isEmpty)
      },
      test("create stores a record retrievable by state before it expires") {
        val r = record(state1, "verifier-1", "preset-1")
        for
          _ <- env.repository.create(r, ttl = 1.hour)
          found <- env.repository.findByState(state1)
        yield assertTrue(found == Some(r))
      },
      test("create upserts on state, replacing codeVerifier and presetId") {
        val first = record(state1, "verifier-1", "preset-1")
        val second = record(state1, "verifier-2", "preset-2")
        for
          _ <- env.repository.create(first, ttl = 1.hour)
          _ <- env.repository.create(second, ttl = 1.hour)
          found <- env.repository.findByState(state1)
        yield assertTrue(found == Some(second))
      },
      test("findByState excludes logins that have expired") {
        val r = record(state1, "verifier-1", "preset-1")
        for
          _ <- env.repository.create(r, ttl = 1.minute)
          before <- env.repository.findByState(state1)
          _ <- TestClock.adjust(2.minutes)
          after <- env.repository.findByState(state1)
        yield assertTrue(before.isDefined, after.isEmpty)
      },
      test("deleteByState removes the login") {
        val r = record(state1, "verifier-1", "preset-1")
        for
          _ <- env.repository.create(r, ttl = 1.hour)
          _ <- env.repository.deleteByState(state1)
          found <- env.repository.findByState(state1)
        yield assertTrue(found.isEmpty)
      },
      test("deleteByState leaves other states untouched") {
        val r1 = record(state1, "verifier-1", "preset-1")
        val r2 = record(state2, "verifier-2", "preset-2")
        for
          _ <- env.repository.create(r1, ttl = 1.hour)
          _ <- env.repository.create(r2, ttl = 1.hour)
          _ <- env.repository.deleteByState(state1)
          found <- env.repository.findByState(state2)
        yield assertTrue(found == Some(r2))
      },
    )

object LoginRepositorySpec:
  case class Env(repository: LoginRepository)
