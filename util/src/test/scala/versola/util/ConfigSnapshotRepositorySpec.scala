package versola.util

import com.augustnagro.magnum.magzio.TransactorZIO
import zio.http.{Request, Response, URL}
import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** What every [[ConfigSnapshot.Repository]] implementation has to do, whatever it stores the
  * records in.
  */
trait ConfigSnapshotRepositorySpec extends DatabaseSpecBase[ConfigSnapshotRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  private val savedAt = Instant.parse("2026-10-09T12:00:00.123Z")
  private val clientsKey = "/configuration/clients/sync"

  private def record(body: String, at: Instant) =
    ConfigSnapshot.Record(clientsKey, body.getBytes(StandardCharsets.UTF_8), at, Array[Byte](1, 2, 3))

  def testCases(env: ConfigSnapshotRepositorySpec.Env): List[Spec[ConfigSnapshotRepositorySpec.Env & Scope, Any]] =
    List(
      test("a saved record is found as it was saved") {
        for
          _ <- env.repository.save(record("first", savedAt))
          found <- env.repository.find(clientsKey)
          missing <- env.repository.find("/configuration/scopes/sync")
        yield assertTrue(
          found.map(r => String(r.body, StandardCharsets.UTF_8)).contains("first"),
          found.map(_.savedAt).contains(savedAt),
          found.exists(_.mac.sameElements(Array[Byte](1, 2, 3))),
          missing.isEmpty,
        )
      },
      test("a newer record replaces the stored one, an older one does not") {
        for
          _ <- env.repository.save(record("first", savedAt))
          _ <- env.repository.save(record("second", savedAt.plusSeconds(60)))
          _ <- env.repository.save(record("stale", savedAt.plusSeconds(30)))
          found <- env.repository.find(clientsKey)
        yield assertTrue(found.map(r => String(r.body, StandardCharsets.UTF_8)).contains("second"))
      },
      test("a body recorded through the snapshot is verified and replayed from the repository") {
        val request = Request.get(URL.decode(s"https://central.example$clientsKey").toOption.get)
        for
          snapshot <- ConfigSnapshot.make(env.repository, Array.fill[Byte](32)(7))
          _ <- ZIO.scoped(snapshot.through(request)(ZIO.succeed(Response.text("""{"clients":[]}"""))))
          // A fresh instance remembers nothing it saved, which is the state of a service after
          // a restart: what is replayed has come back from the repository.
          restarted <- ConfigSnapshot.make(env.repository, Array.fill[Byte](32)(7))
          replayed <- ConfigSnapshot.replay(
            ZIO.scoped(restarted.through(request)(ZIO.fail(RuntimeException("central is down"))).flatMap(_.body.asString)),
          )
        yield assertTrue(replayed._1 == """{"clients":[]}""", replayed._2.isDefined)
      },
    )

object ConfigSnapshotRepositorySpec:
  case class Env(repository: ConfigSnapshot.Repository)
