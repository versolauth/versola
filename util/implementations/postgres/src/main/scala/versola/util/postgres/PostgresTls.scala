package versola.util.postgres

import java.util.Properties

/** TLS policy for connections to Postgres.
  *
  * The JDBC URL is operator-supplied, and pgjdbc's own default (`sslmode=prefer`) encrypts when
  * the server offers it but never verifies who answered, so it is exactly as strong as a plain
  * connection against an attacker on the path. Every connection this codebase opens therefore
  * gets `sslmode=verify-full` unless the URL says otherwise, and a URL that does say otherwise
  * is reported by [[weakness]] -- logged as a warning, and a refusal to start in prod.
  */
object PostgresTls:
  val DefaultSslMode = "verify-full"

  private val verifying = Set("verify-ca", "verify-full")
  private val loopback = Set("localhost", "127.0.0.1", "[::1]")

  /** Driver properties to apply on top of `url`. pgjdbc lets the URL override them, so an
    * operator's explicit `sslmode`/`sslrootcert` always wins.
    */
  def properties(url: String, sslRootCert: Option[String]): Properties =
    val result = Properties()
    if param(url, "sslmode").isEmpty && param(url, "ssl").isEmpty then
      result.setProperty("sslmode", DefaultSslMode)
    if param(url, "sslrootcert").isEmpty then
      sslRootCert.foreach(result.setProperty("sslrootcert", _))
    result

  /** The mode the connection will actually use. */
  def sslMode(url: String): String =
    param(url, "sslmode").map(_.toLowerCase).getOrElse:
      if param(url, "ssl").exists(_.equalsIgnoreCase("false")) then "disable" else DefaultSslMode

  /** Why this URL does not give a verified TLS connection, or None if it does. Connections to a
    * loopback host never leave the machine, so they are not reported.
    */
  def weakness(url: String): Option[String] =
    val mode = sslMode(url)
    Option.unless(verifying(mode) || isLoopback(url)):
      s"sslmode=$mode: the database's certificate is not verified, use sslmode=verify-full"

  private def isLoopback(url: String): Boolean =
    val authority = url.stripPrefix("jdbc:postgresql://").takeWhile(c => c != '/' && c != '?')
    // Only the first host of a multi-host URL is looked at: a mixed list is never loopback-only.
    !authority.contains(",") && loopback(authority.replaceAll(":\\d+$", "").toLowerCase)

  private def param(url: String, name: String): Option[String] =
    url.split("\\?", 2).lift(1).toList
      .flatMap(_.split("[&;]"))
      .flatMap: pair =>
        pair.split("=", 2) match
          case Array(key, value) if key.equalsIgnoreCase(name) => Some(value)
          case _ => None
      .headOption
