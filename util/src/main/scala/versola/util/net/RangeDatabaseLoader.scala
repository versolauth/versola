package versola.util.net

import versola.util.Secret
import zio.*
import zio.http.{Client, Request, Status, URL}
import zio.metrics.{Metric, MetricLabel}
import zio.stream.{ZSink, ZStream}

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.{Instant, YearMonth, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference

/** Where a range database comes from (the common keys of the `antifraud.asn` and `antifraud.geo` blocks).
  *
  * @param file            where the database is read from; empty switches the feature off, and nothing else changes
  * @param url             where a fresher one is downloaded from, to `file`; empty for none (the file is then read again when it
  *                        changes). A [[Secret]], since a source may carry a key in it; only its host is ever logged. It may hold
  *                        `{yyyy}` and `{mm}`, which are the year and the month (UTC) at the moment of the download
  * @param refreshInterval how often `url` is fetched, or `file` looked at again
  */
case class RangeSource(file: String, url: Secret, refreshInterval: Duration):
  def enabled: Boolean = file.trim.nonEmpty

object RangeSource:

  /** The keys `file`, `url` and `refresh-interval` of a block, each with a default (the one for the interval is the caller's),
    * so that a block may leave any of them out. The `url` is read as text, as written, and held as a [[Secret]]. */
  def config(defaultInterval: Duration): Config[RangeSource] =
    (Config.string("file").withDefault("") ++
      Config.string("url").withDefault("").mapOrFail(validUrl) ++
      Config.duration("refreshInterval").withDefault(defaultInterval).mapOrFail(positive)).map: (file, url, interval) =>
      RangeSource(file = file.trim, url = Secret.fromString(url), refreshInterval = interval)

  // The messages never carry the value: the url may hold a key.
  private def validUrl(raw: String): Either[Config.Error, String] =
    val url = raw.trim
    if url.isEmpty then Right(url)
    else
      URL.decode(RangeDatabaseLoader.expand(url, RangeDatabaseLoader.SampleMonth)) match
        case Right(parsed) if parsed.host.exists(_.nonEmpty) && parsed.scheme.exists(s => s.encode == "https" || s.encode == "http") => Right(url)
        case _ => Left(Config.Error.InvalidData(message = "url must be an http or https URL, or empty"))

  private def positive(interval: Duration): Either[Config.Error, Duration] =
    if interval.isZero || interval.isNegative then Left(Config.Error.InvalidData(message = "refresh-interval must be longer than zero"))
    else Right(interval)

/** The range database in use: the one read from the configured file, replaced when a fresher one has been downloaded or the
  * file has changed. What it is asked for is always in memory (`current`); reading and downloading happen in the background,
  * and a failure of either keeps the database there is and says so in the log and in the `..._database_refresh_failed_total`
  * counter of the feature (a database that is never refreshed is never a reason to refuse a request).
  */
trait RangeDatabaseLoader[A]:
  /** `None` while no database is loaded: the feature is off, or its file is not there yet. */
  def current: Option[RangeDatabase[A]]

  /** Completes once the first fetch is over, whatever it came to: at once when none is needed (the file was read, or there
    * is no `url`). What a test or a readiness check waits for, instead of polling `current`. */
  def awaitInitial: UIO[Unit] = ZIO.unit

  /** Does now what the background loop does every interval (download the `url`, or read the file again if it changed),
    * and completes when that is done. Never fails: a failure is the same warning and counter as in the loop. */
  def refreshNow: UIO[Unit] = ZIO.unit

object RangeDatabaseLoader:

  def off[A]: RangeDatabaseLoader[A] = new RangeDatabaseLoader[A]:
    override def current: Option[RangeDatabase[A]] = None

  def fixed[A](database: RangeDatabase[A]): RangeDatabaseLoader[A] = new RangeDatabaseLoader[A]:
    override def current: Option[RangeDatabase[A]] = Some(database)

  /** The most a download may take, and may be: a database is some tens of megabytes. */
  val DownloadTimeout: Duration = 5.minutes
  val MaxDownloadBytes: Long = 256L << 20

  /** How soon a database that is not there yet is tried for again, if the interval is longer. */
  val RetryWhileEmpty: Duration = 5.minutes

  /** More malformed lines than this share of the lines make a file not a database but a wrong file (an error page, a
    * different format); it is not used. */
  private val MaxMalformedShare = 0.1

  private val YearPlaceholder = "{yyyy}"
  private val MonthPlaceholder = "{mm}"

  /** The URL for `month`: the placeholders filled in. */
  private[net] def expand(template: String, month: YearMonth): String =
    template.replace(YearPlaceholder, f"${month.getYear}%04d").replace(MonthPlaceholder, f"${month.getMonthValue}%02d")

  private[net] def hasPlaceholders(template: String): Boolean =
    template.contains(YearPlaceholder) || template.contains(MonthPlaceholder)

  /** The month a template is checked and logged by when no download is under way. */
  private[net] val SampleMonth: YearMonth = YearMonth.of(2000, 1)

  /** The server has no file at the address: for an address with the month in it, the month is not published yet. */
  private class NotFound extends RuntimeException("the server answered 404")

  /** Reads the database `source` names and starts keeping it fresh: the file is read before this returns, so that the first
    * request already finds it; the download, if there is a `url`, runs in the background (at once when there is no file yet),
    * then every `refresh-interval`. Never fails: a file that cannot be read is a warning and no database.
    *
    * @param label   what the database is called in the log (`ASN database`)
    * @param metrics the counters of the feature
    */
  def start[A](
      source: RangeSource,
      format: RangeFormat[A],
      label: String,
      metrics: RangeMetrics,
  ): ZIO[Client & Scope, Nothing, RangeDatabaseLoader[A]] =
    if !source.enabled then ZIO.succeed(off)
    else
      for
        client <- ZIO.service[Client]
        initial <- Promise.make[Nothing, Unit]
        loader = Impl(source, Paths.get(source.file.trim), format, label, metrics, client, initial)
        _ <- loader.loadFile
        _ <- loader.keepFresh.forkScoped
      yield loader

  private class Impl[A](
      source: RangeSource,
      file: Path,
      format: RangeFormat[A],
      label: String,
      metrics: RangeMetrics,
      client: Client,
      initial: Promise[Nothing, Unit],
  ) extends RangeDatabaseLoader[A]:
    private val database = AtomicReference[Option[RangeDatabase[A]]](None)

    /** The modification time of the file the database in use was read from, to tell a file that changed. */
    private val loadedFrom = AtomicReference[Option[Long]](None)

    /** The configured address with its placeholders still in it; none when it is empty or is not an address. */
    private val template: Option[String] =
      Option(String(source.url, StandardCharsets.UTF_8).trim).filter(_.nonEmpty).filter(t => URL.decode(expand(t, SampleMonth)).isRight)

    private val timed: Boolean = template.exists(hasPlaceholders)

    /** The host alone: the address is not logged whole, an operator's mirror may carry a token in it. */
    private val host: String = template.flatMap(t => URL.decode(expand(t, SampleMonth)).toOption).flatMap(_.host).getOrElse("")

    private val Label = label.capitalize

    override def current: Option[RangeDatabase[A]] = database.get

    override def awaitInitial: UIO[Unit] = initial.await

    override def refreshNow: UIO[Unit] = refresh

    /** Reads `path` as a database, or fails if it is not one. It is not taken into use here: the caller does that once the
      * file it came from is where `file` is, so that what is in memory is always what a restart would read. */
    private def read(path: Path): Task[RangeDatabase[A]] =
      for
        loaded <- ZIO.attemptBlocking(RangeDatabase.read(path, format))
        total = loaded.database.ranges + loaded.malformed + loaded.ignored
        _ <- ZIO
          .fail(RuntimeException(s"not a $label: ${loaded.database.ranges} ranges and ${loaded.malformed} malformed lines"))
          .when(loaded.database.ranges == 0 || loaded.malformed > MaxMalformedShare * total)
        _ <- ZIO.logWarning(s"${loaded.malformed} lines of the $label are not valid ranges and were left out").when(loaded.malformed > 0)
      yield loaded.database

    /** Takes `loaded` into use as the contents of `file`, written at `written`. */
    private def use(loaded: RangeDatabase[A], written: FileTime): UIO[Unit] =
      ZIO.succeed {
        database.set(Some(loaded))
        loadedFrom.set(Some(written.toMillis))
      } *> metrics.loaded(written.toInstant, loaded.ranges)

    /** Reads `file` and takes it into use. */
    private def adopt(path: Path): Task[Unit] =
      for
        loaded <- read(path)
        written <- ZIO.attemptBlocking(Files.getLastModifiedTime(path))
        _ <- use(loaded, written)
        _ <- ZIO.logInfo(s"$Label loaded: ${loaded.ranges} ranges")
      yield ()

    private def failed(what: String)(cause: Cause[Throwable]): UIO[Unit] =
      metrics.refreshFailed *> ZIO.logWarningCause(
        s"$what: ${if database.get.isDefined then s"keeping the previous $label" else s"no $label is loaded"}",
        cause,
      )

    /** The file, if it is there. A file that is not there is not a failure while a download is to fetch it. */
    val loadFile: UIO[Unit] =
      ZIO.attemptBlocking(Files.exists(file)).orElseSucceed(false).flatMap: exists =>
        if exists then adopt(file).catchAllCause(failed(s"could not read the $label file"))
        else if template.isEmpty then ZIO.logWarning(s"the $label file is not there and no url is configured: no $label is loaded")
        else ZIO.unit

    /** Downloads the address of the current month, and where that month is not published yet (a 404) once more the address
      * of the month before. */
    private def download(template: String): Task[Unit] =
      if !timed then addressOf(template, SampleMonth).flatMap(fetchTo(_, None))
      else
        Clock.instant.flatMap: now =>
          val month = YearMonth.from(now.atZone(ZoneOffset.UTC))
          addressOf(template, month).flatMap(fetchTo(_, Some(month))).catchSome:
            case _: NotFound =>
              val previous = month.minusMonths(1)
              ZIO.logInfo(s"no $label for $month at $host (404), trying $previous") *>
                addressOf(template, previous).flatMap(fetchTo(_, Some(previous)))

    // The failure does not carry the address: it may hold a key.
    private def addressOf(template: String, month: YearMonth): Task[URL] =
      ZIO.fromEither(URL.decode(expand(template, month))).mapError(_ => RuntimeException("the url is not a valid address"))

    /** Downloads to a file next to `file` and moves it over `file` in one step, so that nothing ever reads half a file, and
      * only once it has been read as a database, so that a wrong one does not replace a good one. */
    private def fetchTo(from: URL, month: Option[YearMonth]): Task[Unit] =
      Random.nextUUID.flatMap: suffix =>
        val tmp = file.resolveSibling(s"${file.getFileName}.$suffix.tmp")
        fetch(from, tmp)
          .flatMap: loaded =>
            // Moved first, used after: a move that fails (the target not writable, a symlink across devices) leaves memory and
            // disk agreeing on the old database, with the failure counted; the other order had them disagree until a restart.
            for
              _ <- ZIO.attemptBlocking(Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
              written <- ZIO.attemptBlocking(Files.getLastModifiedTime(file))
              _ <- use(loaded, written)
              _ <- ZIO.logInfo(s"$Label downloaded from $host${month.fold("")(m => s" for $m")}: ${loaded.ranges} ranges")
            yield ()
          .ensuring:
            // Best effort: a leftover temporary file is a few megabytes in a directory the operator owns.
            ZIO.attemptBlocking(Files.deleteIfExists(tmp)).catchAllCause(ZIO.logWarningCause(s"could not remove the temporary $label download", _))

    /** Downloads `from` to `tmp` within the bounds, and reads it as a database (not yet taken into use). */
    private def fetch(from: URL, tmp: Path): Task[RangeDatabase[A]] =
      for
        _ <- client
          .stream(Request.get(from)): response =>
            if response.status == Status.NotFound then ZStream.fail(NotFound())
            else if !response.status.isSuccess then ZStream.fail(RuntimeException(s"the server answered ${response.status.code}"))
            else response.body.asStream.take(MaxDownloadBytes + 1)
          .run(ZSink.fromPath(tmp))
          .timeoutFail(RuntimeException(s"the download took more than ${DownloadTimeout.render}"))(DownloadTimeout)
        size <- ZIO.attemptBlocking(Files.size(tmp))
        _ <- ZIO.fail(RuntimeException(s"the download is larger than $MaxDownloadBytes bytes")).when(size > MaxDownloadBytes)
        loaded <- read(tmp)
      yield loaded

    /** The download of the configured `url`; without one, the file again if it has changed (an operator replaces it). */
    private def refresh: UIO[Unit] = template match
      case Some(address) => download(address).catchAllCause(failed(s"could not download the $label from $host"))
      case None =>
        ZIO.attemptBlocking(Files.getLastModifiedTime(file).toMillis).option.flatMap: written =>
          ZIO.when(written.isDefined && written != loadedFrom.get)(adopt(file).catchAllCause(failed(s"could not read the $label file"))).unit

    /** Fetches at once if there is nothing to use yet, then every interval; a database that is not there yet is tried for again sooner. */
    val keepFresh: UIO[Nothing] =
      val pause = ZIO.suspendSucceed(
        ZIO.sleep(if database.get.isEmpty && source.refreshInterval > RetryWhileEmpty then RetryWhileEmpty else source.refreshInterval),
      )
      (ZIO.when(template.isDefined && database.get.isEmpty)(refresh) *> initial.succeed(()) *> (pause *> refresh).forever)

/** The metrics of one range database, under a name of their own: `antifraud_asn_...`, `antifraud_geo_...`. */
class RangeMetrics(prefix: String):
  val Hit = "hit"
  val Miss = "miss"
  val Unavailable = "unavailable"

  private val lookups = Metric.counter(s"${prefix}_lookups_total")
  private val loadedTimestamp = Metric.gauge(s"${prefix}_database_loaded_timestamp_seconds")
  private val ranges = Metric.gauge(s"${prefix}_database_ranges")
  private val refreshFailedTotal = Metric.counter(s"${prefix}_database_refresh_failed_total")

  private val tagged = Map(
    Hit -> lookups.tagged(Set(MetricLabel("result", Hit))),
    Miss -> lookups.tagged(Set(MetricLabel("result", Miss))),
    Unavailable -> lookups.tagged(Set(MetricLabel("result", Unavailable))),
  )

  /** `result` is `hit`, `miss` or `unavailable`: a closed set, since it is a label. */
  def lookup(result: String): UIO[Unit] = tagged(result).increment

  /** The database now in use: when its file was written (what a stale one is alerted on) and how many ranges it holds. */
  def loaded(writtenAt: Instant, rangeCount: Int): UIO[Unit] =
    loadedTimestamp.set(writtenAt.getEpochSecond.toDouble) *> ranges.set(rangeCount.toDouble)

  /** A download or a read that did not produce a database; the one in use stays. Alert on it increasing. */
  val refreshFailed: UIO[Unit] = refreshFailedTotal.increment
