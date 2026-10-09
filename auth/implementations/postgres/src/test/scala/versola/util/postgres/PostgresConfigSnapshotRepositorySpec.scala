package versola.util.postgres

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import versola.util.{ConfigSnapshot, DatabaseSpecBase}
import zio.http.{Request, Response, URL}
import zio.test.*
import zio.{Scope, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.time.Instant

object PostgresConfigSnapshotRepositorySpec
    extends PostgresSpec,
      DatabaseSpecBase[PostgresConfigSnapshotRepositorySpec.Env]:

  case class Env(repository: PostgresConfigSnapshotRepository)

  private val savedAt = Instant.parse("2026-10-09T12:00:00.123Z")

  private def record(body: String, at: Instant) =
    ConfigSnapshot.Record("/configuration/clients/sync", body.getBytes(StandardCharsets.UTF_8), at, Array[Byte](1, 2, 3))

  override lazy val environment =
    ZLayer:
      for xa <- ZIO.service[TransactorZIO]
      yield Env(PostgresConfigSnapshotRepository(xa, "auth_config_snapshots"))

  override def beforeEach(env: Env) =
    ZIO.serviceWithZIO[TransactorZIO](_.connect(sql"TRUNCATE TABLE auth_config_snapshots".update.run()))

  override def testCases(env: Env): List[Spec[Env & Scope, Any]] =
    List(
      test("a saved record is found as it was saved") {
        for
          _ <- env.repository.save(record("first", savedAt))
          found <- env.repository.find("/configuration/clients/sync")
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
          found <- env.repository.find("/configuration/clients/sync")
        yield assertTrue(found.map(r => String(r.body, StandardCharsets.UTF_8)).contains("second"))
      },
      test("a body recorded through the snapshot is verified and replayed from the table") {
        val request = Request.get(URL.decode("https://central.example/configuration/clients/sync").toOption.get)
        for
          snapshot <- ConfigSnapshot.make(env.repository, Array.fill[Byte](32)(7))
          _ <- ZIO.scoped(snapshot.through(request)(ZIO.succeed(Response.text("""{"clients":[]}"""))))
          // A fresh instance remembers nothing it saved, which is the state of a service after
          // a restart: what is replayed has come back from the table.
          restarted <- ConfigSnapshot.make(env.repository, Array.fill[Byte](32)(7))
          replayed <- ConfigSnapshot.replay(
            ZIO.scoped(restarted.through(request)(ZIO.fail(RuntimeException("central is down"))).flatMap(_.body.asString)),
          )
        yield assertTrue(replayed._1 == """{"clients":[]}""", replayed._2.isDefined)
      },
    )
