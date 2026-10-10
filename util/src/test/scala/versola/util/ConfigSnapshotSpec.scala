package versola.util

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets

object ConfigSnapshotSpec extends ZIOSpecDefault:

  private val keyMaterial = Array.fill[Byte](32)(7)
  private val clientsRequest = Request.get(URL.decode("https://central.example/configuration/clients/sync").toOption.get)
  private val scopesRequest = Request.get(URL.decode("https://central.example/configuration/scopes/sync").toOption.get)

  private class InMemoryRepository(records: Ref[Map[String, ConfigSnapshot.Record]], writes: Ref[Int])
      extends ConfigSnapshot.Repository:
    override def find(key: String): Task[Option[ConfigSnapshot.Record]] = records.get.map(_.get(key))
    override def save(record: ConfigSnapshot.Record): Task[Unit] =
      writes.update(_ + 1) *> records.update(_.updated(record.key, record))

  private def repository: UIO[(InMemoryRepository, Ref[Map[String, ConfigSnapshot.Record]], Ref[Int])] =
    for
      records <- Ref.make(Map.empty[String, ConfigSnapshot.Record])
      writes <- Ref.make(0)
    yield (InMemoryRepository(records, writes), records, writes)

  private def fetch(body: String): ZIO[Scope, Throwable, Response] = ZIO.succeed(Response.text(body))

  private def read(snapshot: ConfigSnapshot, request: Request, response: ZIO[Scope, Throwable, Response]): Task[String] =
    ZIO.scoped(snapshot.through(request)(response).flatMap(_.body.asString)) <* settle

  /** The snapshot is written in the background, off the path of the read that produced it. */
  private val settle: UIO[Unit] = ZIO.sleep(100.millis).withClock(Clock.ClockLive)

  private def replay(snapshot: ConfigSnapshot, request: Request): Task[(String, Option[java.time.Instant])] =
    ConfigSnapshot.replay(read(snapshot, request, ZIO.fail(RuntimeException("central is not called while replaying"))))

  private def tamper(records: Ref[Map[String, ConfigSnapshot.Record]], key: String)(
      f: ConfigSnapshot.Record => ConfigSnapshot.Record,
  ): UIO[Unit] =
    records.update(all => all.updated(key, f(all(key))))

  private val clientsKey = "/configuration/clients/sync"

  def spec = suite("ConfigSnapshot")(
    test("a successful body is recorded and answered from the record while replaying") {
      for
        (repo, _, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        live <- read(snapshot, clientsRequest, fetch("""{"clients":[]}"""))
        (replayed, savedAt) <- replay(snapshot, clientsRequest)
      yield assertTrue(live == """{"clients":[]}""", replayed == live, savedAt.isDefined)
    },
    test("a central URL built without a leading slash records under the same key") {
      val built = Request.get(URL.decode("https://central.example").toOption.get / "configuration" / "clients" / "sync")
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, built, fetch("""{"clients":[]}"""))
        keys <- records.get.map(_.keySet)
      yield assertTrue(keys == Set(clientsKey))
    },
    test("an unsuccessful response is passed through and not recorded") {
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        response <- ZIO.scoped(snapshot.through(clientsRequest)(ZIO.succeed(Response.status(Status.Unauthorized))).map(_.status))
        stored <- records.get
      yield assertTrue(response == Status.Unauthorized, stored.isEmpty)
    },
    test("without a record the replay fails as missing") {
      for
        (repo, _, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        exit <- replay(snapshot, clientsRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Missing(clientsKey)))
    },
    test("a tampered body is rejected") {
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, clientsRequest, fetch("""{"clients":[]}"""))
        _ <- tamper(records, clientsKey)(_.copy(body = """{"clients":[{"id":"evil"}]}""".getBytes(StandardCharsets.UTF_8)))
        exit <- replay(snapshot, clientsRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Rejected(clientsKey)))
    },
    test("a truncated body is rejected") {
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, clientsRequest, fetch("""{"clients":[]}"""))
        _ <- tamper(records, clientsKey)(r => r.copy(body = r.body.dropRight(3)))
        exit <- replay(snapshot, clientsRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Rejected(clientsKey)))
    },
    test("a record that claims to be younger than it is is rejected") {
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, clientsRequest, fetch("""{"clients":[]}"""))
        _ <- tamper(records, clientsKey)(r => r.copy(savedAt = r.savedAt.plusSeconds(3600)))
        exit <- replay(snapshot, clientsRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Rejected(clientsKey)))
    },
    test("a record written under another key is rejected") {
      for
        (repo, _, _) <- repository
        otherSigner <- ConfigSnapshot.make(repo, Array.fill[Byte](32)(8))
        _ <- read(otherSigner, clientsRequest, fetch("""{"clients":[]}"""))
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        exit <- replay(snapshot, clientsRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Rejected(clientsKey)))
    },
    test("a record moved to another endpoint is rejected") {
      for
        (repo, records, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, clientsRequest, fetch("""{"clients":[]}"""))
        scopesKey = "/configuration/scopes/sync"
        _ <- records.update(all => all.updated(scopesKey, all(clientsKey).copy(key = scopesKey)))
        exit <- replay(snapshot, scopesRequest).exit
      yield assertTrue(exit == Exit.fail(ConfigSnapshot.Rejected(scopesKey)))
    },
    test("an unchanged body is written again only once the confirmation interval has passed") {
      for
        (repo, _, writes) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        _ <- read(snapshot, clientsRequest, fetch("same"))
        _ <- read(snapshot, clientsRequest, fetch("same"))
        afterRepeat <- writes.get
        _ <- read(snapshot, clientsRequest, fetch("changed"))
        afterChange <- writes.get
        _ <- TestClock.adjust(11.minutes)
        _ <- read(snapshot, clientsRequest, fetch("changed"))
        afterInterval <- writes.get
      yield assertTrue(afterRepeat == 1, afterChange == 2, afterInterval == 3)
    },
    test("once the snapshot is served, a failed request reports the source as down until one succeeds") {
      for
        (repo, _, _) <- repository
        snapshot <- ConfigSnapshot.make(repo, keyMaterial)
        down = ZIO.fail(java.net.ConnectException("refused"))
        _ <- read(snapshot, clientsRequest, fetch("saved"))
        beforeReplay <- read(snapshot, clientsRequest, down).exit
        _ <- replay(snapshot, clientsRequest)
        whileServing <- read(snapshot, clientsRequest, down).exit
        _ <- read(snapshot, clientsRequest, fetch("live again"))
        afterRecovery <- read(snapshot, clientsRequest, down).exit
      yield assertTrue(
        beforeReplay.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[java.net.ConnectException]),
        whileServing.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[ConfigSnapshot.SourceDown]),
        afterRecovery.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[java.net.ConnectException]),
      )
    },
    test("a failed write does not fail the sync") {
      val failing = new ConfigSnapshot.Repository:
        override def find(key: String): Task[Option[ConfigSnapshot.Record]] = ZIO.none
        override def save(record: ConfigSnapshot.Record): Task[Unit] = ZIO.fail(RuntimeException("database down"))
      for
        snapshot <- ConfigSnapshot.make(failing, keyMaterial)
        body <- read(snapshot, clientsRequest, fetch("live"))
      yield assertTrue(body == "live")
    },
  ) @@ TestAspect.silentLogging
