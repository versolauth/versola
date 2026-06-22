package versola.oauth.session.model

import zio.json.JsonCodec

import scala.util.matching.Regex

case class UserAgentInfo(
    platform: String,
    os: Option[String],
    browser: Option[String],
    version: Option[String],
) derives JsonCodec

object UserAgentInfo:
  private val unknown = UserAgentInfo("unknown", None, None, None)

  private val ntMap = Map("10.0" -> "10 / 11", "6.3" -> "8.1", "6.2" -> "8", "6.1" -> "7")

  private val iosRe: Regex = "(?i)iPhone|iPad|iPod".r
  private val androidRe: Regex = "(?i)Android".r
  private val desktopRe: Regex = "(?i)Windows|Macintosh|Linux|X11".r

  private val windowsNtRe: Regex = """Windows NT (\d+\.\d+)""".r
  private val macOsRe: Regex = """Mac OS X ([\d_]+)""".r
  private val crosRe: Regex = "(?i)CrOS".r
  private val androidOsRe: Regex = """(?i)Android ([\d.]+)""".r
  private val iosOsRe: Regex = """(?i)iPhone OS ([\d_]+)""".r
  private val linuxRe: Regex = "(?i)Linux".r

  private val edgeRe: Regex = """Edg/(\d+)""".r
  private val firefoxRe: Regex = """Firefox/(\d+)""".r
  private val chromeRe: Regex = """Chrome/(\d+)""".r
  private val safariVersionRe: Regex = """(?i)Version/(\d+).*Safari""".r
  private val safariRe: Regex = "(?i)Safari".r

  def parse(userAgent: Option[String]): UserAgentInfo =
    userAgent.map(_.trim).filter(_.nonEmpty).fold(unknown)(parse)

  def parse(ua: String): UserAgentInfo =
    val platform =
      if iosRe.findFirstMatchIn(ua).isDefined then "ios"
      else if androidRe.findFirstMatchIn(ua).isDefined then "android"
      else if desktopRe.findFirstMatchIn(ua).isDefined then "desktop"
      else "unknown"

    val os =
      windowsNtRe.findFirstMatchIn(ua).map(m => "Windows " + ntMap.getOrElse(m.group(1), m.group(1)))
        .orElse(macOsRe.findFirstMatchIn(ua).map(m => "macOS " + m.group(1).replace('_', '.')))
        .orElse(crosRe.findFirstMatchIn(ua).map(_ => "ChromeOS"))
        .orElse(androidOsRe.findFirstMatchIn(ua).map(m => "Android " + m.group(1)))
        .orElse(iosOsRe.findFirstMatchIn(ua).map(m => "iOS " + m.group(1).replace('_', '.')))
        .orElse(linuxRe.findFirstMatchIn(ua).map(_ => "Linux"))

    val (browser, version) =
      edgeRe.findFirstMatchIn(ua).map(m => ("Edge", Option(m.group(1))))
        .orElse(firefoxRe.findFirstMatchIn(ua).map(m => ("Firefox", Option(m.group(1)))))
        .orElse(chromeRe.findFirstMatchIn(ua).map(m => ("Chrome", Option(m.group(1)))))
        .orElse(safariVersionRe.findFirstMatchIn(ua).map(m => ("Safari", Option(m.group(1)))))
        .orElse(safariRe.findFirstMatchIn(ua).map(_ => ("Safari", None)))
        .map((b, v) => (Option(b), v))
        .getOrElse((None, None))

    UserAgentInfo(platform, os, browser, version)
