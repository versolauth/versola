package versola.util.net

import zio.*
import zio.http.{Client, Request, URL}
import zio.stream.{ZSink, ZStream}

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference

/** The ASN database in use: the one read from the configured file, replaced when a fresher one has been downloaded or the file
  * has changed. What it is asked for is always in memory (`current`); reading and downloading happen in the background, and
  * a failure of either keeps the database there is and says so in the log and in
  * `antifraud_asn_database_refresh_failed_total` (a database that is never refreshed is never a reason to refuse a request).
  */
trait AsnDatabaseLoader:
  /** `None` while no database is loaded: the feature is off, or its file is not there yet. */
  def current: Option[AsnDatabase]

  /** Completes once the first fetch is over, whatever it came to: at once when none is needed (the file was read, or there
    * is no `url`). What a test or a readiness check waits for, instead of polling `current`. */
  def awaitInitial: UIO[Unit] = ZIO.unit

  /** Does now what the background loop does every interval (download the `url`, or read the file again if it changed),
    * and completes when that is done. Never fails: a failure is the same warning and counter as in the loop. */
  def refreshNow: UIO[Unit] = ZIO.unit

object AsnDatabaseLoader:

  val off: AsnDatabaseLoader = new AsnDatabaseLoader:
    override def current: Option[AsnDatabase] = None

  def fixed(database: AsnDatabase): AsnDatabaseLoader = new AsnDatabaseLoader:
    override def current: Option[AsnDatabase] = Some(database)

  /** The most a download may take, and may be: a database is some tens of megabytes. */
  val DownloadTimeout: Duration = 5.minutes
  val MaxDownloadBytes: Long = 256L << 20

  /** How soon a database that is not there yet is tried for again, if the interval is longer. */
  val RetryWhileEmpty: Duration = 5.minutes

  /** More malformed lines than this share of the lines make a file not a database but a wrong file (an error page, a
    * different format); it is not used. */
  private val MaxMalformedShare = 0.1

  /** Reads the database the settings name and starts keeping it fresh: the file is read before this returns, so that the
    * first request already finds it; the download, if there is a `url`, runs in the background (at once when there is no
    * file yet), then every `refresh-interval`. Never fails: a file that cannot be read is a warning and no database. */
  def start(settings: AsnSettings): ZIO[Client & Scope, Nothing, AsnDatabaseLoader] =
    if !settings.enabled then ZIO.succeed(off)
    else
      for
        client <- ZIO.service[Client]
        initial <- Promise.make[Nothing, Unit]
        loader = Impl(settings, Paths.get(settings.file.trim), client, initial)
        _ <- loader.loadFile
        _ <- loader.keepFresh.forkScoped
      yield loader

  private class Impl(settings: AsnSettings, file: Path, client: Client, initial: Promise[Nothing, Unit]) extends AsnDatabaseLoader:
    private val database = AtomicReference[Option[AsnDatabase]](None)

    /** The modification time of the file the database in use was read from, to tell a file that changed. */
    private val loadedFrom = AtomicReference[Option[Long]](None)

    private val url: Option[URL] = Option(settings.url.trim).filter(_.nonEmpty).flatMap(URL.decode(_).toOption)

    override def current: Option[AsnDatabase] = database.get

    override def awaitInitial: UIO[Unit] = initial.await

    override def refreshNow: UIO[Unit] = refresh

    /** Reads `path` as a database, or fails if it is not one. It is not taken into use here: the caller does that once the
      * file it came from is where `file` is, so that what is in memory is always what a restart would read. */
    private def read(path: Path): Task[AsnDatabase] =
      for
        loaded <- ZIO.attemptBlocking(AsnDatabase.read(path))
        total = loaded.database.ranges + loaded.malformed + loaded.notAnnounced
        _ <- ZIO
          .fail(RuntimeException(s"not an ASN database: ${loaded.database.ranges} ranges and ${loaded.malformed} malformed lines"))
          .when(loaded.database.ranges == 0 || loaded.malformed > MaxMalformedShare * total)
        _ <- ZIO.logWarning(s"${loaded.malformed} lines of the ASN database are not valid ranges and were left out").when(loaded.malformed > 0)
      yield loaded.database

    /** Takes `loaded` into use as the contents of `file`, written at `written`. */
    private def use(loaded: AsnDatabase, written: FileTime): UIO[Unit] =
      ZIO.succeed {
        database.set(Some(loaded))
        loadedFrom.set(Some(written.toMillis))
      } *> AsnMetrics.loaded(written.toInstant, loaded.ranges)

    /** Reads `file` and takes it into use. */
    private def adopt(path: Path): Task[Unit] =
      for
        loaded <- read(path)
        written <- ZIO.attemptBlocking(Files.getLastModifiedTime(path))
        _ <- use(loaded, written)
        _ <- ZIO.logInfo(s"ASN database loaded: ${loaded.ranges} ranges")
      yield ()

    private def failed(what: String)(cause: Cause[Throwable]): UIO[Unit] =
      AsnMetrics.refreshFailed *> ZIO.logWarningCause(
        s"$what: ${if database.get.isDefined then "keeping the previous ASN database" else "no ASN database is loaded"}",
        cause,
      )

    /** The file, if it is there. A file that is not there is not a failure while a download is to fetch it. */
    val loadFile: UIO[Unit] =
      ZIO.attemptBlocking(Files.exists(file)).orElseSucceed(false).flatMap: exists =>
        if exists then adopt(file).catchAllCause(failed("could not read the ASN database file"))
        else if url.isEmpty then ZIO.logWarning("the ASN database file is not there and no url is configured: no ASN database is loaded")
        else ZIO.unit

    /** Downloads to a file next to `file` and moves it over `file` in one step, so that nothing ever reads half a file, and
      * only once it has been read as a database, so that a wrong one does not replace a good one. */
    private def download(from: URL): Task[Unit] =
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
              _ <- ZIO.logInfo(s"ASN database downloaded from ${from.host.getOrElse("")}: ${loaded.ranges} ranges")
            yield ()
          .ensuring:
            // Best effort: a leftover temporary file is a few megabytes in a directory the operator owns.
            ZIO.attemptBlocking(Files.deleteIfExists(tmp)).catchAllCause(ZIO.logWarningCause("could not remove the temporary ASN download", _))

    /** Downloads `from` to `tmp` within the bounds, and reads it as a database (not yet taken into use). */
    private def fetch(from: URL, tmp: Path): Task[AsnDatabase] =
      for
        _ <- client
          .stream(Request.get(from)): response =>
            if !response.status.isSuccess then ZStream.fail(RuntimeException(s"the server answered ${response.status.code}"))
            else response.body.asStream.take(MaxDownloadBytes + 1)
          .run(ZSink.fromPath(tmp))
          .timeoutFail(RuntimeException(s"the download took more than ${DownloadTimeout.render}"))(DownloadTimeout)
        size <- ZIO.attemptBlocking(Files.size(tmp))
        _ <- ZIO.fail(RuntimeException(s"the download is larger than $MaxDownloadBytes bytes")).when(size > MaxDownloadBytes)
        loaded <- read(tmp)
      yield loaded

    /** The download of the configured `url`; without one, the file again if it has changed (an operator replaces it). */
    private def refresh: UIO[Unit] = url match
      case Some(from) =>
        // The address is not logged whole: an operator's mirror may carry a token in it.
        download(from).catchAllCause(failed(s"could not download the ASN database from ${from.host.getOrElse("")}"))
      case None =>
        ZIO.attemptBlocking(Files.getLastModifiedTime(file).toMillis).option.flatMap: written =>
          ZIO.when(written.isDefined && written != loadedFrom.get)(adopt(file).catchAllCause(failed("could not read the ASN database file"))).unit

    /** Fetches at once if there is nothing to use yet, then every interval; a database that is not there yet is tried for again sooner. */
    val keepFresh: UIO[Nothing] =
      val pause = ZIO.suspendSucceed(ZIO.sleep(if database.get.isEmpty && settings.refreshInterval > RetryWhileEmpty then RetryWhileEmpty
      else settings.refreshInterval))
      (ZIO.when(url.isDefined && database.get.isEmpty)(refresh) *> initial.succeed(()) *> (pause *> refresh).forever)
