package versola.util.postgres

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/** TLS policy for connections to Postgres.
  *
  * The JDBC URL is operator-supplied, and pgjdbc's own default (`sslmode=prefer`) encrypts when
  * the server offers it but never verifies who answered, so it is exactly as strong as a plain
  * connection against an attacker on the path. Every connection this codebase opens therefore
  * gets `sslmode=verify-full` unless the URL says otherwise, and a URL that does say otherwise
  * is reported by [[weakness]] -- logged as a warning, and a refusal to start in prod.
  *
  * URL parameters are read the way pgjdbc reads them: names are case-sensitive, names and values
  * are URL-decoded, and when a parameter repeats the last occurrence wins. Anything looser would
  * let the check see a different URL than the driver connects with.
  */
object PostgresTls:
  val DefaultSslMode = "verify-full"

  /** Trust the JVM's default trust store. Without a `sslrootcert`, pgjdbc's own default factory
    * reads `~/.postgresql/root.crt` and fails the connection when that file is absent, so a
    * certificate the JVM already trusts would be rejected.
    */
  private val JvmTrustStoreFactory = "org.postgresql.ssl.DefaultJavaSSLFactory"
  private val CertificateVerifyingFactories = Set("org.postgresql.ssl.LibPQFactory", JvmTrustStoreFactory)

  private val verifying = Set("verify-ca", "verify-full")
  private val loopback = Set("localhost", "127.0.0.1", "[::1]")

  /** Driver properties to apply on top of `url`. pgjdbc lets the URL override them, so an
    * operator's explicit `sslmode`/`sslrootcert`/`sslfactory` always wins.
    */
  def properties(url: String, sslRootCert: Option[String]): Properties =
    val params = parameters(url)
    val result = Properties()
    if !params.contains("sslmode") && !params.contains("ssl") then
      result.setProperty("sslmode", DefaultSslMode)
    if !params.contains("sslrootcert") then
      sslRootCert.foreach(result.setProperty("sslrootcert", _))
    if !params.contains("sslfactory") && !params.contains("sslrootcert") && sslRootCert.isEmpty then
      result.setProperty("sslfactory", JvmTrustStoreFactory)
    result

  /** The mode the connection will actually use. */
  def sslMode(url: String): String =
    val params = parameters(url)
    params.get("sslmode").map(_.toLowerCase).getOrElse:
      if params.get("ssl").exists(_.equalsIgnoreCase("false")) then "disable" else DefaultSslMode

  /** Why this URL does not give a verified TLS connection, or None if it does. Connections to a
    * loopback host never leave the machine, so they are not reported.
    */
  def weakness(url: String): Option[String] =
    val mode = sslMode(url)
    val factory = parameters(url).get("sslfactory")
    if isLoopback(url) then None
    else if !verifying(mode) then
      Some(s"sslmode=$mode: the database's certificate is not verified, use sslmode=verify-full")
    else
      factory.filterNot(CertificateVerifyingFactories).map: name =>
        s"sslfactory=$name may accept certificates without validating them, " +
          "use the default factory and set ssl-root-cert for a private CA"

  private def isLoopback(url: String): Boolean =
    val params = parameters(url)
    // A `host` parameter replaces the authority's host when pgjdbc connects.
    val authority = params.get("host").orElse(params.get("PGHOST")).getOrElse:
      url.stripPrefix("jdbc:postgresql://").takeWhile(c => c != '/' && c != '?')
    // A list of hosts is only loopback if every entry is.
    authority.split(",").forall(host => loopback(host.trim.replaceAll(":\\d+$", "").toLowerCase))

  private def decode(value: String): String =
    try URLDecoder.decode(value, StandardCharsets.UTF_8)
    catch case _: IllegalArgumentException => value

  private def parameters(url: String): Map[String, String] =
    url.split("\\?", 2).lift(1).toList
      .flatMap(_.split("[&;]"))
      .filter(_.nonEmpty)
      .map: pair =>
        pair.split("=", 2) match
          case Array(key, value) => decode(key) -> decode(value)
          case Array(key) => decode(key) -> ""
      // Later pairs replace earlier ones, as in pgjdbc
      .toMap
