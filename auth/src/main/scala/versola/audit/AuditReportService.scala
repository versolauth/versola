package versola.audit

import versola.audit.AuditReadRepository.Summary
import versola.util.PageLimit
import versola.util.audit.{AuditEvent, AuditEvents}
import versola.util.net.GeoResolver
import zio.*
import zio.json.ast.Json
import zio.json.{DeriveJsonDecoder, JsonCodec, JsonDecoder, JsonEncoder, jsonExplicitNull}

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** A request for the per-user report that cannot be served as asked. `code` is the `setError` code. */
enum AuditReportError(val code: String, val message: String):
  case WindowOutsideRetention
    extends AuditReportError("audit_window_outside_retention", "from is before the start of the retained window, or to is in the future")
  case WindowReversed extends AuditReportError("audit_window_reversed", "to is before from")
  case Busy extends AuditReportError("audit_report_busy", "too many reports are being read; retry shortly")

/** The per-user report of auth's audit log, which central serves to an administrator: a page of the events about one user, and a
  * summary of them, over a window of the hot retention. Each read is one grouped query per block over the user's index range. */
trait AuditReportService:
  def events(
      userId: UUID,
      from: Option[Instant],
      to: Option[Instant],
      cursor: Option[AuditCursor],
      limit: Option[PageLimit],
  ): IO[Throwable | AuditReportError, AuditReportService.EventsPage]

  def summary(userId: UUID, from: Option[Instant], to: Option[Instant]): IO[Throwable | AuditReportError, AuditReportService.SummaryReport]

object AuditReportService:
  /** How many reports are read from the database at once, and how long one waits for its turn before it is refused (503 `audit_report_busy`).
    * The reads share the database with the writer. */
  val ReadConcurrency = 4
  val ReadWait: Duration = 2.seconds

  /** How far ahead of auth's clock `to` may be: central's clock is not auth's, and `to = now` there is a little later here. */
  val ClockSkew: Duration = 1.minute

  /** `nextCursor` is `null`, not left out, on the last page: central codes against that. */
  /** An event as the report serves it: the stored event, flat, and the `country` of its address when the country database of
    * `antifraud.geo` is loaded and has one. The country is looked up when the report is read and is not stored. */
  case class ReportEvent(event: AuditEvent, country: Option[String])

  object ReportEvent:
    given JsonCodec[ReportEvent] = JsonCodec(
      JsonEncoder[Json.Obj].contramap: item =>
        val stored = JsonEncoder[AuditEvent].toJsonAST(item.event).toOption.flatMap(_.asObject).getOrElse(Json.Obj())
        item.country.fold(stored)(code => Json.Obj(stored.fields :+ ("country" -> Json.Str(code))))
      ,
      JsonDecoder[Json.Obj].mapOrFail: obj =>
        obj.as[AuditEvent].map(event => ReportEvent(event, obj.get("country").flatMap(_.asString))),
    )

  case class EventsPage(events: List[ReportEvent], @jsonExplicitNull nextCursor: Option[String], retentionFrom: Instant) derives JsonCodec
  case class Window(from: Instant, to: Instant, retentionFrom: Instant) derives JsonCodec

  case class SummaryReport(
      window: Window,
      byKind: List[AuditReadRepository.KindCount],
      byClient: List[AuditReadRepository.ClientCount],
      ips: List[AuditReadRepository.IpCount],
      userAgents: List[AuditReadRepository.UserAgentCount],
      sessions: AuditReadRepository.SessionCount,
      countries: List[CountryCount],
      factors: List[AuditReadRepository.Factor],
      high: List[ReportEvent],
  ) derives JsonCodec

  /** The addresses of `ips` grouped by their country, most used first. */
  case class CountryCount(country: String, firstSeen: Instant, lastSeen: Instant, count: Long) derives JsonCodec

  /** The countries of the addresses of the summary (so as many as `ips` holds, at most), most used first, then by code. */
  def countriesOfIps(ips: List[AuditReadRepository.IpCount]): List[CountryCount] =
    ips.flatMap(entry => entry.country.map(entry -> _)).groupBy(_._2).toList.map: (country, entries) =>
      CountryCount(
        country = country,
        firstSeen = entries.map(_._1.firstSeen).min,
        lastSeen = entries.map(_._1.lastSeen).max,
        count = entries.map(_._1.count).sum,
      )
    .sortBy(entry => (-entry.count, entry.country))

  val live: ZLayer[AuditReadRepository & AuditEvents.Settings & GeoResolver, Nothing, AuditReportService] =
    ZLayer.fromZIO:
      for
        repository <- ZIO.service[AuditReadRepository]
        settings <- ZIO.service[AuditEvents.Settings]
        geo <- ZIO.service[GeoResolver]
        reads <- Semaphore.make(ReadConcurrency)
      yield Impl(repository, settings, geo, reads)

  class Impl(repository: AuditReadRepository, settings: AuditEvents.Settings, geo: GeoResolver, reads: Semaphore) extends AuditReportService:

    /** The country of each of the addresses, looked up in the database in memory without counting a lookup (`peek`); an address with
      * none (no database loaded, a private or unknown address, or no address) is left out. */
    private def countriesOf(ips: Iterable[String]): UIO[Map[String, String]] =
      ZIO.foreach(ips.toList.distinct)(ip => geo.peek(ip).map(country => country.map(ip -> _))).map(_.flatten.toMap)

    private def withCountries(events: List[AuditEvent]): UIO[List[ReportEvent]] =
      countriesOf(events.flatMap(_.ip)).map(found => events.map(event => ReportEvent(event, event.ip.flatMap(found.get))))

    /** Runs a read when one of the `ReadConcurrency` places is free, waiting up to `ReadWait` for it. */
    private def gated[A](read: IO[Throwable | AuditReportError, A]): IO[Throwable | AuditReportError, A] =
      ZIO.scoped:
        reads.withPermitScoped.timeout(ReadWait).flatMap:
          case Some(_) => read
          case None => ZIO.fail(AuditReportError.Busy)

    /** The window asked for: `from` defaults to the start of the retained window and `to` to now. Outside the retained window the
      * rows are partly or wholly gone, so the answer would look complete and not be: refused rather than clipped. */
    private def window(from: Option[Instant], to: Option[Instant]): IO[AuditReportError, Window] =
      Clock.instant.flatMap: now =>
        val retentionFrom = now.minusSeconds(settings.retention.toSeconds)
        val start = from.getOrElse(retentionFrom)
        val end = to.getOrElse(now)
        if end.isBefore(start) then ZIO.fail(AuditReportError.WindowReversed)
        else if start.isBefore(retentionFrom) || end.isAfter(now.plusSeconds(ClockSkew.toSeconds)) then
          ZIO.fail(AuditReportError.WindowOutsideRetention)
        else ZIO.succeed(Window(start, end, retentionFrom))

    override def events(
        userId: UUID,
        from: Option[Instant],
        to: Option[Instant],
        cursor: Option[AuditCursor],
        limit: Option[PageLimit],
    ): IO[Throwable | AuditReportError, EventsPage] =
      for
        window <- window(from, to)
        size: Int = limit.getOrElse(PageLimit.Default)
        // One more than a page: whether there is a next page is whether it came back.
        found <- gated(repository.userEvents(userId, window.from, window.to, cursor, size + 1))
        page = found.take(size)
        shown <- withCountries(page)
      yield EventsPage(
        events = shown,
        nextCursor = Option.when(found.size > size)(page.lastOption.map(last => AuditCursor(last.at, last.id).render)).flatten,
        retentionFrom = window.retentionFrom,
      )

    override def summary(userId: UUID, from: Option[Instant], to: Option[Instant]): IO[Throwable | AuditReportError, SummaryReport] =
      for
        window <- window(from, to)
        found <- gated(repository.userSummary(userId, window.from, window.to))
        countries <- countriesOf(found.ips.map(_.ip) ++ found.high.flatMap(_.ip))
        ips = found.ips.map(entry => entry.copy(country = countries.get(entry.ip)))
        high <- withCountries(found.high)
      yield SummaryReport(
        window = window,
        byKind = found.byKind,
        byClient = found.byClient,
        ips = ips,
        userAgents = found.userAgents,
        sessions = found.sessions,
        countries = countriesOfIps(ips),
        factors = found.factors,
        high = high,
      )
