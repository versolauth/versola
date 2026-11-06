package versola.edge.revocation

import org.scalamock.stubs.Stub
import versola.edge.EdgeConfig
import versola.edge.model.EdgeId
import versola.edge.revocation.RevocationFeedClient.Page
import versola.util.{Secret, UnitSpecBase}
import versola.util.revocation.{Revocation, RevocationConfig, RevocationCursor, RevocationKey}
import zio.*
import zio.http.URL
import zio.test.*

import java.security.KeyPairGenerator
import java.time.Instant

/** The puller against stubs of the feed and of the stores, on a `TestClock`: what a cycle does, in which order, and
  * what it leaves behind when something fails. The lease itself is the repository's to promise
  * (`RevocationCursorRepositorySpec`), and the call to auth is `RevocationFeedClientHandshakeSpec`'s.
  */
object RevocationFeedPullerSpec extends UnitSpecBase:

  private val source = "https://auth-mtls.example.com"
  private val pod = "pod-a"

  private val feed = EdgeConfig.RevocationFeed(
    enabled = true,
    pollInterval = 1.second,
    lease = 5.seconds,
    pageSize = 2,
    timeout = 3.seconds,
  )
  private val cache = RevocationConfig(reloadInterval = 600.seconds, overlap = 30.seconds)

  private def at(seconds: Long, key: String = "") = RevocationCursor(Instant.ofEpochSecond(seconds), key)

  private def sid(id: String) = RevocationKey.Sid(id)
  private def revocation(id: String) = Revocation(sid(id), Instant.ofEpochSecond(100_000), None)

  private class Env:
    val cursors: Stub[RevocationCursorRepository] = stub[RevocationCursorRepository]
    val client: Stub[RevocationFeedClient] = stub[RevocationFeedClient]
    val revocations: Stub[EdgeRevocationRepository] = stub[EdgeRevocationRepository]

    /** What happened, in the order it happened, across the three stores. */
    val log: Ref[List[String]] = Unsafe.unsafe { u ?=> Ref.unsafe.make(List.empty[String])(using u) }

    def record(entry: String): UIO[Unit] = log.update(_ :+ entry)

    /** A pod that holds the lease at `stored` and keeps it; `advance`, `renew` and `release` say so in the log. */
    def holding(stored: String): UIO[Unit] =
      cursors.acquire.succeedsWith(Some(stored)) *>
        cursors.advance.returnsZIO((_, _, cursor, _) => record(s"advance:$cursor").as(true)) *>
        cursors.renew.returnsZIO((_, _, _) => record("renew").as(true)) *>
        cursors.release.returnsZIO((_, _) => record("release")) *>
        revocations.revokeAll.returnsZIO(list => record(s"upsert:${list.map(_.key.encoded).mkString(",")}"))

    def puller: UIO[RevocationFeedPuller.Impl] =
      RevocationFeedPuller.make(source, pod, feed, cache, cursors, client, revocations)

  private def pages(byAfter: Map[String, Page]): ((String, Int)) => Task[Page] =
    (after, _) => ZIO.succeed(byAfter.getOrElse(after, Page(Nil, after)))

  val spec = suite("RevocationFeedPuller")(
    suite("tick")(
      test("reads nothing when another pod holds the lease") {
        val env = new Env
        for
          _ <- env.cursors.acquire.succeedsWith(None)
          puller <- env.puller
          _ <- puller.tick
        yield assertTrue(
          env.client.fetch.calls.isEmpty,
          env.revocations.revokeAll.calls.isEmpty,
          env.cursors.release.calls.isEmpty,
        )
      },
      test("drains pages until a short one, writing each before moving the cursor past it") {
        val env = new Env
        val first = at(1010, "sid:b")
        val second = at(1020, "sid:c")
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.returnsZIO(pages(Map(
            at(970).encoded -> Page(List(revocation("a"), revocation("b")), first.encoded),
            first.encoded -> Page(List(revocation("c")), second.encoded),
          )))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(
          // the cycle begins `overlap` behind the stored cursor, then goes on from what each page returned
          env.client.fetch.calls == List((at(970).encoded, 2), (first.encoded, 2)),
          log == List(
            "upsert:sid:a,sid:b",
            s"advance:${first.encoded}",
            "upsert:sid:c",
            s"advance:${second.encoded}",
            "release",
          ),
        )
      },
      test("an empty page leaves the stored cursor where it was and gives the lease back") {
        val env = new Env
        val stored = at(1000, "sid:a")
        for
          _ <- env.holding(stored.encoded)
          // auth echoes the cursor it was given when there is nothing after it
          _ <- env.client.fetch.returnsZIO(pages(Map.empty))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(log == List("upsert:", "renew", "release"))
      },
      test("a page that ends behind the stored cursor does not move it back") {
        val env = new Env
        val stored = at(1000, "sid:z")
        for
          _ <- env.holding(stored.encoded)
          _ <- env.client.fetch.returnsZIO(pages(Map(at(970).encoded -> Page(List(revocation("a")), at(990, "sid:a").encoded))))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(log == List("upsert:sid:a", "renew", "release"), env.cursors.advance.calls.isEmpty)
      },
      test("starts from the beginning for a new edge") {
        val env = new Env
        for
          _ <- env.holding("")
          _ <- env.client.fetch.returnsZIO(pages(Map.empty))
          puller <- env.puller
          _ <- puller.tick
        yield assertTrue(env.client.fetch.calls == List(("", 2)))
      },
      test("starts from the beginning when the stored cursor is not one this version can read") {
        val env = new Env
        for
          _ <- env.holding("###")
          _ <- env.client.fetch.returnsZIO(pages(Map.empty))
          puller <- env.puller
          _ <- puller.tick
        yield assertTrue(env.client.fetch.calls == List(("", 2)))
      },
      test("a failed read leaves the cursor, writes nothing and gives the lease back") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.failsWith(RevocationFeedClient.Refused(404))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(log == List("release"), env.cursors.advance.calls.isEmpty, env.revocations.revokeAll.calls.isEmpty)
      },
      test("a failed write leaves the cursor behind the page and gives the lease back") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.revocations.revokeAll.failsWith(new RuntimeException("database is down"))
          _ <- env.client.fetch.returnsZIO(pages(Map(at(970).encoded -> Page(List(revocation("a")), at(1010, "sid:a").encoded))))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(log == List("release"), env.cursors.advance.calls.isEmpty)
      },
      test("a failure to take the lease is not an exception, and reads nothing") {
        val env = new Env
        for
          _ <- env.cursors.acquire.failsWith(new RuntimeException("database is down"))
          puller <- env.puller
          _ <- puller.tick
        yield assertTrue(env.client.fetch.calls.isEmpty)
      },
      test("stops when the lease is lost half-way, leaving the cursor to the pod that holds it") {
        val env = new Env
        val first = at(1010, "sid:b")
        for
          _ <- env.cursors.acquire.succeedsWith(Some(at(1000, "sid:a").encoded))
          _ <- env.cursors.advance.returnsZIO((_, _, cursor, _) => env.record(s"advance:$cursor").as(false))
          _ <- env.cursors.release.returnsZIO((_, _) => env.record("release"))
          _ <- env.revocations.revokeAll.returnsZIO(list => env.record(s"upsert:${list.size}"))
          _ <- env.client.fetch.returnsZIO(pages(Map(at(970).encoded -> Page(List(revocation("a"), revocation("b")), first.encoded))))
          puller <- env.puller
          _ <- puller.tick
          log <- env.log.get
        yield assertTrue(env.client.fetch.calls.size == 1, log == List("upsert:2", s"advance:${first.encoded}", "release"))
      },
      test("a full page that does not move the position is not read again for ever") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.returnsZIO((after, _) => ZIO.succeed(Page(List(revocation("a"), revocation("b")), after)))
          puller <- env.puller
          _ <- puller.tick
        yield assertTrue(env.client.fetch.calls.size == 1)
      },
    ),
    suite("full read")(
      test("reads the whole list again once reload-interval has passed, and not before") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.returnsZIO(pages(Map.empty))
          puller <- env.puller
          _ <- TestClock.adjust(599.seconds)
          _ <- puller.tick
          _ <- TestClock.adjust(1.second)
          _ <- puller.tick
          _ <- puller.tick
        yield assertTrue(
          // behind the cursor by the overlap, twice; from the beginning at 600 s; behind the cursor again after
          env.client.fetch.calls.map(_._1) == List(at(970).encoded, "", at(970).encoded),
        )
      },
      test("a full read that failed is tried again by the next cycle") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.failsWith(new RuntimeException("auth is down"))
          puller <- env.puller
          _ <- TestClock.adjust(600.seconds)
          _ <- puller.tick
          _ <- puller.tick
        yield assertTrue(env.client.fetch.calls.map(_._1) == List("", ""))
      },
    ),
    suite("live")(
      test("with the feed off (the default) starts nothing and touches nothing") {
        val env = new Env
        for
          _ <- start(env, configWith(EdgeConfig.RevocationFeed(), native = Some(native)))
          _ <- ZIO.foreachDiscard(1 to 5)(_ => TestClock.adjust(1.second) *> ZIO.yieldNow)
        yield assertTrue(
          !EdgeConfig.RevocationFeed().enabled,
          env.cursors.seed.calls.isEmpty,
          env.cursors.acquire.calls.isEmpty,
          env.client.fetch.calls.isEmpty,
        )
      },
      test("enabled without a native block starts nothing, so the edge carries on with notifications") {
        val env = new Env
        for
          _ <- start(env, configWith(feed, native = None))
          _ <- ZIO.foreachDiscard(1 to 5)(_ => TestClock.adjust(1.second) *> ZIO.yieldNow)
        yield assertTrue(env.cursors.seed.calls.isEmpty, env.cursors.acquire.calls.isEmpty, env.client.fetch.calls.isEmpty)
      },
      test("enabled seeds the source it reads and polls it") {
        val env = new Env
        for
          _ <- env.cursors.seed.succeedsWith(())
          _ <- env.cursors.acquire.succeedsWith(None)
          _ <- start(env, configWith(feed, native = Some(native)))
          _ <- ZIO.foreachDiscard(1 to 5)(_ => TestClock.adjust(1.second) *> ZIO.yieldNow)
        yield assertTrue(env.cursors.seed.calls == List(native.authMutualTlsUrl.encode), env.cursors.acquire.calls.nonEmpty)
      },
    ),
    suite("warnings")(
      test("a feed that stays down is logged once, then at most once in five minutes") {
        val env = new Env
        for
          _ <- env.holding(at(1000, "sid:a").encoded)
          _ <- env.client.fetch.failsWith(RevocationFeedClient.Refused(404))
          puller <- env.puller
          _ <- ZIO.foreachDiscard(1 to 20)(_ => TestClock.adjust(1.second) *> puller.tick)
          early <- warnings
          _ <- TestClock.adjust(5.minutes)
          _ <- puller.tick
          late <- warnings
        yield assertTrue(early == 1, late == 2)
      },
    ),
  )

  private val keyPair =
    val generator = KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(2048)
    generator.generateKeyPair().nn

  private val native = EdgeConfig.Native(
    authMutualTlsUrl = URL.decode("https://auth-mtls.example.com").toOption.get,
    trustedCertificates = Set("/etc/versola/auth-mtls.pem"),
    blobKey = Secret.Bytes32(Array.fill(32)(9.toByte)),
  )

  private def configWith(feed: EdgeConfig.RevocationFeed, native: Option[EdgeConfig.Native]): EdgeConfig =
    EdgeConfig(
      id = EdgeId("edge-1"),
      keyId = "kid-1",
      privateKey = keyPair.getPrivate.nn,
      security = EdgeConfig.Security(
        tokenEncryption = EdgeConfig.Security.TokenEncryption(Secret.Bytes32(Array.fill(32)(3.toByte))),
        edgeSessions = EdgeConfig.Security.EdgeSessions(Secret.Bytes32(Array.fill(32)(5.toByte)), 1.hour),
      ),
      central = EdgeConfig.CentralConfig(url = URL.decode("https://central.example").toOption.get),
      versolaUrl = URL.decode("https://idp.example").toOption.get,
      edgeUrl = URL.decode("https://edge.example").toOption.get,
      configurationCacheRefreshInterval = 5.minutes,
      revocation = EdgeConfig.Revocation(feed = feed),
      native = native,
    )

  /** The layer as the edge builds it, kept alive for the rest of the test. */
  private def start(env: Env, config: EdgeConfig): ZIO[Scope, Throwable, Unit] =
    RevocationFeedPuller.live.build.unit.provideSomeEnvironment[Scope](
      _ ++ ZEnvironment[EdgeConfig, RevocationCursorRepository, EdgeRevocationRepository, RevocationFeedClient](
        config,
        env.cursors,
        env.revocations,
        env.client,
      ),
    )

  private def warnings: UIO[Int] =
    ZTestLogger.logOutput.map(_.count(entry => entry.logLevel == LogLevel.Warning && entry.message().startsWith("Could not pull revocations")))
