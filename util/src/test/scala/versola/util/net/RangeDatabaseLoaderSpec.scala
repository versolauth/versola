package versola.util.net

import versola.util.Secret
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

object RangeDatabaseLoaderSpec extends ZIOSpecDefault:

  /** A format under test: the loader is the same for every one, so its cases run once for each, with the rows of that format. */
  private case class Fixture[A](
      name: String,
      label: String,
      metrics: String,
      format: RangeFormat[A],
      versionOne: List[String],
      versionTwo: List[String],
      /** The row for the n-th of many ranges, and a line that is not a row of the format. */
      row: Int => String,
      garbage: String,
      /** What the database holds for an address, as text: the AS number, the country. */
      valueOf: (RangeDatabase[A], String) => Option[String],
      /** The values at 5.45.64.1 in version one and in version two, and at 9.9.9.9 in version two. */
      oneValue: String,
      twoValue: String,
      newValue: String,
      extension: String,
  )

  private val asn = Fixture[AsnInfo](
    name = "ASN, iptoasn.com",
    label = "ASN database",
    metrics = "antifraud_asn",
    format = IptoasnFormat,
    versionOne = List("1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.", "5.45.64.0\t5.45.79.255\t24940\tDE\tHETZNER-AS"),
    versionTwo = List(
      "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET - Cloudflare, Inc.",
      "5.45.64.0\t5.45.79.255\t99999\tDE\tRENAMED-AS",
      "9.9.9.0\t9.9.9.255\t19281\tUS\tQUAD9",
    ),
    row = i => s"10.0.$i.0\t10.0.$i.255\t${i + 1}\tUS\tNet $i",
    garbage = "garbage",
    valueOf = (db, ip) => db.lookup(ip).map(_.asn.toString),
    oneValue = "24940",
    twoValue = "99999",
    newValue = "19281",
    extension = "tsv",
  )

  private val geo = Fixture[String](
    name = "country, DB-IP",
    label = "country database",
    metrics = "antifraud_geo",
    format = DbIpCountryFormat,
    versionOne = List("1.0.0.0,1.0.0.255,US", "5.45.64.0,5.45.79.255,DE"),
    versionTwo = List("1.0.0.0,1.0.0.255,US", "5.45.64.0,5.45.79.255,FR", "9.9.9.0,9.9.9.255,CH"),
    row = i => s"10.0.$i.0,10.0.$i.255,US",
    garbage = "garbage",
    valueOf = (db, ip) => db.lookup(ip),
    oneValue = "DE",
    twoValue = "FR",
    newValue = "CH",
    extension = "csv",
  )

  private val url = "https://example.com/data/ranges.gz"
  private val interval = 24.hours

  private def text(lines: Iterable[String]): Array[Byte] = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)

  private def gzip(data: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val gz = GZIPOutputStream(out)
    gz.write(data)
    gz.close()
    out.toByteArray

  private def source(file: Path, url: String = url, interval: Duration = interval) =
    RangeSource(file = file.toString, url = Secret.fromString(url), refreshInterval = interval)

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

  private def failures(fx: Fixture[?]): UIO[Double] = Metric.counter(s"${fx.metrics}_database_refresh_failed_total").value.map(_.count)

  private def files(dir: Path): List[String] = Files.list(dir).iterator().asScala.map(_.getFileName.toString).toList.sorted

  private def suiteFor[A](fx: Fixture[A]) =
    def start(s: RangeSource) = RangeDatabaseLoader.start(s, fx.format, fx.label, RangeMetrics(fx.metrics))
    def valueAt(loader: RangeDatabaseLoader[A], ip: String): Option[String] = loader.current.flatMap(fx.valueOf(_, ip))
    suite(s"RangeDatabaseLoader (${fx.name})")(
      suite("off")(
        test("with no file configured nothing is loaded and nothing is touched, the url is not fetched") {
          ZIO.scoped:
            for
              seen <- Ref.make(0)
              _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => seen.update(_ + 1).as(Response.ok)).toRoutes)
              loader <- start(RangeSource(file = "", url = Secret.fromString(url), refreshInterval = interval))
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
              _ <- ZIO.attemptBlocking(Files.write(plain, text(fx.versionOne)))
              _ <- ZIO.attemptBlocking(Files.write(packed, gzip(text(fx.versionOne))))
              fromPlain <- start(source(plain, url = ""))
              fromPacked <- start(source(packed, url = ""))
            yield assertTrue(
              valueAt(fromPlain, "5.45.64.1").contains(fx.oneValue),
              valueAt(fromPacked, "5.45.64.1").contains(fx.oneValue),
              fromPlain.current.exists(_.ranges == 2),
            )
        },
        test("that is not there, with no url to fetch it from, leaves no database and does not fail") {
          ZIO.scoped:
            for
              dir <- directory
              loader <- start(source(dir.resolve("missing.tsv"), url = ""))
              logged <- ZTestLogger.logOutput.map(_.count(_.logLevel == LogLevel.Warning))
            yield assertTrue(loader.current.isEmpty, logged == 1)
        },
        test("that is not a database leaves no database, and is counted as a failure") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("html.tsv")
              _ <- ZIO.attemptBlocking(Files.write(file, "<html><body>404 not found</body></html>\n".getBytes(StandardCharsets.UTF_8)))
              before <- failures(fx)
              loader <- start(source(file, url = ""))
              after <- failures(fx)
            yield assertTrue(loader.current.isEmpty, after == before + 1)
        },
        test("a file with a few bad lines among many good ones is a database, with a warning") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("mostly.tsv")
              rows = (0 until 50).map(fx.row) :+ fx.garbage
              _ <- ZIO.attemptBlocking(Files.write(file, text(rows)))
              loader <- start(source(file, url = ""))
              warned <- ZTestLogger.logOutput.map(_.exists(_.message().contains("not valid ranges")))
            yield assertTrue(loader.current.exists(_.ranges == 50), warned)
        },
        test("without a url is read again when it is replaced, and not before the interval") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("local.tsv")
              _ <- ZIO.attemptBlocking(Files.write(file, text(fx.versionOne)))
              loader <- start(source(file, url = ""))
              _ <- sleeping
              // A file written a second later than the one that was read (a file system keeps its times to the second at worst).
              _ <- ZIO.attemptBlocking:
                Files.write(file, text(fx.versionTwo))
                Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)))
              _ <- TestClock.adjust(interval.minus(1.second))
              early = valueAt(loader, "9.9.9.9")
              _ <- TestClock.adjust(2.seconds)
              _ <- until(valueAt(loader, "9.9.9.9").isDefined)
            yield assertTrue(early.isEmpty, valueAt(loader, "9.9.9.9").contains(fx.newValue), valueAt(loader, "5.45.64.1").contains(fx.twoValue))
        },
      ),
      suite("the download")(
        test("with no file yet fetches at once, in the background, and leaves the file and nothing else in its directory") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              _ <- serving(ok(gzip(text(fx.versionOne))))
              loader <- start(source(file))
              _ <- loader.awaitInitial
              listing = files(dir)
              size = Files.readAllBytes(file).length
            yield assertTrue(valueAt(loader, "5.45.64.1").contains(fx.oneValue), listing == List("asn.tsv.gz"), size > 0)
        },
        test("replaces the database with what it fetched, and the one a reader holds is not changed under it") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              _ <- ZIO.attemptBlocking(Files.write(file, text(fx.versionOne)))
              answer <- serving(ok(gzip(text(fx.versionTwo))))
              loader <- start(source(file))
              held = loader.current.get
              _ <- loader.refreshNow
              listing = files(dir)
              stored = RangeDatabase.read(file, fx.format).database
            yield assertTrue(
              valueAt(loader, "5.45.64.1").contains(fx.twoValue),
              fx.valueOf(held, "5.45.64.1").contains(fx.oneValue),
              fx.valueOf(held, "9.9.9.9").isEmpty,
              listing == List("asn.tsv.gz"),
              // What is on disk is what was fetched, so a restart reads the same.
              fx.valueOf(stored, "9.9.9.9").contains(fx.newValue),
            )
        },
        test("fetches again every interval, and not before it") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              _ <- ZIO.attemptBlocking(Files.write(file, text(fx.versionOne)))
              _ <- serving(ok(gzip(text(fx.versionTwo))))
              loader <- start(source(file))
              _ <- sleeping
              _ <- TestClock.adjust(interval.minus(1.second))
              early = valueAt(loader, "9.9.9.9")
              _ <- TestClock.adjust(2.seconds)
              _ <- until(valueAt(loader, "9.9.9.9").isDefined)
            yield assertTrue(early.isEmpty, valueAt(loader, "9.9.9.9").contains(fx.newValue))
        },
        test("a server that answers an error keeps the database and the file, and counts the failure") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              original = text(fx.versionOne)
              _ <- ZIO.attemptBlocking(Files.write(file, original))
              _ <- serving(Response.status(Status.InternalServerError))
              before <- failures(fx)
              loader <- start(source(file))
              _ <- loader.refreshNow
              after <- failures(fx)
              listing = files(dir)
              onDisk = Files.readAllBytes(file)
            yield assertTrue(
              after == before + 1,
              valueAt(loader, "5.45.64.1").contains(fx.oneValue),
              Arrays.equals(onDisk, original),
              listing == List("asn.tsv.gz"),
            )
        },
        test("an answer that is not a database (an error page with status 200) is not used and replaces nothing") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              original = text(fx.versionOne)
              _ <- ZIO.attemptBlocking(Files.write(file, original))
              _ <- serving(ok("<html>maintenance</html>\n".getBytes(StandardCharsets.UTF_8)))
              before <- failures(fx)
              loader <- start(source(file))
              _ <- loader.refreshNow
              after <- failures(fx)
              listing = files(dir)
              onDisk = Files.readAllBytes(file)
            yield assertTrue(
              after == before + 1,
              valueAt(loader, "5.45.64.1").contains(fx.oneValue),
              Arrays.equals(onDisk, original),
              listing == List("asn.tsv.gz"),
            )
        },
        test("a gzip file cut short is not used and replaces nothing") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              original = text(fx.versionOne)
              _ <- ZIO.attemptBlocking(Files.write(file, original))
              full = gzip(text(fx.versionTwo))
              _ <- serving(ok(full.take(full.length - 10)))
              before <- failures(fx)
              loader <- start(source(file))
              _ <- loader.refreshNow
              listing = files(dir)
              onDisk = Files.readAllBytes(file)
            yield assertTrue(valueAt(loader, "5.45.64.1").contains(fx.oneValue), Arrays.equals(onDisk, original), listing == List("asn.tsv.gz"))
        },
        test("a server that never answers is given up on, and the database in use stays") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              _ <- ZIO.attemptBlocking(Files.write(file, text(fx.versionOne)))
              _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.never).toRoutes)
              before <- failures(fx)
              // An interval the clock does not reach, so that only the refresh of this test is in play.
              loader <- start(source(file, interval = 100000.days))
              refreshing <- loader.refreshNow.fork
              // The timeout has registered its sleep once the download has started; advancing the clock before that moves nothing, so
              // it is advanced until the refresh is over.
              _ <- (TestClock.adjust(RangeDatabaseLoader.DownloadTimeout.plus(1.second)) *> live(ZIO.sleep(5.millis)))
                .repeatUntil(_ => false)
                .race(refreshing.join)
              after <- failures(fx)
              listing = files(dir)
            yield assertTrue(after == before + 1, valueAt(loader, "5.45.64.1").contains(fx.oneValue), listing == List("asn.tsv.gz"))
        },
        test("with no database yet a failed download is tried again after five minutes, not a day") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              answer <- serving(Response.status(Status.ServiceUnavailable))
              before <- failures(fx)
              loader <- start(source(file))
              _ <- loader.awaitInitial
              failedOnce <- failures(fx).map(_ == before + 1)
              _ <- answer.set(ok(gzip(text(fx.versionOne))))
              _ <- sleeping
              _ <- TestClock.adjust(RangeDatabaseLoader.RetryWhileEmpty)
              _ <- until(loader.current.isDefined)
            yield assertTrue(failedOnce, valueAt(loader, "5.45.64.1").contains(fx.oneValue))
        },
        test("never logs the address it fetches from, which may carry a token") {
          ZIO.scoped:
            for
              dir <- directory
              file = dir.resolve("asn.tsv.gz")
              _ <- serving(Response.status(Status.Forbidden))
              loader <- start(source(file, url = "https://mirror.example.com/asn.tsv.gz?token=s3cr3t-value"))
              _ <- loader.awaitInitial
              output <- ZTestLogger.logOutput
              text = output.map(e => e.message() + e.cause.prettyPrint).mkString("\n")
            yield assertTrue(text.contains("mirror.example.com"), !text.contains("s3cr3t-value"))
        },
      ),
    )

  /** `{yyyy}` and `{mm}` in the address: the month of the download, and the month before when this one is not published. */
  private def placeholders[A](fx: Fixture[A]) =
    val template = "https://mirror.example.com/free/ranges-{yyyy}-{mm}.csv.gz?key=s3cr3t-value"
    val november = Instant.parse("2026-11-15T10:00:00Z")
    val january = Instant.parse("2027-01-02T00:30:00Z")

    def start(file: Path) =
      RangeDatabaseLoader.start(source(file, url = template), fx.format, fx.label, RangeMetrics(fx.metrics))

    /** Answers `status` to the paths in `missing` and the database to the others, and records the paths asked for. */
    def mirror(missing: Set[String], status: Status = Status.NotFound): ZIO[TestClient, Nothing, Ref[List[String]]] =
      for
        asked <- Ref.make(List.empty[String])
        _ <- TestClient.addRoutes(
          Handler
            .fromFunctionZIO[Request]: request =>
              val path = request.url.path.encode
              asked.update(_ :+ path).as(if missing(path) then Response.status(status) else ok(gzip(text(fx.versionTwo))))
            .toRoutes,
        )
      yield asked

    suite(s"URL placeholders (${fx.name})")(
      test("the year and the month of the download, in UTC, are put into the address") {
        ZIO.scoped:
          for
            dir <- directory
            _ <- TestClock.setTime(november)
            asked <- mirror(Set.empty)
            loader <- start(dir.resolve("ranges.csv.gz"))
            _ <- loader.awaitInitial
            requested <- asked.get
          yield assertTrue(requested == List("/free/ranges-2026-11.csv.gz"), loader.current.exists(_.ranges == 3))
      },
      test("a month with two digits and a year change are written as the source writes them: 2027-01") {
        ZIO.scoped:
          for
            dir <- directory
            _ <- TestClock.setTime(january)
            asked <- mirror(Set.empty)
            loader <- start(dir.resolve("ranges.csv.gz"))
            _ <- loader.awaitInitial
            requested <- asked.get
          yield assertTrue(requested == List("/free/ranges-2027-01.csv.gz"), loader.current.isDefined)
      },
      test("a 404 for this month is tried once more with the month before, across a year end, and says which was taken") {
        ZIO.scoped:
          for
            dir <- directory
            _ <- TestClock.setTime(january)
            asked <- mirror(Set("/free/ranges-2027-01.csv.gz"))
            before <- failures(fx)
            loader <- start(dir.resolve("ranges.csv.gz"))
            _ <- loader.awaitInitial
            requested <- asked.get
            after <- failures(fx)
            output <- ZTestLogger.logOutput
            info = output.filter(_.logLevel == LogLevel.Info).map(_.message())
          yield assertTrue(
            requested == List("/free/ranges-2027-01.csv.gz", "/free/ranges-2026-12.csv.gz"),
            loader.current.exists(_.ranges == 3),
            after == before,
            info.exists(m => m.contains("2027-01") && m.contains("404") && m.contains("2026-12")),
            info.exists(m => m.contains("downloaded") && m.contains("2026-12")),
          )
      },
      test("when the month before is missing too the database in use stays, and it is one failure, after two requests") {
        ZIO.scoped:
          for
            dir <- directory
            file = dir.resolve("ranges.csv.gz")
            _ <- ZIO.attemptBlocking(Files.write(file, text(fx.versionOne)))
            _ <- TestClock.setTime(november)
            asked <- mirror(Set("/free/ranges-2026-11.csv.gz", "/free/ranges-2026-10.csv.gz"))
            before <- failures(fx)
            loader <- start(file)
            _ <- loader.refreshNow
            requested <- asked.get
            after <- failures(fx)
          yield assertTrue(
            requested.take(2) == List("/free/ranges-2026-11.csv.gz", "/free/ranges-2026-10.csv.gz"),
            after == before + 1,
            loader.current.flatMap(fx.valueOf(_, "5.45.64.1")).contains(fx.oneValue),
          )
      },
      test("only a 404 falls back: another error is one request and one failure") {
        ZIO.scoped:
          for
            dir <- directory
            _ <- TestClock.setTime(november)
            asked <- mirror(Set("/free/ranges-2026-11.csv.gz"), Status.InternalServerError)
            before <- failures(fx)
            loader <- start(dir.resolve("ranges.csv.gz"))
            _ <- loader.awaitInitial
            requested <- asked.get
            after <- failures(fx)
          yield assertTrue(requested == List("/free/ranges-2026-11.csv.gz"), after == before + 1, loader.current.isEmpty)
      },
      test("an address without placeholders is fetched as it is, and a 404 is not retried") {
        ZIO.scoped:
          for
            dir <- directory
            asked <- Ref.make(0)
            _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => asked.update(_ + 1).as(Response.status(Status.NotFound))).toRoutes)
            loader <- RangeDatabaseLoader.start(source(dir.resolve("r.csv.gz"), url = url), fx.format, fx.label, RangeMetrics(fx.metrics))
            _ <- loader.awaitInitial
            requests <- asked.get
          yield assertTrue(requests == 1)
      },
      test("only the host is logged, whatever the month: neither the key nor the path of the address is") {
        ZIO.scoped:
          for
            dir <- directory
            _ <- TestClock.setTime(november)
            _ <- mirror(Set("/free/ranges-2026-11.csv.gz", "/free/ranges-2026-10.csv.gz"))
            loader <- start(dir.resolve("ranges.csv.gz"))
            _ <- loader.awaitInitial
            output <- ZTestLogger.logOutput
            logged = output.map(e => e.message() + e.cause.prettyPrint).mkString("\n")
          yield assertTrue(logged.contains("mirror.example.com"), !logged.contains("s3cr3t-value"), !logged.contains("/free/"))
      },
    )

  def spec = suite("RangeDatabaseLoader")(suiteFor(asn), suiteFor(geo), placeholders(geo)).provide(TestClient.layer) @@ TestAspect.sequential
