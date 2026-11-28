package versola.edge

import versola.edge.CsrfGuard.Verdict
import zio.*
import zio.http.{Body, Method, Request, URL}
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.test.*

object CsrfReportSpec extends ZIOSpecDefault:

  private def call(headers: (String, String)*): Request =
    headers.foldLeft(Request(method = Method.POST, url = URL.empty / "resources" / "api", body = Body.empty))((r, h) => r.addHeader(h._1, h._2))

  private def counted(surface: String, result: String): UIO[Double] =
    Metric.counter("edge_cookie_origin_decisions_total").tagged(Set(MetricLabel("surface", surface), MetricLabel("result", result))).value.map(_.count)

  private def lines = ZTestLogger.logOutput.map(_.map(entry => entry.logLevel -> entry.message()))

  def spec = suite("CsrfReport")(
    test("a refusal is counted by result, and logged as a warning that names the origin and the preset") {
      val report = CsrfReport("spec-refusal")
      for
        _ <- report.record(Verdict.RefusedOrigin, call("Origin" -> "https://app.example.com"), "web")
        _ <- report.record(Verdict.RefusedFetchSite, call("Sec-Fetch-Site" -> "same-site"), "web")
        origin <- counted("spec-refusal", "refused_origin")
        site <- counted("spec-refusal", "refused_fetch_site")
        out <- lines
      yield assertTrue(
        origin == 1.0,
        site == 1.0,
        out.count((level, message) => level == LogLevel.Warning && message.contains("origin='https://app.example.com'") && message.contains("preset=web")) == 1,
        out.exists((_, message) => message.contains("Sec-Fetch-Site: same-site")),
      )
    },
    test("a call with no origin headers is counted and nothing is logged: there is no origin to name, and it is a success path") {
      val report = CsrfReport("spec-noheaders")
      for
        _ <- ZIO.foreachDiscard(1 to 5)(_ => report.record(Verdict.NoHeaders, call(), "web"))
        n <- counted("spec-noheaders", "no_headers")
        out <- lines
      yield assertTrue(n == 5.0, out.isEmpty)
    },
    test("an allowed call is neither counted nor logged") {
      val report = CsrfReport("spec-allowed")
      for
        _ <- report.record(Verdict.Allowed, call("Origin" -> "https://edge.example.com"), "web")
        out <- lines
        n <- counted("spec-allowed", "refused_origin")
      yield assertTrue(out.isEmpty, n == 0.0)
    },
    test("the same origin is logged once a minute however often it is refused, and every refusal is counted") {
      val report = CsrfReport("spec-window")
      val refused = call("Origin" -> "https://app.example.com")
      for
        _ <- ZIO.foreachDiscard(1 to 20)(_ => report.record(Verdict.RefusedOrigin, refused, "web"))
        first <- lines.map(_.size)
        _ <- TestClock.adjust(61.seconds)
        _ <- report.record(Verdict.RefusedOrigin, refused, "web")
        second <- lines.map(_.size)
        n <- counted("spec-window", "refused_origin")
      yield assertTrue(first == 1, second == 2, n == 21.0)
    },
    test("an attacker who writes a new origin every time cannot grow what is remembered or flood the log: past the bound it is counted, and one line a minute says the names are being left out") {
      val report = CsrfReport("spec-bound")
      for
        _ <- ZIO.foreachDiscard(1 to CsrfReport.MaxKeys + 50)(n => report.record(Verdict.RefusedOrigin, call("Origin" -> s"https://h$n.example.com"), "web"))
        out <- lines
        n <- counted("spec-bound", "refused_origin")
        named = out.count((_, message) => message.contains("origin='https://h"))
        saturated = out.count((_, message) => message.contains("further origins are counted and not named"))
        _ <- TestClock.adjust(61.seconds)
        _ <- report.record(Verdict.RefusedOrigin, call("Origin" -> "https://fresh.example.com"), "web")
        after <- lines
      yield assertTrue(
        named == CsrfReport.MaxKeys,
        saturated == 1,
        n == (CsrfReport.MaxKeys + 50).toDouble,
        after.exists((_, message) => message.contains("origin='https://fresh.example.com'")),
      )
    },
    test("what a caller wrote is made fit for a log line: printable ASCII, no quote or backslash, at most 100 characters") {
      val written = "https://a.example\u0000\u001b[31m'\\" + "x" * 200
      val shown = CsrfReport.printable(written)
      assertTrue(shown.length == 100, shown.forall(c => c >= ' ' && c <= '~' && c != '\'' && c != '\\'))
    },
  ) @@ TestAspect.sequential
