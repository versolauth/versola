//> using scala 3.8.1
//> using dep org.scala-lang.modules::scala-xml:2.4.0

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.xml.XML

val Output = "coverage.json"

/** Locate the aggregated scoverage report produced by `sbt coverageAggregate`. */
def findReport(): Path =
  Using.resource(Files.walk(Paths.get("."))) { stream =>
    stream
      .iterator()
      .asScala
      .filter(p =>
        p.getFileName.toString == "scoverage.xml"
          && Option(p.getParent).exists(_.getFileName.toString == "scoverage-report")
      )
      .toList
      .sortBy(_.toString)
      .headOption
      .getOrElse {
        System.err.println("No scoverage report found")
        sys.exit(1)
      }
  }

def statementRate(path: Path): Double =
  (XML.loadFile(path.toFile) \ "@statement-rate").text.toDouble

def colorFor(pct: Double): String =
  if pct >= 90 then "brightgreen"
  else if pct >= 80 then "green"
  else if pct >= 70 then "yellowgreen"
  else if pct >= 60 then "yellow"
  else if pct >= 50 then "orange"
  else "red"

def jsonEscape(s: String): String =
  s.flatMap {
    case '"'  => "\\\""
    case '\\' => "\\\\"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c    => c.toString
  }

/** Render a shields.io endpoint badge payload. */
def render(pct: Double): String =
  val message = s"${pct.round}%"
  s"""{"schemaVersion":1,"label":"coverage","message":"$message","color":"${colorFor(pct)}"}"""

def publishToGist(content: String): Unit =
  val gistId = sys.env("GIST_ID")
  val token = sys.env("GIST_TOKEN")
  val filename = sys.env.getOrElse("GIST_FILENAME", Output)
  val body =
    s"""{"files":{"${jsonEscape(filename)}":{"content":"${jsonEscape(content)}"}}}"""
  val request = HttpRequest
    .newBuilder()
    .uri(URI.create(s"https://api.github.com/gists/$gistId"))
    .header("Authorization", s"Bearer $token")
    .header("Accept", "application/vnd.github+json")
    .header("User-Agent", "coverage-badge")
    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
    .build()
  val response =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
  if response.statusCode() >= 300 then
    System.err.println(s"Gist update failed: ${response.statusCode()} ${response.body()}")
    sys.exit(1)
  println(s"Updated gist $gistId file $filename")

@main def run(): Unit =
  val report = findReport()
  val pct = statementRate(report)
  val content = render(pct)
  if sys.env.contains("GIST_ID") && sys.env.contains("GIST_TOKEN") then publishToGist(content)
  else
    Files.writeString(Paths.get(Output), content)
    println(s"Wrote $Output (${pct.round}% from $report)")
