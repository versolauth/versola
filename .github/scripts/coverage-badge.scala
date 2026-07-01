//> using scala 3.8.1
//> using dep org.scala-lang.modules::scala-xml:2.4.0
//> using dep com.lihaoyi::requests:0.9.0
//> using dep com.lihaoyi::os-lib:0.11.4

import scala.xml.XML

val Output = "coverage.json"

/** Locate the aggregated scoverage report produced by `sbt coverageAggregate`. */
def findReport(): os.Path =
  os.walk(os.pwd)
    .filter(p => p.last == "scoverage.xml" && p.segments.contains("scoverage-report"))
    .sortBy(_.toString)
    .headOption
    .getOrElse {
      System.err.println("No scoverage report found")
      sys.exit(1)
    }

def statementRate(path: os.Path): Double =
  (XML.loadFile(path.toIO) \ "@statement-rate").text.toDouble

def colorFor(pct: Double): String =
  if pct >= 90 then "brightgreen"
  else if pct >= 80 then "green"
  else if pct >= 70 then "yellowgreen"
  else if pct >= 60 then "yellow"
  else if pct >= 50 then "orange"
  else "red"

/** Render a shields.io endpoint badge payload. */
def render(pct: Double): String =
  val message = s"${pct.round}%"
  ujson.Obj(
    "schemaVersion" -> 1,
    "label"         -> "coverage",
    "message"       -> message,
    "color"         -> colorFor(pct)
  ).toString()

def publishToGist(content: String): Unit =
  val gistId   = sys.env("GIST_ID")
  val token    = sys.env("GIST_TOKEN")
  val filename = sys.env.getOrElse("GIST_FILENAME", Output)
  val body     = ujson.Obj("files" -> ujson.Obj(filename -> ujson.Obj("content" -> content)))
  val response = requests.patch(
    s"https://api.github.com/gists/$gistId",
    headers = Map(
      "Authorization" -> s"Bearer $token",
      "Accept"        -> "application/vnd.github+json",
      "User-Agent"    -> "coverage-badge"
    ),
    data = body.toString()
  )
  println(s"Updated gist $gistId file $filename (${response.statusCode})")

@main def run(): Unit =
  val report  = findReport()
  val pct     = statementRate(report)
  val content = render(pct)
  if sys.env.contains("GIST_ID") && sys.env.contains("GIST_TOKEN") then publishToGist(content)
  else
    os.write.over(os.pwd / Output, content)
    println(s"Wrote $Output (${pct.round}% from $report)")
