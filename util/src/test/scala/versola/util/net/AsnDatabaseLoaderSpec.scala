package versola.util.net

import zio.*
import zio.http.*
import zio.metrics.Metric
import zio.test.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Arrays
import java.util.zip.GZIPOutputStream
import scala.jdk.CollectionConverters.*

object AsnDatabaseLoaderSpec extends ZIOSpecDefault:

  private val versionOne = List("1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.", "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS")
  private val versionTwo = List(
    "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.",
    "5.45.64.0\t5.45.79.255\t99999\tDE\tRENAMED-AS",
    "9.9.9.0\t9.9.9.255\t19281\tUS\tQUAD9",
  )

  private val url = "https://example.com/data/ip2asn-combined.tsv.gz"
  private val interval = 24.hours

  private def text(lines: Iterable[String]): Array[Byte] = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def settings(file: Path, url: String = url, interval: Duration = interval) =
    AsnSettings(file = file.toString, url = url, refreshInterval = interval, hostingKeywords = AsnClassifier.defaultKeywords)

  /** A directory of its own for a test, gone with it. */
  private val directory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("asn-loader")))(dir =>
      ZIO.attemptBlocking:
        Files.list(dir).iterator().asScala.foreach(Files.deleteIfExists)
        Files.deleteIfExists(dir)
      .orDie,
    )

  /** What the server answers, changed by the test. */
  private def serving(initial: Response): ZIO[TestClient, Nothing, Ref[Response]] =
    for
      answer <- Ref.make(initial)
      _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => answer.get).toRoutes)
    yield answer

  private def ok(data: Array[Byte]): Response = Response(status = Status.Ok, body = Body.fromArray(data))

  /** Only for what the loop does on its own schedule: the other cases call `refreshNow` and `awaitInitial`, which complete when
    * the work is done. Work on the blocking pool or in a forked fiber finishes in real time, whatever the test clock says, and
    * on a loaded runner that can be slow, so the bound is generous. */
  private def until(condition: => Boolean): UIO[Unit] =
    live(ZIO.sleep(5.millis).repeatUntil(_ => condition).timeoutFail(new AssertionError("timed out"))(60.seconds)).orDie

  /** The loop has reached its sleep: advancing the clock now wakes it. */
  private val sleeping: UIO[Unit] =
    live(
      ZIO.sleep(5.millis).zipRight(TestClock.sleeps).repeatUntil(_.nonEmpty).timeoutFail(new AssertionError("the loop never slept"))(60.seconds),
    ).unit.orDie

  private def failures: UIO[Double] = Metric.counter("antifraud_asn_database_refresh_failed_total").value.map(_.count)

  private def files(dir: Path): List[String] = Files.list(dir).iterator().asScala.map(_.getFileName.toString).toList.sorted

  private def asnOf(loader: AsnDatabaseLoader, ip: String): Option[Int] = loader.current.flatMap(_.lookup(ip)).map(_.asn)

  def spec = suite("AsnDatabaseLoader")(
    suite("off")(
      test("with no file configured nothing is loaded and nothing is touched, the url is not fetched") {
        ZIO.scoped:
          for
            seen <- Ref.make(0)
            _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => seen.update(_ + 1).as(Response.ok)).toRoutes)
            loader <- AsnDatabaseLoader.start(AsnSettings.off.copy(url = url))
            _ <- TestClock.adjust(interval.multipliedBy(3))
            requests <- seen.get
          yield assertTrue(loader.current.isEmpty, requests == 0)
      },
    ),
    suite("the file")(
      test("is read before the loader is returned, plain or gzipped, so that the first request already finds it") {
        ZIO.scoped:
          for
            dir <- directory
            plain = dir.resolve("plain.tsv")
            packed = dir.resolve("packed.tsv.gz")
            _ <- ZIO.attemptBlocking(Files.write(plain, text(versionOne)))
            _ <- ZIO.attemptBlocking(Files.write(packed, gzip(text(versionOne))))
            fromPlain <- AsnDatabaseLoader.start(settings(plain, url = ""))
            fromPacked <- AsnDatabaseLoader.start(settings(packed, url = ""))
          yield assertTrue(
            asnOf(fromPlain, "5.45.64.1").contains(24940),
            asnOf(fromPacked, "5.45.64.1").contains(24940),
            fromPlain.current.exists(_.ranges == 2),
          )
      },
      test("that is not there, with no url to fetch it from, leaves no database and does not fail") {
        ZIO.scoped:
          for
            dir <- directory
            loader <- AsnDatabaseLoader.start(settings(dir.resolve("missing.tsv"), url = ""))
            logged <- ZTestLogger.logOutput.map(_.count(_.logLevel == LogLevel.Warning))
          yield assertTrue(loader.current.isEmpty, logged == 1)
      },
      test("that is not a database leaves no database, and is counted as a failure") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("html.tsv")
            _ <- ZIO.attemptBlocking(Files.write(file, "<html><body>404 not found</body></html>\n".getBytes(StandardCharsets.UTF_8)))
            before <- failures
            loader <- AsnDatabaseLoader.start(settings(file, url = ""))
            after <- failures
          yield assertTrue(loader.current.isEmpty, after == before + 1)
      },
      test("a file with a few bad lines among many good ones is a database, with a warning") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("mostly.tsv")
            rows = (0 until 50).map(i => s"10.0.$i.0\t10.0.$i.255\t${i + 1}\tUS\tNet $i") :+ "garbage"
            _ <- ZIO.attemptBlocking(Files.write(file, text(rows)))
            loader <- AsnDatabaseLoader.start(settings(file, url = ""))
            warned <- ZTestLogger.logOutput.map(_.exists(_.message().contains("not valid ranges")))
          yield assertTrue(loader.current.exists(_.ranges == 50), warned)
      },
      test("without a url is read again when it is replaced, and not before the interval") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("local.tsv")
            _ <- ZIO.attemptBlocking(Files.write(file, text(versionOne)))
            loader <- AsnDatabaseLoader.start(settings(file, url = ""))
            _ <- sleeping
            // A file written a second later than the one that was read (a file system keeps its times to the second at worst).
            _ <- ZIO.attemptBlocking:
              Files.write(file, text(versionTwo))
              Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)))
            _ <- TestClock.adjust(interval.minus(1.second))
            early = asnOf(loader, "9.9.9.9")
            _ <- TestClock.adjust(2.seconds)
            _ <- until(asnOf(loader, "9.9.9.9").isDefined)
          yield assertTrue(early.isEmpty, asnOf(loader, "9.9.9.9").contains(19281), asnOf(loader, "5.45.64.1").contains(99999))
      },
    ),
    suite("the download")(
      test("with no file yet fetches at once, in the background, and leaves the file and nothing else in its directory") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            _ <- serving(ok(gzip(text(versionOne))))
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- loader.awaitInitial
            listing = files(dir)
            size = Files.readAllBytes(file).length
          yield assertTrue(asnOf(loader, "5.45.64.1").contains(24940), listing == List("asn.tsv.gz"), size > 0)
      },
      test("replaces the database with what it fetched, and the one a reader holds is not changed under it") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            _ <- ZIO.attemptBlocking(Files.write(file, text(versionOne)))
            answer <- serving(ok(gzip(text(versionTwo))))
            loader <- AsnDatabaseLoader.start(settings(file))
            held = loader.current.get
            _ <- loader.refreshNow
            listing = files(dir)
            stored = AsnDatabase.read(file).database
          yield assertTrue(
            asnOf(loader, "5.45.64.1").contains(99999),
            held.lookup("5.45.64.1").map(_.asn).contains(24940),
            held.lookup("9.9.9.9").isEmpty,
            listing == List("asn.tsv.gz"),
            // What is on disk is what was fetched, so a restart reads the same.
            stored.lookup("9.9.9.9").map(_.asn).contains(19281),
          )
      },
      test("fetches again every interval, and not before it") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            _ <- ZIO.attemptBlocking(Files.write(file, text(versionOne)))
            _ <- serving(ok(gzip(text(versionTwo))))
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- sleeping
            _ <- TestClock.adjust(interval.minus(1.second))
            early = asnOf(loader, "9.9.9.9")
            _ <- TestClock.adjust(2.seconds)
            _ <- until(asnOf(loader, "9.9.9.9").isDefined)
          yield assertTrue(early.isEmpty, asnOf(loader, "9.9.9.9").contains(19281))
      },
      test("a server that answers an error keeps the database and the file, and counts the failure") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            original = text(versionOne)
            _ <- ZIO.attemptBlocking(Files.write(file, original))
            _ <- serving(Response.status(Status.InternalServerError))
            before <- failures
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- loader.refreshNow
            after <- failures
            listing = files(dir)
            onDisk = Files.readAllBytes(file)
          yield assertTrue(
            after == before + 1,
            asnOf(loader, "5.45.64.1").contains(24940),
            Arrays.equals(onDisk, original),
            listing == List("asn.tsv.gz"),
          )
      },
      test("an answer that is not a database (an error page with status 200) is not used and replaces nothing") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            original = text(versionOne)
            _ <- ZIO.attemptBlocking(Files.write(file, original))
            _ <- serving(ok("<html>maintenance</html>\n".getBytes(StandardCharsets.UTF_8)))
            before <- failures
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- loader.refreshNow
            after <- failures
            listing = files(dir)
            onDisk = Files.readAllBytes(file)
          yield assertTrue(
            after == before + 1,
            asnOf(loader, "5.45.64.1").contains(24940),
            Arrays.equals(onDisk, original),
            listing == List("asn.tsv.gz"),
          )
      },
      test("a gzip file cut short is not used and replaces nothing") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            original = text(versionOne)
            _ <- ZIO.attemptBlocking(Files.write(file, original))
            full = gzip(text(versionTwo))
            _ <- serving(ok(full.take(full.length - 10)))
            before <- failures
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- loader.refreshNow
            listing = files(dir)
            onDisk = Files.readAllBytes(file)
          yield assertTrue(asnOf(loader, "5.45.64.1").contains(24940), Arrays.equals(onDisk, original), listing == List("asn.tsv.gz"))
      },
      test("a server that never answers is given up on, and the database in use stays") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            _ <- ZIO.attemptBlocking(Files.write(file, text(versionOne)))
            _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.never).toRoutes)
            before <- failures
            // An interval the clock does not reach, so that only the refresh of this test is in play.
            loader <- AsnDatabaseLoader.start(settings(file, interval = 100000.days))
            refreshing <- loader.refreshNow.fork
            // The timeout has registered its sleep once the download has started; advancing the clock before that moves nothing, so
            // it is advanced until the refresh is over.
            _ <- (TestClock.adjust(AsnDatabaseLoader.DownloadTimeout.plus(1.second)) *> live(ZIO.sleep(5.millis)))
              .repeatUntil(_ => false)
              .race(refreshing.join)
            after <- failures
            listing = files(dir)
          yield assertTrue(after == before + 1, asnOf(loader, "5.45.64.1").contains(24940), listing == List("asn.tsv.gz"))
      },
      test("with no database yet a failed download is tried again after five minutes, not a day") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            answer <- serving(Response.status(Status.ServiceUnavailable))
            before <- failures
            loader <- AsnDatabaseLoader.start(settings(file))
            _ <- loader.awaitInitial
            failedOnce <- failures.map(_ == before + 1)
            _ <- answer.set(ok(gzip(text(versionOne))))
            _ <- sleeping
            _ <- TestClock.adjust(AsnDatabaseLoader.RetryWhileEmpty)
            _ <- until(loader.current.isDefined)
          yield assertTrue(failedOnce, asnOf(loader, "5.45.64.1").contains(24940))
      },
      test("never logs the address it fetches from, which may carry a token") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("asn.tsv.gz")
            _ <- serving(Response.status(Status.Forbidden))
            loader <- AsnDatabaseLoader.start(settings(file, url = "https://mirror.example.com/asn.tsv.gz?token=s3cr3t-value"))
            _ <- loader.awaitInitial
            output <- ZTestLogger.logOutput
            text = output.map(e => e.message() + e.cause.prettyPrint).mkString("\n")
          yield assertTrue(text.contains("mirror.example.com"), !text.contains("s3cr3t-value"))
      },
    ),
  ).provide(TestClient.layer) @@ TestAspect.sequential
