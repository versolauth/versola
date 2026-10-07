package versola.migrate

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Keeps each edge-fronted client's `edgeClientCertificate` in central equal to the certificate
  * its issuer (cert-manager, step-ca, anything that writes PEM files) last produced -- the
  * automation #421 left as a follow-up. Without it, every renewal leaves central handing edge
  * the expired certificate until a human PUTs the new one.
  *
  * One directory per client under `CERT_SYNC_DIR`, named by the client id and holding the files
  * the issuer writes:
  * {{{
  *   $CERT_SYNC_DIR/<client-id>/tls.crt   certificate chain, leaf first
  *   $CERT_SYNC_DIR/<client-id>/tls.key   its unencrypted PKCS#8 key
  * }}}
  * which is exactly the layout of a mounted `kubernetes.io/tls` Secret, so a Pod mounts one
  * Secret per client and nothing else. A directory missing either file is skipped, so a client
  * whose first certificate is still being issued does not stop the others.
  *
  * Each pass reads every directory and PUTs `PUT /configuration/clients` -- `edgeClientCertificate`
  * only, everything else left as it is -- for the ones whose content differs from what this
  * process last pushed. The first pass therefore pushes everything, which is harmless: central
  * stores what it is given, and a restart of this process is how a drifted value is repaired.
  *
  * Environment:
  *   - `CENTRAL_URL`: central's base URL.
  *   - `CENTRAL_SECRET`, `CENTRAL_SECRET_FILE` or `CENTRAL_RESOURCE_SECRET` (what central's own
  *     `*.secrets.env` names it, so that file can be an `env_file` here): the resource secret
  *     central's admin API takes as HTTP Basic (`central:<secret>`), base64url as central issued it.
  *   - `CERT_SYNC_DIR`: defaults to `/certs`.
  *   - `CERT_SYNC_INTERVAL_SECONDS`: pause between passes, default 300. `0` runs one pass and
  *     exits non-zero if any client failed, for use as a Job or a renewal hook.
  *
  * Plain synchronous JDK code, like [[MigrateTool]], so it adds nothing to this image's classpath.
  */
object CertSyncTool:

  /** What a PUT carries. Every other field of `UpdateClientRequest` is optional or an empty
    * add/remove set, so the client is otherwise untouched. */
  def requestBody(clientId: String, pem: String): String =
    s"""{"clientId":${jsonString(clientId)},""" +
      """"redirectUris":{"add":[],"remove":[]},"scope":{"add":[],"remove":[]},""" +
      """"permissions":{"add":[],"remove":[]},""" +
      s""""edgeClientCertificate":${jsonString(pem)}}"""

  def jsonString(value: String): String =
    val out = StringBuilder("\"")
    value.foreach:
      case '"' => out ++= "\\\""
      case '\\' => out ++= "\\\\"
      case '\n' => out ++= "\\n"
      case '\r' => out ++= "\\r"
      case '\t' => out ++= "\\t"
      case c if c < ' ' => out ++= f"\\u${c.toInt}%04x"
      case c => out += c
    out += '"'
    out.toString

  /** The clients to sync: `(clientId, pem)` for every directory holding both files. */
  def readClients(dir: Path): List[(String, String)] =
    if !Files.isDirectory(dir) then Nil
    else
      // Closed after the listing: a long-running loop would otherwise leak a directory handle per pass.
      val entries = scala.util.Using.resource(Files.list(dir).nn)(_.iterator.nn.asScala.toList)
      entries.sortBy(_.toString).flatMap: clientDir =>
        val crt = clientDir.resolve("tls.crt")
        val key = clientDir.resolve("tls.key")
        Option.when(Files.isDirectory(clientDir) && Files.isRegularFile(crt) && Files.isRegularFile(key)):
          val pem = Files.readString(crt).trim + "\n" + Files.readString(key).trim + "\n"
          (clientDir.getFileName.toString, pem)

  private def digest(pem: String): String =
    MessageDigest.getInstance("SHA-256").nn.digest(pem.getBytes(StandardCharsets.UTF_8)).nn
      .map(b => f"${b & 0xff}%02x").mkString

  private def required(name: String): String =
    sys.env.get(name).filter(_.nonEmpty).getOrElse:
      System.err.println(s"cert-sync: $name is required")
      sys.exit(2)

  private def secret(): String =
    sys.env.get("CENTRAL_SECRET").filter(_.nonEmpty)
      .orElse(sys.env.get("CENTRAL_RESOURCE_SECRET").filter(_.nonEmpty))
      .orElse(sys.env.get("CENTRAL_SECRET_FILE").map(path => Files.readString(Path.of(path)).trim))
      .getOrElse:
        System.err.println("cert-sync: CENTRAL_SECRET, CENTRAL_SECRET_FILE or CENTRAL_RESOURCE_SECRET is required")
        sys.exit(2)

  def main(args: Array[String]): Unit =
    val centralUrl = required("CENTRAL_URL").stripSuffix("/")
    val dir = Path.of(sys.env.getOrElse("CERT_SYNC_DIR", "/certs"))
    val interval = sys.env.get("CERT_SYNC_INTERVAL_SECONDS").flatMap(_.toIntOption).getOrElse(300)
    val http = HttpClient.newBuilder.nn.connectTimeout(Duration.ofSeconds(10)).nn.build.nn
    val pushed = scala.collection.mutable.Map.empty[String, String]

    def push(clientId: String, pem: String): Boolean =
      try
        // Re-read per request: a mounted secret file is replaced under us on rotation of the
        // central resource secret just as it is for the certificates.
        val basic = java.util.Base64.getEncoder.nn.encodeToString(s"central:${secret()}".getBytes(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder(URI.create(s"$centralUrl/configuration/clients")).nn
          .timeout(Duration.ofSeconds(30)).nn
          .header("Authorization", s"Basic $basic").nn
          .header("Content-Type", "application/json").nn
          .PUT(HttpRequest.BodyPublishers.ofString(requestBody(clientId, pem))).nn
          .build.nn
        val response = http.send(request, HttpResponse.BodyHandlers.ofString()).nn
        if response.statusCode == 204 || response.statusCode == 200 then
          println(s"cert-sync: $clientId updated")
          true
        else
          System.err.println(s"cert-sync: $clientId refused with ${response.statusCode}: ${response.body}")
          false
      catch
        case NonFatal(error) =>
          System.err.println(s"cert-sync: $clientId failed: $error")
          false

    def pass(): Boolean =
      readClients(dir).map: (clientId, pem) =>
        val hash = digest(pem)
        if pushed.get(clientId).contains(hash) then true
        else
          val ok = push(clientId, pem)
          if ok then pushed(clientId) = hash
          ok
      .forall(identity)

    if interval <= 0 then sys.exit(if pass() then 0 else 1)
    else
      while true do
        pass()
        Thread.sleep(interval * 1000L)
