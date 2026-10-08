//> using scala 3.8.1
//> using jvm 25

// Run with `scala-cli run scripts/gen-env.scala` for local dev (see
// develop.md), or as part of the `tools` sbt project (see build.sbt) for
// the versola-tools image (see docker/Dockerfile.tools) -- both compile
// this exact file, not a copy. No shebang here: nothing in this repo
// executes it directly (`./gen-env.scala`), every caller goes through
// `scala-cli run <path>` or the sbt-packaged launcher, and a shebang line
// isn't valid Scala syntax for plain scalac/sbt to compile.

import java.io.{File, PrintWriter}
import java.net.URI
import java.security.{KeyPairGenerator, SecureRandom}
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import scala.sys.process.Process
import java.util.Base64

def rand(rng: SecureRandom, n: Int): String =
  val b = Array.ofDim[Byte](n)
  rng.nextBytes(b)
  Base64.getUrlEncoder.withoutPadding.encodeToString(b)

def genUUIDv7(rng: SecureRandom): String =
  val now = System.currentTimeMillis()
  val b = Array.ofDim[Byte](16)
  rng.nextBytes(b)
  b(0) = ((now >>> 40) & 0xFF).toByte
  b(1) = ((now >>> 32) & 0xFF).toByte
  b(2) = ((now >>> 24) & 0xFF).toByte
  b(3) = ((now >>> 16) & 0xFF).toByte
  b(4) = ((now >>> 8)  & 0xFF).toByte
  b(5) = (now          & 0xFF).toByte
  b(6) = ((b(6) & 0x0F) | 0x70).toByte  // version 7
  b(8) = ((b(8) & 0x3F) | 0x80).toByte  // variant 10xx
  val msb = (0 until 8).foldLeft(0L)((acc, i) => (acc << 8) | (b(i) & 0xFF))
  val lsb = (8 until 16).foldLeft(0L)((acc, i) => (acc << 8) | (b(i) & 0xFF))
  java.util.UUID(msb, lsb).toString

def b64std(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

def b64url(bi: java.math.BigInteger): String =
  val raw = bi.toByteArray
  val trimmed = if raw.length > 0 && raw(0) == 0 then raw.drop(1) else raw
  Base64.getUrlEncoder.withoutPadding.encodeToString(trimmed)

// When false (local env), prompts are skipped and defaults are used as-is.
var interactive = true

// Populated once, at the top of genEnv(), from this run's own `args`. Each
// `prompt`/`promptYN` call below takes an optional `flag` name; when this run
// was invoked with a matching `--flag=value` argument, that value is used
// directly and the prompt (interactive or not) is never reached for it. This
// is what lets `k8s` -- the only target that stays interactive (see
// `isKubernetes` below) -- be scripted: supply every flag its prompts need
// and the whole run completes without reading stdin at all, the same as
// vps/docker-local already do via environment variables. A run can also mix
// flags and typed answers freely -- anything without a matching flag just
// falls back to its usual prompt (or non-interactive default).
var cliArgs: Map[String, String] = Map.empty

def parseCliArgs(args: Seq[String]): Map[String, String] =
  args.flatMap { arg =>
    if !arg.startsWith("--") then None
    else
      val body = arg.stripPrefix("--")
      val eq   = body.indexOf('=')
      // A bare `--flag` (no `=value`) is treated as `true`, so
      // `promptYN`-backed flags (--otp, --smtp) can be given without a
      // value, matching how a shell boolean flag usually reads.
      if eq < 0 then Some(body -> "true") else Some(body.substring(0, eq) -> body.substring(eq + 1))
  }.toMap

def prompt(msg: String, default: String = "", flag: String = null): String =
  if flag != null && cliArgs.contains(flag) then return cliArgs(flag)
  if !interactive then return default
  print(msg)
  val line = scala.io.StdIn.readLine()
  if line == null || line.trim.isEmpty then default else line.trim

// For values that have no sensible default at all -- unlike vps's other
// non-interactive defaults (network addresses, ports), a public domain
// is specific to whichever deployment this is (see goshacodes' review on
// versolauth/versola#176: "this is our domain, users of cli will have
// other domains"). Reading it from the environment rather than hardcoding
// anything here means this script stays the same across every
// deployment; only versola-cli's own invocation (see its --auth-url
// flag) differs. Fails loudly and immediately instead of silently
// writing an empty or wrong URL into the generated config.
def requiredEnv(name: String): String =
  sys.env.getOrElse(name, throw RuntimeException(s"$name environment variable is required when TARGET=vps"))

def promptYN(msg: String, defaultYes: Boolean = false, flag: String = null): Boolean =
  if flag != null && cliArgs.contains(flag) then
    return Set("y", "yes", "true", "1").contains(cliArgs(flag).trim.toLowerCase)
  if !interactive then return defaultYes
  val hint = if defaultYes then "[Y/n]" else "[y/N]"
  print(s"$msg $hint: ")
  val line = scala.io.StdIn.readLine()
  if line == null || line.trim.isEmpty then defaultYes
  else line.trim.toLowerCase.startsWith("y")

def section(title: String): Unit =
  if interactive then println(title)

def writeFile(dir: File, name: String, content: String): Unit =
  dir.mkdirs()
  val f = File(dir, name)
  val pw = PrintWriter(f)
  try pw.print(content)
  finally pw.close()
  println(s"  Written: ${f.getPath}")

// ── nginx: the TLS terminator standing in front of auth for edge's RFC 8705 mutual-TLS
// calls (SSOClient.scala) ───────────────────────────────────────────────────────────────
// `versolaInternalTrustedCertificates` names a certificate edge is meant to validate the
// server against -- for that to be exercisable at all in local dev / CI e2e, something has
// to actually terminate TLS at `versola-internal-url` and be handed a matching certificate.
// auth itself never does (see ClientAuthentication.scala's own comment: it reads the
// certificate from a header, same as any tenant behind a proxy in production), so this
// generates the same shape locally: an nginx that terminates TLS, forwards whatever
// certificate it saw to auth's plain HTTP port, and otherwise gets out of the way.

/** A fresh certificate for nginx to present, written to `dir/server.crt` and `dir/server.key`,
  * signed by a CA written alongside it at `dir/ca.crt` / `dir/ca.key` -- self-signed would be
  * simpler, but nginx's `ssl_client_certificate` (required below even for `optional_no_ca`)
  * advertises that certificate's issuer as the one acceptable CA in its handshake's
  * `CertificateRequest`, and the JDK's default `X509KeyManager` -- what a JDK-provider TLS
  * client (edge, absent netty-tcnative) chooses a client certificate through -- filters its
  * available aliases against exactly that list. A self-signed client certificate edge
  * presents (RFC 8705 §2.2 has no CA at all) would never match a self-signed server
  * certificate's own issuer, so the handshake would silently complete with no certificate
  * sent rather than erroring -- confirmed by hand: that is exactly what happened before this
  * generated a CA at all. Both server and client certificates in this dev/e2e stack (see
  * `EdgeCertificate.scala`, which reads `ca.crt`/`ca.key` from this same directory) are
  * signed by the one CA instead, so their issuer is always the DN nginx advertises.
  *
  * Generated on every run rather than committed: it is dev-only key material with nothing
  * pinned to its value, unlike the fixed secrets above that e2e reads back out
  * (`bootstrapResourceSecretLine` and friends) -- there is nothing here for a test to assert
  * on beyond "edge trusts precisely this file".
  */
def genInternalTlsCertificate(dir: File): Unit =
  dir.mkdirs()
  def run(args: String*): Unit =
    val exit = Process(Seq("openssl") ++ args).!
    if exit != 0 then
      throw RuntimeException(s"openssl failed (exit $exit): ${args.mkString(" ")}")

  val caKey = File(dir, "ca.key").getPath
  val caCert = File(dir, "ca.crt").getPath
  val serverKey = File(dir, "server.key").getPath
  val serverCsr = File(dir, "server.csr").getPath
  val serverCert = File(dir, "server.crt").getPath

  run("req", "-x509", "-newkey", "rsa:2048", "-nodes",
    "-keyout", caKey, "-out", caCert, "-days", "3650",
    "-subj", "/CN=versola-internal-tls-ca")
  run("req", "-newkey", "rsa:2048", "-nodes",
    "-keyout", serverKey, "-out", serverCsr, "-subj", "/CN=localhost",
    "-addext", "subjectAltName=DNS:localhost")
  run("x509", "-req", "-in", serverCsr, "-CA", caCert, "-CAkey", caKey, "-CAcreateserial",
    "-out", serverCert, "-days", "3650", "-copy_extensions", "copy")

/** The listener `PostgresOAuthApp.mutualTlsServerConfig` terminates itself (RFC 8705 §5),
  * plus a client certificate e2e presents to it -- the counterpart, on this side, of
  * `SSOClientMutualTlsHandshakeSpec`'s `TestCertificates.generate`, except these three have to
  * exist as files: a real TLS handshake reads them off disk, not from an in-process fixture.
  *
  * All three signed by the same CA, the same relationship [[genInternalTlsCertificate]]'s
  * server certificate has to itself: this CA is the one anchor `trusted-certificates` names,
  * so the client certificate has to chain to it or the handshake `MutualTlsListenerSpec`
  * depends on never completes.
  *
  * The client's `dNSName` (`e2e-native-mtls-client.versola.test`) is registered against it by
  * that same spec -- a literal duplicated there rather than read from a file this script
  * writes, since nothing on the auth/central/e2e side is running yet for either to hand the
  * other a value.
  */
def genAuthMutualTlsCertificate(dir: File): Unit =
  dir.mkdirs()
  def run(args: String*): Unit =
    val exit = Process(Seq("openssl") ++ args).!
    if exit != 0 then
      throw RuntimeException(s"openssl failed (exit $exit): ${args.mkString(" ")}")

  val caKey = File(dir, "ca.key").getPath
  val caCert = File(dir, "ca.crt").getPath
  val serverKey = File(dir, "server.key").getPath
  val serverCsr = File(dir, "server.csr").getPath
  val serverCert = File(dir, "server.crt").getPath
  val clientKey = File(dir, "client.key").getPath
  val clientCsr = File(dir, "client.csr").getPath
  val clientCert = File(dir, "client.crt").getPath

  run("req", "-x509", "-newkey", "rsa:2048", "-nodes",
    "-keyout", caKey, "-out", caCert, "-days", "3650",
    "-subj", "/CN=versola-auth-mtls-ca")
  run("req", "-newkey", "rsa:2048", "-nodes",
    "-keyout", serverKey, "-out", serverCsr, "-subj", "/CN=localhost",
    "-addext", "subjectAltName=DNS:localhost")
  run("x509", "-req", "-in", serverCsr, "-CA", caCert, "-CAkey", caKey, "-CAcreateserial",
    "-out", serverCert, "-days", "3650", "-copy_extensions", "copy")
  run("req", "-newkey", "rsa:2048", "-nodes",
    "-keyout", clientKey, "-out", clientCsr, "-subj", "/CN=e2e-native-mtls-client",
    "-addext", "subjectAltName=DNS:e2e-native-mtls-client.versola.test")
  run("x509", "-req", "-in", clientCsr, "-CA", caCert, "-CAkey", caKey, "-CAcreateserial",
    "-out", clientCert, "-days", "3650", "-copy_extensions", "copy")

/** The certificate edge presents as `central-admin` (#353): RFC 8705 §2.1 `tls_client_auth`,
  * which is what the default tenant's FAPI 2.0 profile admits for an edge-fronted web client.
  * Written to `dir/central-admin.{crt,key}` and returned as the one PEM (certificate, then its
  * PKCS#8 key) `bootstrap.central-admin-mtls.certificate` takes.
  *
  * Signed by [[genInternalTlsCertificate]]'s CA, so `dir` must already hold it: that CA is the
  * one issuer nginx advertises, and a JDK TLS client (edge) offers no certificate whose issuer
  * is not on that list. `genpkey` rather than `req -newkey`, since it writes PKCS#8 under both
  * OpenSSL and LibreSSL, and PKCS#8 is the only key form `PrivateClientCertificate` accepts.
  * Central registers the client by this certificate's own subject DN, so nothing else needs to
  * agree with the subject chosen here.
  */
def genCentralAdminCertificate(dir: File): String =
  def run(args: String*): Unit =
    val exit = Process(Seq("openssl") ++ args).!
    if exit != 0 then
      throw RuntimeException(s"openssl failed (exit $exit): ${args.mkString(" ")}")

  val key = File(dir, "central-admin.key").getPath
  val csr = File(dir, "central-admin.csr").getPath
  val cert = File(dir, "central-admin.crt").getPath
  run("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key)
  run("req", "-new", "-key", key, "-out", csr, "-subj", "/O=Versola/CN=central-admin")
  run("x509", "-req", "-in", csr, "-CA", File(dir, "ca.crt").getPath, "-CAkey", File(dir, "ca.key").getPath,
    "-CAcreateserial", "-out", cert, "-days", "3650")
  def read(path: String) = scala.io.Source.fromFile(path).mkString.trim
  s"${read(cert)}\n${read(key)}\n"

/** `dir` and its certificate must already exist (see [[genInternalTlsCertificate]]) -- this
  * only renders the conf that points at them. Absolute paths throughout: nginx resolves a
  * relative one against its own prefix, not this process's working directory, which would
  * silently pick a different directory than the one just written to.
  */
def internalTlsNginxConf(dir: File, port: Int, upstream: String): String =
  val absolute = dir.getAbsoluteFile
  val upstreamUri = URI.create(upstream)
  val upstreamPort = if upstreamUri.getPort > 0 then upstreamUri.getPort else 80
  s"""daemon off;
     |pid ${File(absolute, "nginx.pid").getPath};
     |error_log ${File(absolute, "error.log").getPath};
     |worker_processes 1;
     |events {
     |  worker_connections 64;
     |}
     |http {
     |  access_log ${File(absolute, "access.log").getPath};
     |  server {
     |    listen $port ssl;
     |    server_name localhost;
     |    ssl_certificate ${File(absolute, "server.crt").getPath};
     |    ssl_certificate_key ${File(absolute, "server.key").getPath};
     |    # optional_no_ca: request a client certificate but do not validate it against a CA.
     |    # RFC 8705's self-signed and registered-subject methods are both validated at the
     |    # application layer (auth's OAuthClientService), not by the terminator -- nginx's
     |    # only job is completing the handshake and forwarding what it saw. Naming the CA
     |    # here (rather than skipping this file, which nginx does not allow even in this
     |    # mode) is not for that validation, which optional_no_ca skips -- it is what nginx
     |    # advertises as its one acceptable issuer in the handshake's CertificateRequest, and
     |    # a JDK-provider TLS client's default key manager will offer no certificate at all
     |    # if none of its own match an issuer on that list (see genInternalTlsCertificate's
     |    # own comment). Every certificate this dev/e2e stack signs is signed by this CA for
     |    # exactly that reason.
     |    ssl_client_certificate ${File(absolute, "ca.crt").getPath};
     |    ssl_verify_client optional_no_ca;
     |    location / {
     |      proxy_pass http://127.0.0.1:$upstreamPort;
     |      proxy_set_header Host $$host;
     |      # RFC 8705 §6.5: the header/encoding the default tenant is configured to read
     |      # (OAuthClient.mtlsCertificateHeader in e2e's Flows.scala; MutualTlsSpec's
     |      # `urlEncodedPem`). `$$ssl_client_escaped_cert` is empty when the connection
     |      # presented no certificate, and proxy_set_header omits a header entirely rather
     |      # than forwarding an empty value -- so a client_secret/private_key_jwt call
     |      # through this same port arrives at auth exactly as if this terminator were not
     |      # here at all.
     |      proxy_set_header ssl-client-cert $$ssl_client_escaped_cert;
     |    }
     |  }
     |}
     |""".stripMargin

// ── Secret placeholders (docker-local, vps and k8s) ──────────────────────
// In docker-local, vps and k8s modes, every secret field this script
// generates becomes a `${VAR}` HOCON substitution placeholder instead of a
// literal value -- see `useOpenBao` below, named for the mechanism the
// first two of these resolve it through. Three different things then
// supply the real value, one per mode:
//   - docker-local / vps: versola-cli resolves it against OpenBao --
//     reading a previous `configure` run's value back, or storing this
//     run's freshly generated one if there isn't one yet -- and supplies
//     it as a real environment variable when it starts each container
//     (see writeGeneratedSecrets below, and versola-cli's openbao
//     package).
//   - k8s: nothing in this repository resolves it. The operator builds a
//     Kubernetes Secret from this run's own `*.generated-secrets.env`
//     file (see k8s/README.md §4) and points `secrets.existingSecret` at
//     it; the chart injects each key the same way regardless of where it
//     came from.
// isLocal, and any other target this script doesn't specifically know
// about, keep writing the value directly: a person running this
// interactively can just type the real value in, and there's no Secret or
// OpenBao pipeline on the other end to resolve a placeholder against.
//
// Deliberately `${VAR}`, not `${?VAR}`: the optional form silently drops
// the key from the resolved config if the env var is missing, so a broken
// secret pipeline (OpenBao/ESO/Vault misconfigured, a k8s Secret missing a
// key, ...) doesn't fail until whatever code path first reads that
// specific key -- possibly well after boot, with a message that doesn't
// name the actual gap. Every placeholder this script writes is one the
// same run's own writeGeneratedSecrets call (docker-local, vps, k8s) or
// the OpenBao resolution flow (docker-local, vps) unconditionally
// populates before the container ever starts, so requiring it costs
// nothing in the working case and turns the broken case into an
// immediate, named ConfigException.UnresolvedSubstitution at config load
// instead.
//
// usePlaceholder is a parameter, not a closed-over var like `interactive`
// below: it's decided from local vals inside genEnv() (isDockerLocal,
// isVps), not top-level mutable state, so there's nothing for a top-level
// def to close over.
def secretField(usePlaceholder: Boolean, value: String, envVar: String): String =
  if usePlaceholder then s"$${$envVar}" else "\"" + value + "\""

// Same idea as secretField, but for values the interactive branches wrap
// in HOCON triple-quotes (the RSA private keys) rather than a plain quoted
// string -- preserves that exactly on every path this doesn't change.
def secretKeyField(usePlaceholder: Boolean, value: String, envVar: String): String =
  if usePlaceholder then s"$${$envVar}" else "\"\"\"" + value + "\"\"\""

// Writes the values secretField/secretKeyField placeholdered out, as
// plain KEY=value lines -- not JSON: this script has no JSON dependency,
// and a dotenv shape serves every consumer this has today without change --
// versola-cli loads it straight into Compose's `env_file:` for docker-local
// and vps (after resolving each value against OpenBao), and it's also
// exactly the shape `kubectl create secret generic --from-env-file=...`
// wants for k8s (see k8s/README.md §4). Only called when useOpenBao is
// true; every other env has nothing to write here since it never
// placeholdered anything out in the first place.
//
// Before writing, the key set is checked against SecretSchema (below): a key
// written here that the schema doesn't list, or a schema key that isn't
// written, stops the run -- naming the keys, never their values.
def writeGeneratedSecrets(dir: File, target: SecretTarget, service: String, secrets: Seq[(String, String)]): Unit =
  SecretSchema.verifyKeys(target, service, secrets.map((k, _) => k))
  val content = secrets.map((k, v) => s"$k=$v").mkString("\n") + "\n"
  writeFile(dir, s"$service.generated-secrets.env", content)

// ── Secret schema ────────────────────────────────────────────────────────
// The one declarative list of every secret a deployment needs: what it is
// called, which services receive it, how it is shaped, and what to do when a
// deployment doesn't have it yet. versola-cli reads the JSON this script
// writes next to the configs (secrets.schema.json, see SecretSchema.toJson)
// to decide, before deploying a new version, which secrets are missing from
// the secret store and may be taken from this run's freshly generated
// candidates. Values are still generated by this script, not by the CLI.
//
// `enum` is Scala 3's closed set of alternatives (like a Java enum, and what
// `sealed trait` + `case object`s used to be); `final case class` is an
// immutable record with equals/copy/toString generated for it.

/** How a value is shaped. `json` is the name written to secrets.schema.json. */
enum SecretType(val json: String):
  /** `size` bytes in URL-safe base64. gen-env writes it without padding, but the services decode it
    * with Java's URL decoder, which also accepts `=` padding: a consumer validates the DECODED
    * length (padding optional), not the number of characters. Only for values whose decoder
    * enforces that length (`Secret.Bytes16/Bytes32`); anything looser is `Opaque`. */
  case Base64Url extends SecretType("base64url")
  /** RSA private key, PKCS#8 DER in standard base64. No `size`: gen-env generates 2048 bits, but
    * `PrivateKeyUtil.parse` accepts any modulus and an imported key (develop.md, "Onboarding") may
    * be 3072 or 4096, so the modulus is not a promise about every value the store may hold. */
  case RsaPrivateKey extends SecretType("rsa-private-key")
  /** `{"keys":[...]}`: public JWKs, wrapped the way central's bootstrap.jwks expects. */
  case JwkSet extends SecretType("jwk-set")
  /** One public JWK, single-line JSON. */
  case PublicJwk extends SecretType("public-jwk")
  /** One private JWK (carries `d`), single-line JSON. */
  case PrivateJwk extends SecretType("private-jwk")
  /** An identifier derived together with a key, not random (`edge-<date>`). */
  case KeyId extends SecretType("key-id")
  /** No shape, no size: a value the operator can set (a prompt, a flag, an imported existing
    * password), so nothing may be validated about it. A secret is only `Base64Url` etc. when
    * every value the store may hold has that shape, not merely the one gen-env generates. */
  case Opaque extends SecretType("opaque")

/** What a deployment does when a secret is absent from its secret store. */
enum OnMissing(val json: String):
  /** Take this run's generated candidate. */
  case Generate extends OnMissing("generate")
  /** Take the candidate on a first install only; on an upgrade a missing value is an
    * error, because something already depends on the real one: either something outside the
    * store holds it (POSTGRES_PASSWORD: the role in Postgres has a password of its own), or
    * data it protects is stored (PASSWORDS_SECRET, REFRESH_TOKENS_SECRET, CLIENT_SECRETS_SECRET,
    * EDGE_TOKEN_ENC_KEY, CENTRAL_RESOURCE_SECRET, the signing, edge and utils key pairs). */
  case GenerateOnFirstInstallOnly extends OnMissing("generate-on-first-install-only")
  /** Never generated here; the operator supplies it. */
  case External extends OnMissing("external")

/** The deployment targets that write *.generated-secrets.env (`local` writes none). */
enum SecretTarget(val json: String):
  case DockerLocal extends SecretTarget("docker-local")
  case Vps extends SecretTarget("vps")
  case K8s extends SecretTarget("k8s")

/**
  * @param services who receives it: the *.generated-secrets.env of each service listed. A
  *                 value shared by several services is listed once, with all of them. The
  *                 same name in several entries (one service each) is separate values -- a
  *                 consumer keys a secret by (name, service), never by name alone.
  *                 `utils` is not a service but the holder of the `utils` client's private
  *                 key; it never reaches a *.generated-secrets.env (see `file`).
  * @param size     Base64Url: bytes before encoding; otherwise None.
  * @param group    secrets that only work as a set (a key pair split across services,
  *                 tied together by a kid): all of a group's members are taken, or none.
  * @param file     the file this script writes the value to instead of a
  *                 *.generated-secrets.env, when it isn't in one.
  */
final case class SecretSpec(
    name: String,
    services: List[String],
    tpe: SecretType,
    size: Option[Int],
    group: Option[String],
    onMissing: OnMissing,
    targets: Set[SecretTarget],
    file: Option[String] = None,
)

object SecretSchema:
  /** Bumped when the shape of secrets.schema.json changes incompatibly. */
  val SchemaVersion = 1

  /** The services whose *.generated-secrets.env this script writes. */
  val Services: List[String] = List("auth", "central", "edge")
  /** `utils` plus the services above: every valid entry in SecretSpec.services. */
  private val Holders: Set[String] = Services.toSet + "utils"

  private val everywhere: Set[SecretTarget] = SecretTarget.values.toSet
  // The Postgres password and the admin bootstrap password are placeholdered out only on vps
  // and k8s -- see the comment on `authExtras` in genEnv, and pgPassDefault / bootstrapPasswordDefault.
  // They differ between the two, so each has an entry per target below.

  /** A value k8s takes from the operator's prompt or flag: nothing here generates it, and
    * nothing about its shape is promised. */
  private def k8sOperatorSecret(name: String, service: String): SecretSpec =
    SecretSpec(name, List(service), SecretType.Opaque, None, None, OnMissing.External, Set(SecretTarget.K8s))

  private def base64Url(
      name: String,
      services: List[String],
      bytes: Int,
      onMissing: OnMissing = OnMissing.Generate,
  ): SecretSpec =
    SecretSpec(name, services, SecretType.Base64Url, Some(bytes), None, onMissing, everywhere)

  /** A value that persisted data depends on: a hash key, an at-rest encryption key, a signing
    * key. Losing it after the first install doesn't rotate it, it destroys what it protects --
    * so a store that lacks it on an upgrade is an error, not an invitation to generate. */
  private def bound(name: String, services: List[String], bytes: Int): SecretSpec =
    base64Url(name, services, bytes, OnMissing.GenerateOnFirstInstallOnly)

  /** A value the services decode as a plain `Secret` (base64url of ANY length) or as text: the
    * generated one has a size, but nothing rejects another, so no size is promised. */
  private def opaque(name: String, services: List[String], onMissing: OnMissing = OnMissing.Generate): SecretSpec =
    SecretSpec(name, services, SecretType.Opaque, None, None, onMissing, everywhere)

  private def grouped(
      name: String,
      services: List[String],
      tpe: SecretType,
      size: Option[Int],
      group: String,
      file: Option[String] = None,
      onMissing: OnMissing = OnMissing.Generate,
  ): SecretSpec =
    SecretSpec(name, services, tpe, size, Some(group), onMissing, everywhere, file)

  /** Every secret, in the order they are written. The sizes mirror the `rand(rng, N)` calls in
    * genEnv; check-secret-schema.sh decodes the generated values and compares their length. */
  val specs: List[SecretSpec] = List(
    // auth
    base64Url("ACCESS_TOKENS_SECRET", List("auth"), 32),
    // The AES key central encrypts client secrets, JWKS signing keys, edge keys and resource secrets with.
    bound("CLIENT_SECRETS_SECRET", List("auth", "central"), 16),
    // The MAC key of every stored refresh token: a fresh one turns each of them, the long-lived
    // on-device offline_access ones included, into invalid_grant for good.
    bound("REFRESH_TOKENS_SECRET", List("auth"), 32),
    // Losing the next three (and the cookie and DPoP secrets below) only signs users out or
    // fails in-flight codes and requests, which expire within minutes; they stay `generate`.
    base64Url("AUTH_CODES_SECRET", List("auth"), 32),
    base64Url("SESSIONS_SECRET", List("auth"), 32),
    // The key every stored password hash is computed with: a new one fails every login.
    bound("PASSWORDS_SECRET", List("auth"), 16),
    base64Url("CONVERSATION_COOKIE_SECRET", List("auth"), 32),
    base64Url("SESSION_COOKIE_SECRET", List("auth"), 32),
    base64Url("USER_AGENT_COOKIE_SECRET", List("auth"), 32),
    base64Url("PAR_REQUESTS_SECRET", List("auth"), 32),
    base64Url("DPOP_NONCES_SECRET", List("auth"), 32),
    // The signing key auth holds and the public JWKS central serves for it: auth matches the
    // key to its JWKS entry by RSA modulus, and the entry needs a usable `alg`.
    // (a fresh pair next to the real one leaves two kids in play -- develop.md, "Onboarding")
    grouped("JWT_PRIVATE_KEY", List("auth"), SecretType.RsaPrivateKey, None, "jwt", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    base64Url("CENTRAL_SECRET_KEY", List("auth", "central"), 32),
    // central
    // central decodes it as a plain `Secret` (any length), and keeps the stored one once the
    // resource exists (seedAuthResource); auth reads it back by sync, so `generate` is harmless.
    opaque("ACCOUNT_RESOURCE_SECRET", List("central")),
    // Seeded into central's database once, AES-encrypted (seedCentralResource creates the resource
    // only if it is absent) and checked there by authorizeBasic. Edge gets it by sync, but cert-sync
    // and operator tools present the value from the store, so a fresh one after the first install
    // is a 401 for them. (A rotation through the admin API changes central's copy, not the store's.)
    opaque("CENTRAL_RESOURCE_SECRET", List("central"), OnMissing.GenerateOnFirstInstallOnly),
    grouped("JWKS_JSON", List("central"), SecretType.JwkSet, None, "jwt", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    // The edge's key pair: the private half and its kid at the edge, the public half at central.
    // (central already trusts the real public key: a fresh edge pair makes every sync call 401)
    grouped("EDGE_PUBLIC_JWK", List("central"), SecretType.PublicJwk, None, "edge-key", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    // The `utils` client's key pair: the public half at central, the private half in the file
    // below (and, once versola-cli stores it, under the pseudo-service `utils`). central re-applies
    // the public half on every boot (seedUtilityClient), and loadgen/versola-cli hold the private
    // half outside the store, so a fresh pair on an upgrade silently locks them out.
    grouped("UTILITY_CLIENT_PUBLIC_JWK", List("central"), SecretType.PublicJwk, None, "utils", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    // edge
    grouped("EDGE_PRIVATE_KEY", List("edge"), SecretType.RsaPrivateKey, None, "edge-key", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    grouped("EDGE_KEY_ID", List("edge"), SecretType.KeyId, None, "edge-key", onMissing = OnMissing.GenerateOnFirstInstallOnly),
    // Encrypts the refresh tokens edge keeps in its session table (EdgeService.storeSession). With a
    // fresh key every existing session fails AES-GCM decryption in refreshSession, which surfaces as
    // a server error rather than a re-login. EDGE_SESSIONS_SECRET and EDGE_NATIVE_BLOB_KEY below
    // are not like this: the first is not read by edge today, the second seals short-lived blobs
    // that fail cleanly as `Unreadable`.
    bound("EDGE_TOKEN_ENC_KEY", List("edge"), 32),
    base64Url("EDGE_SESSIONS_SECRET", List("edge"), 32),
    // edge decodes it as a plain `Secret` (any length) and only compares it, so no size is promised.
    opaque("EDGE_INTERNAL_SECRET", List("edge")),
    base64Url("EDGE_DPOP_NONCE_SALT", List("edge"), 32),
    // The AES-256-GCM key edge seals the native-app blob with (`native.blob-key`).
    base64Url("EDGE_NATIVE_BLOB_KEY", List("edge"), 32),
    // vps: one password for all three services (verifySharedPostgresPassword), generated once.
    // Opaque although vps generates 24 random bytes (`rand(rng, 24)`): --*-postgres-password
    // overrides it, and an existing deployment's real password (develop.md, "Onboarding") is
    // whatever it is. The type is a promise about every value the store may hold, so a
    // consumer validating "24-byte base64url" would reject those.
    SecretSpec(
      "POSTGRES_PASSWORD",
      List("auth", "central", "edge"),
      SecretType.Opaque,
      None,
      None,
      OnMissing.GenerateOnFirstInstallOnly,
      Set(SecretTarget.Vps),
    ),
    // k8s: three Postgres instances, three passwords the operator types (--*-postgres-password);
    // the chart keeps them under separate keys. The same name in several entries, one service
    // each, means separate values -- see SecretSpec.services.
    k8sOperatorSecret("POSTGRES_PASSWORD", "auth"),
    k8sOperatorSecret("POSTGRES_PASSWORD", "central"),
    k8sOperatorSecret("POSTGRES_PASSWORD", "edge"),
    // vps generates 16 random bytes, but --admin-password overrides them: opaque for the same reason.
    SecretSpec(
      "ADMIN_BOOTSTRAP_PASSWORD",
      List("auth"),
      SecretType.Opaque,
      None,
      None,
      OnMissing.Generate,
      Set(SecretTarget.Vps),
    ),
    // k8s: whatever the operator types (--admin-password); the default is not generated.
    k8sOperatorSecret("ADMIN_BOOTSTRAP_PASSWORD", "auth"),
    // Written to a file of its own, in every target that writes a *.generated-secrets.env.
    grouped(
      "UTILS_PRIVATE_KEY_JWK",
      List("utils"),
      SecretType.PrivateJwk,
      None,
      "utils",
      file = Some("utils.private-key.jwk"),
      onMissing = OnMissing.GenerateOnFirstInstallOnly,
    ),
  )

  def parseTarget(name: String): Option[SecretTarget] = SecretTarget.values.find(_.json == name)

  def forTarget(target: SecretTarget): List[SecretSpec] = specs.filter(_.targets.contains(target))

  /** The keys `service`'s *.generated-secrets.env must hold on `target`. */
  def keysFor(target: SecretTarget, service: String): Set[String] =
    forTarget(target).filter(_.services.contains(service)).map(_.name).toSet

  /** vps's schema entry for POSTGRES_PASSWORD is one value for auth, central and edge. The three
    * `--*-postgres-password` flags are accepted there too, so this stops a run that gives them
    * different values. Names the flags, never the values. */
  def verifySharedPostgresPassword(auth: String, central: String, edge: String): Unit =
    if auth != central || auth != edge then
      throw RuntimeException(
        "on vps auth, central and edge share one Postgres password, so --auth-postgres-password, " +
          "--central-postgres-password and --edge-postgres-password must be the same value " +
          "(or all left out); k8s is the target with a password per service",
      )

  /** Stops the run when `written` (the keys about to be written, names only) isn't exactly the
    * schema's set for `service` on `target`. The message names keys and nothing else. */
  def verifyKeys(target: SecretTarget, service: String, written: Seq[String]): Unit =
    val expected   = keysFor(target, service)
    val actual     = written.toSet
    val missing    = (expected -- actual).toList.sorted
    val unexpected = (actual -- expected).toList.sorted
    val repeated   = written.diff(written.distinct).distinct.sorted
    if missing.nonEmpty || unexpected.nonEmpty || repeated.nonEmpty then
      throw RuntimeException(
        s"secret schema mismatch for $service.generated-secrets.env on ${target.json}: " +
          s"in the schema but not written: [${missing.mkString(", ")}]; " +
          s"written but not in the schema: [${unexpected.mkString(", ")}]; " +
          s"written twice: [${repeated.mkString(", ")}]",
      )

  /** Everything wrong with `candidates` as a schema, as messages; empty when it is sound. */
  def problems(candidates: List[SecretSpec]): List[String] =
    // A name may repeat (k8s has a POSTGRES_PASSWORD per service), but not for the same service
    // on the same target: that would be two answers to one question.
    val slots = candidates.flatMap(spec => for target <- spec.targets.toList; service <- spec.services yield (spec.name, service, target.json))
    val duplicateNames = slots.diff(slots.distinct).map((name, _, _) => name).distinct.sorted
    val perSpec = candidates.flatMap: spec =>
      val noServices = if spec.services.isEmpty then List(s"${spec.name}: no services") else Nil
      val unknownServices =
        spec.services.filterNot(Holders.contains).map(service => s"${spec.name}: unknown service $service")
      val noTargets = if spec.targets.isEmpty then List(s"${spec.name}: no targets") else Nil
      val sizeProblem = (spec.tpe, spec.size) match
        case (SecretType.Base64Url, None) => List(s"${spec.name}: ${spec.tpe.json} needs a size")
        case (SecretType.Base64Url, Some(size)) if size <= 0 => List(s"${spec.name}: size must be positive")
        case (SecretType.Base64Url, Some(_)) => Nil
        case (_, Some(_)) => List(s"${spec.name}: ${spec.tpe.json} takes no size")
        case (_, None) => Nil
      // A secret that isn't in a *.generated-secrets.env must say which file holds it, and
      // the other way round; `utils` is the only holder with no such file.
      val holdsFile = spec.services.contains("utils")
      val fileProblem =
        if holdsFile && spec.file.isEmpty then List(s"${spec.name}: held by utils but names no file")
        else if !holdsFile && spec.file.nonEmpty then List(s"${spec.name}: names a file but is in a *.generated-secrets.env")
        else Nil
      noServices ++ unknownServices ++ noTargets ++ sizeProblem ++ fileProblem
    val groupProblems = candidates
      .flatMap(spec => spec.group.map(_ -> spec))
      .groupBy((group, _) => group)
      .toList
      .sortBy((group, _) => group)
      .flatMap: (group, members) =>
        val specsOfGroup = members.map((_, spec) => spec)
        val tooSmall = if specsOfGroup.size < 2 then List(s"group $group: fewer than two members") else Nil
        val targetSets = specsOfGroup.map(_.targets).distinct
        val split = if targetSets.size > 1 then List(s"group $group: members are not on the same targets") else Nil
        // A set is taken whole or not at all, so it can't be generated on upgrade for one member only.
        val policies = specsOfGroup.map(_.onMissing).distinct
        val mixed = if policies.size > 1 then List(s"group $group: members have different onMissing") else Nil
        tooSmall ++ split ++ mixed
    duplicateNames.map(name => s"duplicate name $name") ++ perSpec ++ groupProblems

  /** `s` as a JSON string literal. This script has no JSON dependency (see the comment on
    * writeGeneratedSecrets), and the schema only holds names and fixed words, but escaping
    * costs nothing and keeps the output valid whatever a name turns into. */
  private def jsonString(s: String): String =
    val out = StringBuilder("\"")
    s.foreach: ch =>
      ch match
        case '"'  => out ++= "\\\""
        case '\\' => out ++= "\\\\"
        case '\n' => out ++= "\\n"
        case '\r' => out ++= "\\r"
        case '\t' => out ++= "\\t"
        case other if other < ' ' => out ++= "\\u%04x".format(other.toInt)
        case other => out += other
    out += '"'
    out.toString

  /** secrets.schema.json for `target`: only the entries that exist on it, so versola-cli has no
    * target logic to get wrong. No values -- names, shapes and policies only. */
  def toJson(target: SecretTarget): String =
    val entries = forTarget(target).map: spec =>
      val services = spec.services.map(jsonString).mkString("[", ",", "]")
      val size     = spec.size.fold("null")(_.toString)
      val group    = spec.group.fold("null")(jsonString)
      val file     = spec.file.fold("null")(jsonString)
      "    {" +
        s""""name":${jsonString(spec.name)},""" +
        s""""services":$services,""" +
        s""""type":${jsonString(spec.tpe.json)},""" +
        s""""size":$size,""" +
        s""""group":$group,""" +
        s""""onMissing":${jsonString(spec.onMissing.json)},""" +
        s""""file":$file""" +
        "}"
    List(
      "{",
      s"""  "schemaVersion": $SchemaVersion,""",
      s"""  "target": ${jsonString(target.json)},""",
      """  "secrets": [""",
      entries.mkString(",\n"),
      "  ]",
      "}",
      "",
    ).mkString("\n")

@main def genEnv(args: String*): Unit =
  cliArgs = parseCliArgs(args)
  val rng = SecureRandom()

  // ── Key pairs ─────────────────────────────────────────────────────────────────
  println("Generating RSA-2048 and EC (P-256) key pairs...")

  case class RsaKey(privateB64: String, jwk: String, kid: String)

  /** `alg` only changes the JWK's own claim about itself -- an RSA key pair signs the same
    * way under RS256 or PS256, so this is what decides which one a deployment publishes.
    */
  def genRsaKey(kid: String, alg: String = "RS256"): RsaKey =
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048, rng)
    val kp      = kpg.generateKeyPair()
    val privKey = kp.getPrivate.asInstanceOf[RSAPrivateCrtKey]
    val pubKey  = kp.getPublic.asInstanceOf[RSAPublicKey]
    val n       = b64url(pubKey.getModulus)
    val e       = b64url(pubKey.getPublicExponent)
    val jwk     = s"""{"kty":"RSA","e":"$e","use":"sig","kid":"$kid","alg":"$alg","n":"$n"}"""
    RsaKey(b64std(privKey.getEncoded), jwk, kid)

  /** `privateJwk` is the same pair as one JWK carrying `d`, for the one consumer that signs
    * from a JWK rather than PKCS#8 -- loadgen's `provision.provisioner-private-key`. */
  case class EcKey(privateB64: String, jwk: String, kid: String, privateJwk: String)

  /** P-256 is the only curve ES256 signs on. Unlike an RSA modulus, a coordinate is fixed
    * width (32 bytes here) and never carries a leading sign byte to strip -- `b64url` would
    * strip a genuine leading zero byte instead, so coordinates get their own encoding.
    */
  def genEcKey(kid: String): EcKey =
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), rng)
    val kp      = kpg.generateKeyPair()
    val privKey = kp.getPrivate.asInstanceOf[java.security.interfaces.ECPrivateKey]
    val pubKey  = kp.getPublic.asInstanceOf[java.security.interfaces.ECPublicKey]
    def coordinate(value: java.math.BigInteger): String =
      val raw = value.toByteArray
      val fixed =
        if raw.length == 32 then raw
        else if raw.length > 32 then raw.takeRight(32)
        else Array.fill[Byte](32 - raw.length)(0) ++ raw
      Base64.getUrlEncoder.withoutPadding.encodeToString(fixed)
    val x   = coordinate(pubKey.getW.getAffineX)
    val y   = coordinate(pubKey.getW.getAffineY)
    val jwk = s"""{"kty":"EC","crv":"P-256","x":"$x","y":"$y","use":"sig","kid":"$kid","alg":"ES256"}"""
    val privateJwk = s"""{"kty":"EC","crv":"P-256","x":"$x","y":"$y","d":"${coordinate(privKey.getS)}","use":"sig","kid":"$kid","alg":"ES256"}"""
    EcKey(b64std(privKey.getEncoded), jwk, kid, privateJwk)

  val today = java.time.LocalDate.now.toString
  // JWT signing key: auth signs access tokens with the private half; central serves the
  // public half in the JWKS so auth/edge/admin-console can verify those tokens. PS256, not
  // RS256: FAPI 1.0 Advanced §8.6 and FAPI 2.0 both require PS256 or ES256 and disallow
  // RS256's PKCS#1 v1.5 padding, and there is no reason a fresh deployment should start out
  // non-compliant with that. The key itself is still RSA -- PS256 is the same key, read with
  // RSASSA-PSS padding instead of PKCS#1 v1.5.
  val jwtKey = genRsaKey(s"jwt-$today", "PS256")
  // A second, EC signing key published alongside it: FAPI's other permitted algorithm, so a
  // fresh deployment demonstrates verifying both rather than only the one it happens to sign
  // with. Verify-only from central's perspective -- see `BootstrapService.seedJwks` -- same as
  // `jwtKey`; nothing here is `bootstrap.jwks`'s private half, only auth's own `JWT_PRIVATE_KEY`
  // is, so this key can be rotated in for real signing later without this script's involvement.
  val esKey = genEcKey(s"jwt-es256-$today")
  // Edge key: central encrypts each edge's client secrets with the public half and
  // verifies the edge's sync tokens against it; the edge signs/decrypts with the private half.
  val edgeKey = genRsaKey(s"edge-$today")
  val jwks    = s"""{"keys":[${jwtKey.jwk}, ${esKey.jwk}]}"""

  // ── Admin user ID ─────────────────────────────────────────────────────────────
  val adminUserId = genUUIDv7(rng) // stable across restarts; seeded in both auth and central

  // ── Random secrets ────────────────────────────────────────────────────────────
  val centralSecretKey          = rand(rng, 32) // shared: auth↔central (edge doesn't read it)
  val clientSecretsSecret       = rand(rng, 16) // shared: auth + central (central: AES key of client secrets, signing keys, resource secrets at rest)
  val accessTokensSecret        = rand(rng, 32)
  val refreshTokensSecret       = rand(rng, 32)
  val authCodesSecret           = rand(rng, 32)
  val sessionsSecret            = rand(rng, 32)
  val passwordsSecret           = rand(rng, 16)
  val conversationCookieSecret  = rand(rng, 32) // auth only: signs the SSO_CONVERSATION cookie
  val sessionCookieSecret       = rand(rng, 32)
  val userAgentCookieSecret     = rand(rng, 32) // auth only: signs the SSO_USER_AGENT_ID cookie
  val edgeTokenEncKey           = rand(rng, 32)
  val edgeSessionsSecret        = rand(rng, 32)
  val edgeInternalSecret        = rand(rng, 32) // authorizes edge's non-prod /service/configuration/sync
  val parRequestsSecret         = rand(rng, 32) // auth only: keys the stored request_uri references
  val dpopNoncesSecret          = rand(rng, 32) // auth only: authenticates DPoP-Nonce values
  // Edge's own nonce space, kept apart from auth's: RFC 9449 §9 has the resource server
  // issue nonces under its own key, so a nonce minted by auth is not valid at edge.
  val edgeDpopNonceSalt         = rand(rng, 32)
  val edgeNativeBlobKey         = rand(rng, 32)
  val accountResourceSecretGenerated = rand(rng, 32) // central: seeds the "auth" resource record; auth fetches it decrypted via registry sync
  val centralResourceSecretGenerated = rand(rng, 32) // central: seeds its own "central" resource record; edge fetches it to proxy admin calls (auth.scala's authorizeBasic)
  // central: seeds bootstrap.utility-client with the public half; the private half is loadgen's
  // provision.provisioner-private-key. See `utilityKey`'s use below for where each half goes.
  val utilityKey = genEcKey("utils")

  // ── Environment ───────────────────────────────────────────────────────────────
  println("\n── Environment ───────────────────────────────────────────────────────")
  // target picks which of this script's own branches to run (network
  // defaults, interactive vs non-interactive) -- see `env` below for why
  // this is deliberately a different question from "what environment name
  // gets written into the config".
  val target  = prompt("  Target [local]: ", "local", flag = "target")
  val isLocal = target == "local"
  // docker-local is for "versola bootstrap local": auth/central/edge each run
  // in their own container on one Docker Compose bridge network, instead of
  // sharing the host's network the way "local" (above) and prod both assume.
  // Same idea as isLocal — skip prompts, use defaults — but the defaults
  // themselves have to be different: "localhost" from inside one container
  // doesn't reach a service running in another container, so anything that's
  // a real network call (not just a JWT issuer string) needs to point at the
  // other container's Compose service name instead. See versola-cli's
  // manual-test/README.md for how these values were worked out by hand
  // before being made the default here.
  val isDockerLocal = target == "docker-local"
  // vps is for "versola configure vps": auth/central/edge run as Docker
  // containers with `network_mode: host` on the one real VPS (see
  // deploy.md) -- no bridge network, no Compose service-name addressing,
  // so internal calls go through 127.0.0.1 instead. Non-interactive like
  // docker-local, for the same reason: this is driven by the CLI calling
  // versola-tools non-interactively, not a person typing at a prompt.
  // Unlike docker-local's throwaway Postgres container, the VPS's Postgres
  // role already exists outside this script's control -- see the comment
  // on pgPassDefault below.
  val isVps = target == "vps"
  // k8s is for the k8s/versola and k8s/loadgen Helm charts (see k8s/README.md).
  // Unlike docker-local/vps it stays interactive: a k8s deployment is a manual,
  // occasional bootstrap (no versola-cli target drives it, unlike vps), and the campaign topology genuinely needs a human's
  // input at several prompts a shared default can't safely guess -- three
  // independent Postgres instances (one per service, not one host split by
  // ?currentSchema=) and auth's internal address, which under an Ingress is
  // never the same as its public one. The plain interactive branch below
  // already asks for all of these one at a time; the one thing "k8s" changes
  // is `useOpenBao`, so every secret becomes a `${VAR}` placeholder instead of
  // a literal value -- which is what the chart's Secret-based wiring requires
  // (see versola.secretEnv in k8s/versola/templates/_helpers.tpl).
  val isKubernetes = target == "k8s"
  // The literal string written into the generated config's own `env`
  // field below -- deliberately a different variable from `target` above,
  // which only picks which of THIS script's own branches to run (network
  // defaults, interactive vs not). "vps" is a target, not an environment:
  // the same VPS could run "prod" today and "qa" tomorrow, so target
  // alone can't answer what belongs in this field (goshacodes' review on
  // versolauth/versola#176: "vps is not an env ... if you need
  // customization, you need a separate param").
  //
  // VersolaApp.envName (see util/http/VersolaApp.scala) treats exactly
  // one string, "prod", as EnvName.Prod; every other value becomes
  // EnvName.Test(value), which gates test-only behavior (e.g.
  // deterministic OTP codes instead of real delivery, per
  // BootstrapService.adminAuthFactors/adminPhone) that must never run
  // against a deployment serving real users.
  //
  // docker-local's throwaway stack has no such ambiguity -- it's always a
  // test env, fixed here rather than asked about. vps reads it from
  // ENV_NAME instead of hardcoding "prod": entrypoint.sh's caller
  // (versola-cli, non-interactively) sets that explicitly, so it's a
  // param this script is handed, not one it guesses from target -- see
  // versola-cli's pullAndRunTools for where that's set. Everything else
  // (isLocal, and real interactive deployments where a human just types
  // the name at the prompt above) keeps using target verbatim, same as
  // always -- typing "prod" here still works exactly like it did before
  // vps existed as a non-interactive target.
  val env =
    if isDockerLocal then "docker-local"
    else if isVps then sys.env.getOrElse("ENV_NAME", "prod")
    else target
  // Every secret field this script generates becomes a `${VAR}` HOCON
  // placeholder (resolved via OpenBao by versola-cli) in both
  // non-interactive Docker envs, not just docker-local -- see
  // secretField's own comment.
  val useOpenBao = isDockerLocal || isVps || isKubernetes
  val configurationCacheRefreshInterval = if isLocal || isDockerLocal then "1 minute" else "5 minutes"
  // Postgres user/password are the same across all three services either
  // way; computed once here so the Auth/Central/Edge sections below don't
  // each repeat the useOpenBao check above, rather than the specific targets composing it.
  //
  // vps's password looks random-per-run below (rand(rng, 24) does run
  // every time), but what actually reaches the config is whatever
  // secretField placeholders it into -- versola-cli resolves that against
  // OpenBao the same as any other secret, and an existing value there wins
  // over this freshly generated one. So this value only becomes the real
  // password on the first `configure` against an empty OpenBao: versola-cli
  // then stores it and prints the CREATE ROLE / ALTER ROLE statement that
  // gives the `versola_app` role this password (see deploy.md, 3.3), and
  // every later run reuses the stored one. A server whose role already has
  // a password of its own gets that one written into OpenBao instead (see
  // develop.md, "Onboarding a deployment that already has secrets").
  val pgUserDefault = if isDockerLocal then "versola" else if isVps then "versola_app" else "dev"
  val pgPassDefault = if isDockerLocal then "versola" else if isVps then rand(rng, 24) else "1234"
  // isLocal, docker-local and vps are all non-interactive; only the values differ.
  interactive = !(isLocal || isDockerLocal || isVps)
  if isLocal then println("  local env — using defaults, skipping prompts")
  if isDockerLocal then println("  docker-local env — using bridge-network defaults, skipping prompts")
  if isVps then println("  vps env — using host-network defaults, skipping prompts")
  if isKubernetes then println("  k8s env — interactive, every secret placeholdered out")

  // central refuses to seed itself an admin API nobody can call: both blocks below are
  // always emitted (bootstrap.resource-secret and bootstrap.utility-client), never left
  // empty, regardless of target -- BootstrapService fills the resource secret in ONCE, the
  // first time the resource doesn't exist yet, so a value that isn't here at first boot has
  // no later config-only recovery (versolauth/versola#380). The utility client's public key
  // is different: seedUtilityClient re-applies it on EVERY boot, so a pair that changes
  // between runs replaces the key central trusts. Pinned in local dev so
  // e2e tests can rely on a stable value they can hardcode against; every other target
  // gets this run's own freshly generated one, placeholdered out exactly like every other
  // secret when useOpenBao is set (see secretField's own comment for what resolves it on
  // each target).
  //
  // The resource-secret line MUST end with "\n": centralConf below interpolates this value
  // into "|${bootstrapResourceSecretLine}|}" — the `}` on that source line is its own
  // stripMargin-delimited line only because this value supplies the newline that precedes
  // it. Without the newline, stripMargin leaves a literal "|}" in the generated HOCON,
  // which fails to parse.
  val bootstrapResourceSecretLine =
    if isLocal then "  resource-secret = \"ZGV2LWNlbnRyYWwtYWRtaW4tc2VjcmV0LTMyYnl0ZXM\"\n"
    else s"  resource-secret = ${secretField(useOpenBao, centralResourceSecretGenerated, "CENTRAL_RESOURCE_SECRET")}\n"

  // The utility client `loadgen provision` authenticates as, so that it reaches central's
  // admin API through edge's proxy instead of holding an internal secret. Client id stays
  // a literal on every target -- it isn't secret, and central only needs loadgen's own
  // config (provision.provisioner-client-id) to name the same string.
  //
  // It authenticates with RFC 7523 `private_key_jwt` and DPoP-bound tokens, which is what the
  // default tenant's FAPI 2.0 profile admits (versolauth/versola#424, #445). Central gets the
  // public half; loadgen gets the private half as `provision.provisioner-private-key`:
  //   - local: a pinned pair, so e2e and a developer's loadgen can hardcode the private half
  //     the same way they do every other pinned local secret. Dev only -- it is committed.
  //   - docker-local, vps, k8s: this run's own pair. The public half is placeholdered and
  //     travels in central.generated-secrets.env exactly like JWKS_JSON and EDGE_PUBLIC_JWK, so
  //     OpenBao's existing-value-wins rule keeps central's key stable across runs. The private
  //     half goes to its own file, `utils.private-key.jwk`, and is deliberately
  //     NOT in any *.generated-secrets.env: versola-cli loads those into the container they
  //     are named for, and central must never hold the key that authenticates as `utils`.
  //     That file is authoritative only for the run that first populated central's OpenBao:
  //     a later run generates a fresh pair, and where the stored public half is not the one
  //     placeholdered (no OpenBao: k8s) central would take the new one on its next boot, so
  //     only the file from the first run matches what central trusts. Keep that file, not a
  //     regenerated one -- which is why the schema marks the pair first-install-only.
  //   A deployment that seeded `utils` with a client_secret under an older generator cannot take
  //   this config as is: central refuses a boot that calls for another method than the client
  //   holds (see k8s/README.md, "Upgrading a deployment that already seeded utils").
  // UTILS_PRIVATE_KEY_JWK (the JWK itself, i.e. the contents of utils.private-key.jwk from an
  // earlier run) reuses that pair instead of generating one, so a deployment that already holds
  // its key -- every k8s one, which has nothing like versola-cli's OpenBao to keep it -- gets a
  // central.conf that still matches it. versola-cli does the equivalent itself from OpenBao.
  val reusedUtilityKey: Option[(String, String)] =
    sys.env.get("UTILS_PRIVATE_KEY_JWK").map(_.trim).filter(_.nonEmpty).map: jwk =>
      def field(name: String): String =
        s""""$name"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(jwk).map(_.group(1)).getOrElse(
          throw RuntimeException(s"UTILS_PRIVATE_KEY_JWK has no \"$name\" member"),
        )
      val kid = s""""kid"\\s*:\\s*"([^"]*)"""".r.findFirstMatchIn(jwk).map(_.group(1)).getOrElse("utils")
      field("d") // must be a private key
      val publicJwk = s"""{"kty":"${field("kty")}","crv":"${field("crv")}","x":"${field("x")}","y":"${field("y")}","use":"sig","kid":"$kid","alg":"ES256"}"""
      (publicJwk, jwk)
  val utilityPublicJwk =
    if isLocal then """{"kty":"EC","crv":"P-256","x":"Rst-brXjn7AQChQkaCwR6Vf5-nlVw4SDw-swh8g3GdU","y":"gD6MZlaRGOf1MColB6GhG5N3TdvJGsiF1J7_jYNAgfo","use":"sig","kid":"utils-local","alg":"ES256"}"""
    else reusedUtilityKey.fold(utilityKey.jwk)(_._1)
  val utilityPrivateJwk =
    if isLocal then """{"kty":"EC","crv":"P-256","x":"Rst-brXjn7AQChQkaCwR6Vf5-nlVw4SDw-swh8g3GdU","y":"gD6MZlaRGOf1MColB6GhG5N3TdvJGsiF1J7_jYNAgfo","d":"jWGh5lV46NJ3RwT8kJ5lfBeBTGBtXnM5V3gwgAEYpXM","use":"sig","kid":"utils-local","alg":"ES256"}"""
    else reusedUtilityKey.fold(utilityKey.privateJwk)(_._2)
  // Several lines rather than one, so unlike the resource-secret line above it supplies its
  // own newlines and nothing follows it on a line.
  val bootstrapUtilityClientLines =
    "  utility-client {\n" +
      "    client-id = \"utils\"\n" +
      s"    public-key-jwk = ${secretKeyField(useOpenBao, utilityPublicJwk, "UTILITY_CLIENT_PUBLIC_JWK")}\n" +
      "  }\n"

  // Same reasoning for the "auth" resource secret: e2e tests call auth's additional
  // listener (Account Settings) directly, with the Basic credentials edge would use.
  val accountResourceSecret =
    if isLocal then "ZGV2LWF1dGgtYWNjb3VudC1zZWNyZXQtMzJieXRlcyE" else accountResourceSecretGenerated

  // Same reasoning again: e2e calls edge's own /service/configuration/sync directly, so
  // it needs a value it can hardcode rather than one generated fresh on every run.
  val edgeInternalSecretValue =
    if isLocal then "ZGV2LWVkZ2UtaW50ZXJuYWwtc2VjcmV0LTMyYnl0ZSE" else edgeInternalSecret

  // ── Service URLs ──────────────────────────────────────────────────────────────
  // authUrl      – public-facing URL (JWT issuer, server metadata, browser redirects via edge).
  // authInternalUrl – internal S2S URL used by central to call auth's admin APIs.
  //                   Defaults to authUrl; override in k8s / service-mesh deployments.
  section("\n── Service URLs ──────────────────────────────────────────────────────")
  // authUrl is a public-facing string (JWT issuer, browser redirects) — it
  // never needs to be a Docker service name, even in docker-local, since
  // browsers/JWT verifiers reach it via the host's published port either way.
  val authUrlDefault      = if isDockerLocal then "http://localhost:2821" else if isVps then requiredEnv("AUTH_URL") else "http://localhost:9003"
  val authUrl              = prompt(s"  Auth public URL [$authUrlDefault]: ", authUrlDefault, flag = "auth-url")
  val passkeyRpId         = URI.create(authUrl).getHost
  // authInternalUrl, unlike authUrl, IS a real network call — central uses it
  // to reach auth's admin API server-to-server. Defaulting this to authUrl
  // (as the non-docker-local branch below does) is correct when both share
  // the host's network, but would silently break in docker-local: central's
  // container can't reach auth via "localhost", it needs auth's Compose
  // service name.
  val authInternalDefault = if isDockerLocal then "http://auth:8080" else authUrl
  val authInternalUrl     = prompt(s"  Auth internal URL [$authInternalDefault]: ", authInternalDefault, flag = "auth-internal-url")
  // edgeInternalUrl / edgeInternalTrustPath -- versola-internal-url and
  // versola-internal-trusted-certificates in edge's own config (EdgeConfig.scala), which
  // SSOClient.scala uses only for the calls it makes as an RFC 8705 mutual-TLS client. Not
  // authInternalUrl above: that variable is central's own S2S call to auth's admin API, an
  // unrelated feature this must not start rerouting.
  //
  // isLocal alone gets a real TLS terminator (nginx, generated below) rather than reusing
  // authInternalUrl's plain address: SSOClient.scala fails closed without a trust anchor for
  // this credential (there is no secure default to fall back to -- see its own comment), so
  // e2e's mTLS-fronted client can only be exercised against something that actually
  // terminates TLS. docker-local/vps/interactive are left exactly as before -- absent here,
  // same as every environment before this credential existed -- until whoever operates one
  // decides what terminates TLS in front of their own auth.
  val edgeInternalTlsDir = File("edge/dev/internal-tls")
  val edgeInternalTlsPort = 9443
  val edgeInternalUrl = if isLocal then s"https://localhost:$edgeInternalTlsPort" else authInternalUrl
  val edgeInternalTrustPath: Option[String] =
    if isLocal then Some(File(edgeInternalTlsDir, "server.crt").getAbsolutePath) else None
  // #353: `central-admin` is authenticated by edge with `tls_client_auth` through that same
  // terminator, which is the only credential the default tenant's FAPI 2.0 profile admits for
  // it. isLocal alone, for the same reason: docker-local/vps/interactive have no terminator in
  // front of auth, so central-admin stays on its client_secret there (bootstrap seeds it
  // outside the profile, with a warning -- see BootstrapService). The CA has to exist before
  // central's config is rendered, because the certificate it signs goes into that config; it
  // is generated here, once, and not again when the files are written below -- a second CA
  // would leave this certificate signed by an issuer nginx no longer advertises.
  val centralAdminMtlsLines =
    if isLocal then
      genInternalTlsCertificate(edgeInternalTlsDir)
      val pem = genCentralAdminCertificate(edgeInternalTlsDir)
      val tripleQuote = "\"" * 3
      "  central-admin-mtls {\n" +
        s"    certificate = $tripleQuote$pem$tripleQuote\n" +
        "    certificate-header = \"ssl-client-cert\"\n" +
        "    certificate-encoding = \"urlEncodedPem\"\n" +
        "  }\n"
    else ""
  val edgeInternalTrustLine =
    edgeInternalTrustPath.fold("")(path => s"""versola-internal-trusted-certificates = ["$path"]\n""")
  // #440: the CA central issues edge client certificates from, for a registration that asks for
  // one. The terminator's own, for the reason central-admin's certificate is signed by it: it is
  // the one issuer nginx advertises, so the only one edge will present a certificate from. Auth's
  // mutual-TLS listener trusts it as well (`authMutualTlsTrustedClients` below), so the same
  // certificate serves an edge-fronted native client there. isLocal alone, like the CA itself.
  val centralClientCertificateAuthorityBlock =
    if isLocal then
      s"""
         |client-certificate-authority {
         |  certificate = "${File(edgeInternalTlsDir, "ca.crt").getAbsolutePath}"
         |  private-key = "${File(edgeInternalTlsDir, "ca.key").getAbsolutePath}"
         |}
         |""".stripMargin
    else ""
  val authAdditionalDefault =
    if isDockerLocal then "http://auth:8082"
    else if isVps then "http://127.0.0.1:8082"
    else if isLocal then "http://localhost:9007"
    else "http://localhost:8082"
  val authAdditionalUrl = prompt(s"  Auth additional URL [$authAdditionalDefault]: ", authAdditionalDefault, flag = "auth-additional-url")

  // RFC 8705 §5: `auth`'s own mutual-TLS listener, terminating TLS itself rather than
  // reading a header a proxy forwarded (contrast `edgeInternalTlsDir` above, which is that
  // header path's terminator). `isLocal`-only for the same reason `edgeInternalTlsDir` is:
  // docker-local/vps/interactive are left exactly as before -- absent, no listener -- until
  // whoever operates one decides what certificate it should present. e2e's own client
  // certificate is signed by the same CA and generated alongside it below.
  // Everywhere but `local`: where the listener's files are mounted, and the address edge reaches
  // it on -- auth's own host (authInternalUrl's) on the mutual-TLS port. docker-local/vps get the
  // files from the bundle's `mtls-init` service (a shared volume at /app/mtls); k8s from the
  // chart's `pki` block, which mounts a cert-manager Secret per purpose.
  val mtlsServerCertificate = if isKubernetes then "/app/mtls/server/tls.crt" else "/app/mtls/server.crt"
  val mtlsServerKey = if isKubernetes then "/app/mtls/server/tls.key" else "/app/mtls/server.key"
  val mtlsTrustedCertificates = if isKubernetes then "/app/mtls/trust/ca.crt" else "/app/mtls/ca.crt"
  val mtlsAuthPin = if isKubernetes then "/app/mtls/auth-pin/tls.crt" else "/app/mtls/server.crt"
  // vps: authInternalUrl is the public domain there, which nothing proxies to this port -- edge
  // is on the same host and reaches the listener (bound to BIND_HOST) on loopback.
  val mtlsExternalUrl =
    if isVps then "https://127.0.0.1:8083" else s"https://${URI.create(authInternalUrl).getHost}:8083"
  val authMutualTlsDir = File("auth/dev/mtls")
  val authMutualTlsPort = 9008
  val authMutualTlsUrl = s"https://localhost:$authMutualTlsPort"
  // The listener's own CA, which e2e's client certificate chains to, and the terminator's, which
  // central issues edge client certificates from (`centralClientCertificateAuthorityBlock`). One
  // PEM bundle: the trust manager loads every certificate in it.
  val authMutualTlsTrustedClients = File(authMutualTlsDir, "trusted-clients.crt")
  val authMutualTlsBlock =
    if isLocal then
      s"""
         |mutual-tls {
         |  certificate            = "${File(authMutualTlsDir, "server.crt").getAbsolutePath}"
         |  private-key            = "${File(authMutualTlsDir, "server.key").getAbsolutePath}"
         |  trusted-certificates   = "${authMutualTlsTrustedClients.getAbsolutePath}"
         |  external-url           = "$authMutualTlsUrl"
         |}
         |""".stripMargin
    else
      s"""
         |# RFC 8705 §5's listener (MPORT, #440). The files are put there by whatever issues them:
         |# the `mtls-init` service in the compose bundle (step-ca), or the chart's `pki` block
         |# (cert-manager) -- see develop.md "Certificate storage and rotation".
         |mutual-tls {
         |  certificate            = "$mtlsServerCertificate"
         |  private-key            = "$mtlsServerKey"
         |  trusted-certificates   = "$mtlsTrustedCertificates"
         |  external-url           = "$mtlsExternalUrl"
         |}
         |""".stripMargin

  // #420: edge's native-app back channel, straight to the listener above -- no terminator in
  // between. `trusted-certificates` pins the listener's own server certificate (a leaf, which
  // EdgeConfig.validated insists on). Local-only, like the listener itself.
  val edgeNativeBlock =
    if isLocal then
      s"""
         |native {
         |  auth-mutual-tls-url   = "$authMutualTlsUrl"
         |  trusted-certificates  = ["${File(authMutualTlsDir, "server.crt").getAbsolutePath}"]
         |  blob-key              = ${secretField(useOpenBao, edgeNativeBlobKey, "EDGE_NATIVE_BLOB_KEY")}
         |}
         |""".stripMargin
    else
      s"""
         |# #420/#440: edge's native-app back channel, straight to auth's mutual-TLS listener. The
         |# pin is a list so auth's listener certificate can be rotated with an overlap window.
         |native {
         |  auth-mutual-tls-url   = "$mtlsExternalUrl"
         |  trusted-certificates  = ["$mtlsAuthPin"]
         |  blob-key              = ${secretField(useOpenBao, edgeNativeBlobKey, "EDGE_NATIVE_BLOB_KEY")}
         |}
         |""".stripMargin

  // centralUrl IS a real network call from both auth and edge, so it needs
  // the same treatment.
  // Reverted to 9001 (not 8090, which every other branch here uses) --
  // ci-cd.yml's e2e job hardcodes `PORT=9001` when it starts central
  // directly (not through Docker) for this exact "local" branch, so this
  // default has to keep matching that or auth can't reach it at all
  // (confirmed by hand: unifying this to 8090 broke that job outright --
  // auth retried against the wrong port until it OOM'd). goshacodes'
  // port-consistency comment on #176 was about the interactive-vs-docker
  // discrepancy in general; this one specific value turned out to be
  // load-bearing for CI, not just a cosmetic mismatch.
  val centralUrlDefault   = if isDockerLocal then "http://central:8090" else if isVps then "http://127.0.0.1:8090" else "http://localhost:9001"
  val centralUrl           = prompt(s"  Central URL [$centralUrlDefault]: ", centralUrlDefault, flag = "central-url")
  // edgeUrl is public-facing only, same reasoning as authUrl above — BUT
  // in docker-local, nginx (not edge's own port) is the actual public
  // entry point a browser can reach. edge's own port (8095) isn't
  // published to the host at all; the nginx config used by "versola
  // bootstrap local" (currently in the separate versola-cli repo, not
  // this one) already proxies /complete, /login, /resources,
  // /permissions through to edge internally. Confirmed by hand: pointing
  // this at 8095 sent the post-login redirect straight to a closed port
  // and the browser got ERR_CONNECTION_REFUSED right after a real login
  // succeeded.
  val edgeUrlDefault      = if isDockerLocal then "http://localhost:2821" else if isVps then authUrl else "http://localhost:9005"
  val edgeUrl              = prompt(s"  Edge URL [$edgeUrlDefault]: ", edgeUrlDefault, flag = "edge-url")
  section("\n── Auth service ──────────────────────────────────────────────────────")
  // Postgres is its own container in docker-local (compose service name
  // "postgres"), and all three services share one database via
  // ?currentSchema=, same as prod (see deploy.md) rather than each getting
  // its own database.
  //
  // pgHostDefault (host:port) is vps-only and, like AUTH_URL, has no
  // sensible default here: whether Postgres runs on the same box
  // (127.0.0.1, this deployment's current setup) or somewhere else
  // entirely (managed Postgres, a separate DB server) is specific to
  // whoever's deploying, not something this script should assume for
  // every future target (goshacodes' review on versolauth/versola#176:
  // "user should provide this URL, we should not set defaults"). One
  // value, not three -- all three services share the same host, just a
  // different ?currentSchema=.
  val pgHostDefault = if isVps then requiredEnv("POSTGRES_HOST") else ""
  val authPgUrlDefault = if isDockerLocal then "jdbc:postgresql://postgres:5432/auth?currentSchema=auth" else if isVps then s"jdbc:postgresql://$pgHostDefault/auth?currentSchema=auth" else "jdbc:postgresql://localhost:5432/auth"
  val authPgUrl        = prompt(s"  Postgres URL [$authPgUrlDefault]: ", authPgUrlDefault, flag = "auth-postgres-url")
  val authPgUser       = prompt(s"  Postgres user [$pgUserDefault]: ", pgUserDefault, flag = "auth-postgres-user")
  val authPgPass       = prompt(s"  Postgres password [$pgPassDefault]: ", pgPassDefault, flag = "auth-postgres-password")

  section("\n── Auth bootstrap admin user ──────────────────────────────────────────────")
  val bootstrapLogin    = prompt("  Admin login [admin]: ", "admin", flag = "admin-login")
  // vps's default here is a freshly random value, not the fixed
  // "Admin1234!" the other envs use -- unlike Postgres's password (see
  // pgPassDefault above), nothing outside this script already owns this
  // value, so there's no real password to match: OpenBao generating and
  // keeping the first one it sees is exactly right here, no manual
  // seeding needed.
  val bootstrapPasswordDefault = if isVps then rand(rng, 16) else "Admin1234!"
  val bootstrapPassword = prompt("  Admin bootstrap password [Admin1234!]: ", bootstrapPasswordDefault, flag = "admin-password")

  section("\n── Central service ───────────────────────────────────────────────────")
  // edgeCompleteUrl (edgeUrl + "/complete") is always appended to this list
  // further down regardless of what's entered here, so this default mainly
  // matters for postLoginRedirectUri (the *first* entry). It used to default
  // to edgeCompleteUrl itself -- confirmed by hand that this is broken: it
  // sends the browser back to /complete a second time with no code/state,
  // which 500s (MissingQueryParams) since that endpoint always requires
  // them. Pointing it at nginx's /central/admin/ path instead means a
  // finished login lands somewhere that either works (once "versola
  // bootstrap" wires up central-ui, see versola-cli) or 404s cleanly, not a
  // crash loop. Still not localhost:3000 -- nothing runs there in
  // docker-local.
  val redirectUriDefault  = if isDockerLocal then s"$edgeUrl/central/admin/" else if isVps then s"$authUrl/central/admin/" else "http://localhost:3000"
  val centralRedirectUris = prompt(s"  Admin panel bootstrap redirect URIs (comma-separated) [$redirectUriDefault]: ", redirectUriDefault, flag = "central-redirect-uris")
  val centralPgUrlDefault = if isDockerLocal then "jdbc:postgresql://postgres:5432/auth?currentSchema=central" else if isVps then s"jdbc:postgresql://$pgHostDefault/auth?currentSchema=central" else "jdbc:postgresql://localhost:5432/auth"
  val centralPgUrl        = prompt(s"  Postgres URL [$centralPgUrlDefault]: ", centralPgUrlDefault, flag = "central-postgres-url")
  val centralPgUser       = prompt(s"  Postgres user [$pgUserDefault]: ", pgUserDefault, flag = "central-postgres-user")
  val centralPgPass       = prompt(s"  Postgres password [$pgPassDefault]: ", pgPassDefault, flag = "central-postgres-password")


  // "dpop_signing_alg_values_supported" below (RFC 9449 §5.1) is not a mirror of anything:
  // auth reads the set a proof is checked against straight off this document, so editing it
  // here is how a deployment narrows or widens what it accepts. An entry auth has no verifier
  // for is dropped rather than advertised; drop the field entirely to fall back to its default.
  // "token_endpoint_auth_signing_alg_values_supported" (RFC 8414 §2) works the same way for the
  // `alg` of an RFC 7523 client assertion, and "request_object_signing_alg_values_supported"
  // (RFC 9101 §4) for the `alg` of a JAR request object.
  //
  // "authorization_signing_alg_values_supported" (JARM §4) has no line below, unlike its
  // siblings above: it is derived from the synced JWKS itself (see `ServedMetadata.derive`),
  // not read off this stored document, so a value written into it here would be computed once
  // and then silently replaced -- never served, never even wrong on its own terms. What a
  // JARM response is signed with is a fact about the tenant's own signing key, not something
  // this document could state independently of the JWKS without risking the two disagreeing.
  val metadata =
    s"""{
       |  "issuer": "$authUrl",
       |  "authorization_endpoint": "$authUrl/authorize",
       |  "token_endpoint": "$authUrl/token",
       |  "userinfo_endpoint": "$authUrl/userinfo",
       |  "jwks_uri": "$authUrl/.well-known/jwks.json",
       |  "introspection_endpoint": "$authUrl/introspect",
       |  "revocation_endpoint": "$authUrl/revoke",
       |  "pushed_authorization_request_endpoint": "$authUrl/par",
       |  "end_session_endpoint": "$authUrl/logout",
       |  "scopes_supported": ["openid", "profile", "email", "phone", "offline_access"],
       |  "response_types_supported": ["code", "code id_token"],
       |  "response_modes_supported": ["query", "fragment", "jwt", "query.jwt", "fragment.jwt"],
       |  "code_challenge_methods_supported": ["S256"],
       |  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token"],
       |  "subject_types_supported": ["public", "pairwise"],
       |  "id_token_signing_alg_values_supported": ["PS256"],
       |  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post", "private_key_jwt"],
       |  "token_endpoint_auth_signing_alg_values_supported": ["ES256", "PS256"],
       |  "dpop_signing_alg_values_supported": ["ES256", "PS256"],
       |  "request_object_signing_alg_values_supported": ["ES256", "PS256"],
       |  "claims_supported": ["sub", "iss", "aud", "exp", "iat", "jti", "nonce", "auth_time", "acr", "amr", "sid"],
       |  "frontchannel_logout_supported": true,
       |  "frontchannel_logout_session_supported": true,
       |  "backchannel_logout_supported": true,
       |  "backchannel_logout_session_supported": true,
       |  "authorization_response_iss_parameter_supported": true
       |}""".stripMargin

  section("\n── Edge service ──────────────────────────────────────────────────────")
  val edgePgUrlDefault = if isDockerLocal then "jdbc:postgresql://postgres:5432/auth?currentSchema=edge" else if isVps then s"jdbc:postgresql://$pgHostDefault/auth?currentSchema=edge" else "jdbc:postgresql://localhost:5432/auth"
  val edgePgUrl        = prompt(s"  Postgres URL [$edgePgUrlDefault]: ", edgePgUrlDefault, flag = "edge-postgres-url")
  val edgePgUser       = prompt(s"  Postgres user [$pgUserDefault]: ", pgUserDefault, flag = "edge-postgres-user")
  val edgePgPass       = prompt(s"  Postgres password [$pgPassDefault]: ", pgPassDefault, flag = "edge-postgres-password")
  // The schema says vps has one POSTGRES_PASSWORD for all three services, and versola-cli stores
  // it as one value; the three flags would let a run say otherwise. Only the password is
  // checked: the users and URLs have their own flags and are not secrets.
  if isVps then SecretSchema.verifySharedPostgresPassword(authPgPass, centralPgPass, edgePgPass)

  // Edge complete URL is always added as a registered redirect URI so the preset can use it.
  val edgeCompleteUrl        = s"$edgeUrl/complete"
  // OP-initiated front-channel logout is loaded by the browser, so it needs a publicly
  // reachable URL. Locally, edge is exposed directly on its own port (edgeUrl); in
  // production it's path-routed behind auth's public domain instead (see deploy.md).
  val frontChannelLogoutUri = if isLocal then s"$edgeUrl/logout/frontchannel" else s"$authUrl/logout/frontchannel"
  val centralRedirectUriList =
    (centralRedirectUris.split(",").map(_.trim) :+ edgeCompleteUrl)
      .distinct
      .map(u => s""""$u"""")
      .mkString(", ")
  val postLoginRedirectUri   = centralRedirectUris.split(",").map(_.trim).head
  val passkeyOrigins = List(authUrl, edgeUrl).distinct.map(u => "\"" + u + "\"").mkString(", ")

  // ── OTP provider ──────────────────────────────────────────────────────────────
  section("\n── OTP Provider ──────────────────────────────────────────────────────")
  val wantsOtp = promptYN("Configure OTP provider?", flag = "otp")
  val otpBlock =
    if wantsOtp then
      val url    = prompt("  OTP provider URL: ", "http://localhost:9100/sms", flag = "otp-url")
      val method = prompt("  HTTP method [POST]: ", "POST", flag = "otp-method")
      val uname  = prompt("  Username (empty = none): ", flag = "otp-username")
      val pass   = prompt("  Password (empty = none): ", flag = "otp-password")
      val uLine  = if uname.nonEmpty then s"""  username = "$uname"\n""" else ""
      val pLine  = if pass.nonEmpty  then s"""  password = "$pass"\n""" else ""
      s"""
         |otp-provider {
         |  method = "$method"
         |  url = "$url"
         |  ${uLine}
         |  ${pLine}
         |  body {
         |    # phones = "{{phone}}"
         |    # mes = "{{message}}"
         |  }
         |}
         |""".stripMargin
    else
      """
        |# otp-provider {
        |#   method = "POST"
        |#   url = ""
        |#   username = ""
        |#   password = ""
        |#   body {
        |#     phones = "{{phone}}"
        |#     mes = "{{message}}"
        |#   }
        |# }
        |""".stripMargin

  // ── SMTP ──────────────────────────────────────────────────────────────────────
  section("\n── SMTP ──────────────────────────────────────────────────────────────")
  val wantsSmtp = promptYN("Configure SMTP?", flag = "smtp")
  val smtpBlock =
    if wantsSmtp then
      val host    = prompt("  Host: ", "localhost", flag = "smtp-host")
      val portStr = prompt("  Port [587]: ", "587", flag = "smtp-port")
      val port    = portStr.toIntOption.getOrElse(587)
      val uname   = prompt("  Username: ", "dev", flag = "smtp-username")
      val pass    = prompt("  Password: ", "dev", flag = "smtp-password")
      val from    = prompt("  From email [noreply@example.com]: ", "noreply@example.com", flag = "smtp-from")
      val subj    = prompt("  Subject [Your verification code]: ", "Your verification code", flag = "smtp-subject")
      val tls     = promptYN("  Use STARTTLS?", defaultYes = true, flag = "smtp-starttls")
      s"""
         |smtp {
         |  host = "$host"
         |  port = $port
         |  username = "$uname"
         |  password = "$pass"
         |  from = "$from"
         |  subject = "$subj"
         |  start-tls = $tls
         |}
         |""".stripMargin
    else
      """
        |# smtp {
        |#   host = ""
        |#   port = 587
        |#   username = ""
        |#   password = ""
        |#   from = ""
        |#   subject = ""
        |#   start-tls = true
        |# }
        |""".stripMargin

  // ── Build config files ────────────────────────────────────────────────────────

  val authConf =
    s"""env = $env
       |
       |configuration-cache-refresh-interval = "$configurationCacheRefreshInterval"
       |
       |# otel-exporter = "http://localhost:4317"
       |
       |bootstrap {
       |  login = "$bootstrapLogin"
       |  password = ${secretField((isVps || isKubernetes), bootstrapPassword, "ADMIN_BOOTSTRAP_PASSWORD")}
       |  admin-user-id = "$adminUserId"
       |}
       |
       |security {
       |  access-tokens-secret         = ${secretField(useOpenBao, accessTokensSecret, "ACCESS_TOKENS_SECRET")}
       |  client-secrets-secret        = ${secretField(useOpenBao, clientSecretsSecret, "CLIENT_SECRETS_SECRET")}
       |  refresh-tokens-secret        = ${secretField(useOpenBao, refreshTokensSecret, "REFRESH_TOKENS_SECRET")}
       |  auth-codes-secret            = ${secretField(useOpenBao, authCodesSecret, "AUTH_CODES_SECRET")}
       |  sessions-secret              = ${secretField(useOpenBao, sessionsSecret, "SESSIONS_SECRET")}
       |  passwords-secret             = ${secretField(useOpenBao, passwordsSecret, "PASSWORDS_SECRET")}
       |  conversation-cookie-secret   = ${secretField(useOpenBao, conversationCookieSecret, "CONVERSATION_COOKIE_SECRET")}
       |  session-cookie-secret        = ${secretField(useOpenBao, sessionCookieSecret, "SESSION_COOKIE_SECRET")}
       |  user-agent-cookie-secret     = ${secretField(useOpenBao, userAgentCookieSecret, "USER_AGENT_COOKIE_SECRET")}
       |  par-requests-secret          = ${secretField(useOpenBao, parRequestsSecret, "PAR_REQUESTS_SECRET")}
       |  dpop-nonces-secret           = ${secretField(useOpenBao, dpopNoncesSecret, "DPOP_NONCES_SECRET")}
       |}
       |
       |par {
       |  request-uri-ttl  = "60 seconds"
       |  max-request-size = 8192
       |}
       |$authMutualTlsBlock
       |# Admission control for Argon2id password hashing, which runs on ZIO's unbounded
       |# blocking pool (see Argon2Config). max-concurrent bounds concurrent password hashes:
       |# each holds ~19 MiB of heap for its duration, so worst-case hashing heap is roughly
       |# max-concurrent * 19 MiB -- the default of 12 (~228 MiB) is sized for auth's 512m
       |# mem_limit. Raise it only together with the container's memory limit, or logins
       |# will OOM the pod. Overridable via ARGON2_MAX_CONCURRENCY without editing this file.
       |argon2 {
       |  max-concurrent = 12
       |  max-concurrent = $${?ARGON2_MAX_CONCURRENCY}
       |}
       |
       |jwt {
       |  issuer = "$authUrl"
       |  private-key = ${secretKeyField(useOpenBao, jwtKey.privateB64, "JWT_PRIVATE_KEY")}
       |}
       |
       |central {
       |  url = "$centralUrl"
       |  secret-key = ${secretField(useOpenBao, centralSecretKey, "CENTRAL_SECRET_KEY")}
       |}
       |$otpBlock$smtpBlock
       |postgres {
       |  url = "$authPgUrl"
       |  user = "$authPgUser"
       |  password = ${secretField((isVps || isKubernetes), authPgPass, "POSTGRES_PASSWORD")}
       |  maximum-pool-size = 10
       |  minimum-idle = 10
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "60 seconds"
       |  # Absent unless POSTGRES_POOL_METRICS_INTERVAL is set, which leaves HikariCP
       |  # without a MetricsTrackerFactory and this pool publishing nothing -- the state
       |  # this service has always run in. Set it (e.g. "10 seconds") to put the pool's
       |  # occupancy, acquisition wait and timeouts on the db_client_connection_* series
       |  # k8s/loadgen/dashboards/db-pools.json reads.
       |  pool-metrics-interval = $${?POSTGRES_POOL_METRICS_INTERVAL}
       |}
       |
       |cleanup {
       |  max-threads = 2
       |  tables = [
       |    {
       |      table-name = "auth_conversations"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |    }
       |    {
       |      table-name = "authorization_codes"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |      key-column = "code"
       |    }
       |    {
       |      table-name = "pushed_authorization_requests"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |      key-column = "request_uri"
       |    }
       |    {
       |      table-name = "refresh_tokens"
       |      batch-size = 1000
       |      interval   = "10 minutes"
       |    }
       |    {
       |      table-name = "sso_sessions"
       |      batch-size = 1000
       |      interval   = "10 minutes"
       |    }
       |    {
       |      table-name = "challenge_throttle"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |      key-column = "ctid"
       |    }
       |    {
       |      table-name = "user_passwords"
       |      batch-size = 1000
       |      interval   = "12 hours"
       |    }
       |    {
       |      table-name = "user_agents"
       |      batch-size = 1000
       |      interval   = "10 minutes"
       |    }
       |  ]
       |}
       |""".stripMargin

  // #440: central issues and renews edge-fronted clients' certificates through a CA it holds no
  // key of. docker-local/vps: step-ca, through the provisioner key and root `mtls-init` copies
  // into central's volume (compose.fragment*.yml.template). k8s: cert-manager, through the
  // chart's issuer (the chart sets CLIENT_CERT_ISSUER_NAME). `local` has no CA and issues nothing.
  val clientCertificatesBlock =
    if isLocal then ""
    else if isKubernetes then
      """
        |# Certificates for edge-fronted clients (#440), issued through cert-manager with the pod's
        |# own service account. The chart sets CLIENT_CERT_ISSUER_NAME to its client CA issuer.
        |client-certificates {
        |  validity     = "14 days"
        |  renew-before = "4 days"
        |  cert-manager {
        |    issuer-name  = ${CLIENT_CERT_ISSUER_NAME}
        |    issuer-kind  = ${?CLIENT_CERT_ISSUER_KIND}
        |    issuer-group = ${?CLIENT_CERT_ISSUER_GROUP}
        |  }
        |}
        |""".stripMargin
    else
      val stepCaUrl = if isVps then "https://127.0.0.1:9000" else "https://step-ca:9000"
      s"""
         |# Certificates for edge-fronted clients (#440): central generates the key and the request,
         |# step-ca signs it through the `central` JWK provisioner, and central renews the
         |# certificate before it expires. Only the provisioner key is central's -- the CA's own
         |# keys stay in step-ca.
         |client-certificates {
         |  validity     = "14 days"
         |  renew-before = "4 days"
         |  step-ca {
         |    url             = "$stepCaUrl"
         |    root-certificate = "/app/ca/root_ca.crt"
         |    provisioner     = "central"
         |    provisioner-key = "/app/ca/provisioner.json"
         |  }
         |}
         |""".stripMargin

  val centralConf =
    s"""env = $env
       |
       |configuration-cache-refresh-interval = "$configurationCacheRefreshInterval"
       |
       |# otel-exporter = "http://localhost:4317"
       |$clientCertificatesBlock
       |bootstrap {
       |  login = "$bootstrapLogin"
       |  admin-user-id = "$adminUserId"
       |  redirect-uris = [$centralRedirectUriList]
       |  edges = [
       |    {
       |      id = "edge-default"
       |      public-key-jwk = ${secretKeyField(useOpenBao, edgeKey.jwk, "EDGE_PUBLIC_JWK")}
       |    }
       |  ]
       |  # Matches the JWT signing key in auth (jwt.private-key). Placeholdered
       |  # together with EDGE_PUBLIC_JWK above, not just JWT_PRIVATE_KEY in
       |  # auth.conf: this is the *public* half of that same key pair, and it
       |  # has to come from the same resolved-or-generated source as the
       |  # private half, or a second `configure` would reuse auth's old
       |  # private key from OpenBao while writing a freshly generated public
       |  # key here -- a pair that no longer matches, which is exactly what
       |  # broke edge's sync calls to central (401s) before this existed.
       |  jwks = ${secretKeyField(useOpenBao, jwks, "JWKS_JSON")}
       |  metadata = \"\"\"$metadata\"\"\"
       |  presets = [
       |    {
       |      id = "central-admin"
       |      description = "Central Admin Login"
       |      redirect-uri = "$edgeCompleteUrl"
       |      post-login-redirect-uri = "$postLoginRedirectUri"
       |      post-logout-redirect-uri = "$postLoginRedirectUri"
       |    }
       |  ]
       |  central-url = "$centralUrl"
       |  front-channel-logout-uri = "$frontChannelLogoutUri"
       |  passkey {
       |    rp-id = "$passkeyRpId"
       |    origins = [$passkeyOrigins]
       |  }
       |  auth-additional-url = "$authAdditionalUrl"
       |  auth-resource-secret = ${secretField(useOpenBao, accountResourceSecret, "ACCOUNT_RESOURCE_SECRET")}
       |${centralAdminMtlsLines}${bootstrapUtilityClientLines}${bootstrapResourceSecretLine}|}
       |
       |secret-key = ${secretField(useOpenBao, centralSecretKey, "CENTRAL_SECRET_KEY")}
       |client-secrets-secret = ${secretField(useOpenBao, clientSecretsSecret, "CLIENT_SECRETS_SECRET")}
       |
       |auth {
       |  url = "$authInternalUrl"
       |}
       |$centralClientCertificateAuthorityBlock
       |user-outbox {
       |  poll-interval = 1 second
       |  batch-size = 32
       |  lease = 1 minute
       |  max-backoff = 5 minutes
       |  max-attempts = 5
       |}
       |
       |postgres {
       |  url = "$centralPgUrl"
       |  user = "$centralPgUser"
       |  password = ${secretField((isVps || isKubernetes), centralPgPass, "POSTGRES_PASSWORD")}
       |  maximum-pool-size = 15
       |  minimum-idle = 15
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "60 seconds"
       |  # Absent unless POSTGRES_POOL_METRICS_INTERVAL is set, which leaves HikariCP
       |  # without a MetricsTrackerFactory and this pool publishing nothing -- the state
       |  # this service has always run in. Set it (e.g. "10 seconds") to put the pool's
       |  # occupancy, acquisition wait and timeouts on the db_client_connection_* series
       |  # k8s/loadgen/dashboards/db-pools.json reads.
       |  pool-metrics-interval = $${?POSTGRES_POOL_METRICS_INTERVAL}
       |}
       |""".stripMargin

  val edgeConf =
    s"""env = $env
       |
       |configuration-cache-refresh-interval = "$configurationCacheRefreshInterval"
       |
       |# otel-exporter = "http://localhost:4317"
       |
       |id = "edge-default"
       |
       |# Placeholdered together with EDGE_PRIVATE_KEY below, not left as a
       |# plain literal: edgeKey.kid is derived from *today's* date (see
       |# genRsaKey's caller above), so a literal here would silently drift to
       |# a new kid on every later run even when EDGE_PRIVATE_KEY itself
       |# resolves back to an older, already-stored key from OpenBao -- signing
       |# with an old key but claiming a fresh kid is exactly the mismatch that
       |# makes central reject edge's sync calls. Resolving both through
       |# OpenBao together keeps them the same pair on every run, not just the
       |# first.
       |key-id = ${secretField(useOpenBao, edgeKey.kid, "EDGE_KEY_ID")}
       |private-key = ${secretKeyField(useOpenBao, edgeKey.privateB64, "EDGE_PRIVATE_KEY")}
       |
       |security {
       |  token-encryption {
       |    key = ${secretField(useOpenBao, edgeTokenEncKey, "EDGE_TOKEN_ENC_KEY")}
       |  }
       |
       |  edge-sessions {
       |    secret = ${secretField(useOpenBao, edgeSessionsSecret, "EDGE_SESSIONS_SECRET")}
       |    ttl = 30 days
       |  }
       |
       |  # Authorizes POST /service/configuration/sync (non-prod only) -- see
       |  # EdgeConfig.Security.internalSecret.
       |  internal-secret = ${secretField(useOpenBao, edgeInternalSecretValue, "EDGE_INTERNAL_SECRET")}
       |}
       |
       |postgres {
       |  url = "$edgePgUrl"
       |  user = "$edgePgUser"
       |  password = ${secretField((isVps || isKubernetes), edgePgPass, "POSTGRES_PASSWORD")}
       |  maximum-pool-size = 10
       |  minimum-idle = 10
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "60 seconds"
       |  # Absent unless POSTGRES_POOL_METRICS_INTERVAL is set, which leaves HikariCP
       |  # without a MetricsTrackerFactory and this pool publishing nothing -- the state
       |  # this service has always run in. Set it (e.g. "10 seconds") to put the pool's
       |  # occupancy, acquisition wait and timeouts on the db_client_connection_* series
       |  # k8s/loadgen/dashboards/db-pools.json reads.
       |  pool-metrics-interval = $${?POSTGRES_POOL_METRICS_INTERVAL}
       |}
       |
       |cleanup {
       |  max-threads = 2
       |  tables = [
       |    {
       |      table-name = "pending_logins"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |      key-column = "state"
       |    }
       |    {
       |      table-name = "edge_sessions"
       |      batch-size = 500
       |      interval   = "1 hour"
       |      key-column = "ctid"
       |    }
       |    {
       |      table-name = "revocations"
       |      batch-size = 1000
       |      interval   = "1 hour"
       |      key-column = "revoked_key"
       |    }
       |  ]
       |}
       |
       |# RFC 9449 proof validation on proxied calls, against edge-url below. Only
       |# what is a fact about this deployment lives here: the salt keying this
       |# edge's own nonce space, and the two windows. What a proof may be signed
       |# with (§5.1) is read off the metadata document central holds, so this edge
       |# and auth cannot disagree about it; whether a nonce is required (§9) is
       |# per edge in central -- change it in the console, not by redeploying.
       |# Remove this block entirely to turn DPoP off; a key-bound token is still
       |# refused over Bearer the same way.
       |dpop {
       |  nonce-salt = ${secretField(useOpenBao, edgeDpopNonceSalt, "EDGE_DPOP_NONCE_SALT")}
       |  iat-leeway = "60 seconds"
       |  nonce-ttl = "600 seconds"
       |}
       |
       |central {
       |  url = "$centralUrl"
       |}
       |
       |versola-url = "$authUrl"
       |versola-internal-url = "$edgeInternalUrl"
       |${edgeInternalTrustLine}# The origin clients reach this edge on -- what a DPoP proof's htu is
       |# rebuilt against (DpopVerifier), not trusting a forwarded Host header.
       |edge-url = "$edgeUrl"
       |$edgeNativeBlock""".stripMargin

  // ── Write files ───────────────────────────────────────────────────────────────
  println("\nGenerating config files...")
  if isLocal then
    writeFile(File("auth/dev"),     "env.conf", authConf)
    writeFile(File("central/dev"),  "env.conf", centralConf)
    writeFile(File("edge/dev"),     "env.conf", edgeConf)
    // The CA and server certificate were generated with central-admin's certificate, above.
    writeFile(edgeInternalTlsDir, "nginx.conf", internalTlsNginxConf(edgeInternalTlsDir, edgeInternalTlsPort, authInternalUrl))
    genAuthMutualTlsCertificate(authMutualTlsDir)
    writeFile(
      authMutualTlsDir,
      authMutualTlsTrustedClients.getName,
      Seq(File(authMutualTlsDir, "ca.crt"), File(edgeInternalTlsDir, "ca.crt"))
        .map(file => scala.io.Source.fromFile(file).mkString.trim)
        .mkString("", "\n", "\n"),
    )
    println(
      s"""
         |Done! Files written to service dev directories:
         |  - auth/dev/env.conf
         |  - central/dev/env.conf
         |  - edge/dev/env.conf
         |  - edge/dev/internal-tls/{ca.*,server.*,central-admin.*,nginx.conf} (the TLS terminator
         |    edge's RFC 8705 mutual-TLS calls go through -- start with
         |    `nginx -c $$(pwd)/edge/dev/internal-tls/nginx.conf` before edge)
         |  - auth/dev/mtls/{ca.*,server.*,client.*,trusted-clients.crt} (auth's own RFC 8705 §5 listener --
         |    no terminator to start, auth serves it itself on MPORT=$authMutualTlsPort;
         |    client.crt/client.key are the certificate e2e's MutualTlsListenerSpec presents)
         |""".stripMargin,
    )
  else
    // Keyed by target, not env: entrypoint.sh looks these files up at
    // .local/env/"$TARGET"/... (docker-local or vps), and has no way to
    // know what env value this run happened to resolve to (ENV_NAME,
    // for vps, isn't necessarily "prod" -- see env's own comment above).
    val dir = File(s".local/env/$target")
    writeFile(dir, "auth.conf",    authConf)
    writeFile(dir, "central.conf", centralConf)
    writeFile(dir, "edge.conf",    edgeConf)

    // versola-cli resolves each of these against OpenBao (existing value
    // wins; a first-time value gets stored there) before starting any
    // container -- see the comment on secretField above. auth.conf and
    // central.conf both reference CENTRAL_SECRET_KEY/CLIENT_SECRETS_SECRET,
    // so both files carry them; versola-cli only needs to resolve each
    // shared value once; writing it into both is harmless.
    if useOpenBao then
      // vps and k8s additionally placeholder out Postgres's password and
      // the admin bootstrap password -- see pgPassDefault and
      // bootstrapPasswordDefault above for why those two, specifically,
      // aren't part of the docker-local set. Postgres's *user* isn't
      // here: "versola_app" (or whatever a k8s deployment typed at its own
      // prompt) isn't secret, so it stays a literal value in the .conf
      // files instead (see pgUserDefault).
      val authExtras = if isVps || isKubernetes then Seq(
        "POSTGRES_PASSWORD"        -> authPgPass,
        "ADMIN_BOOTSTRAP_PASSWORD" -> bootstrapPassword,
      ) else Seq.empty
      val secretTarget = SecretSchema.parseTarget(target).getOrElse(
        throw RuntimeException(s"no secret schema for target '$target' (placeholders are only written for docker-local, vps and k8s)"),
      )
      // A schema that contradicts itself must stop the run before any secrets file is written.
      SecretSchema.problems(SecretSchema.specs) match
        case Nil => ()
        case found => throw RuntimeException("secret schema is inconsistent: " + found.mkString("; "))
      writeGeneratedSecrets(dir, secretTarget, "auth", Seq(
        "ACCESS_TOKENS_SECRET"       -> accessTokensSecret,
        "CLIENT_SECRETS_SECRET"      -> clientSecretsSecret,
        "REFRESH_TOKENS_SECRET"      -> refreshTokensSecret,
        "AUTH_CODES_SECRET"          -> authCodesSecret,
        "SESSIONS_SECRET"            -> sessionsSecret,
        "PASSWORDS_SECRET"           -> passwordsSecret,
        "CONVERSATION_COOKIE_SECRET" -> conversationCookieSecret,
        "SESSION_COOKIE_SECRET"      -> sessionCookieSecret,
        "USER_AGENT_COOKIE_SECRET"   -> userAgentCookieSecret,
        "PAR_REQUESTS_SECRET"        -> parRequestsSecret,
        "DPOP_NONCES_SECRET"         -> dpopNoncesSecret,
        "JWT_PRIVATE_KEY"            -> jwtKey.privateB64,
        "CENTRAL_SECRET_KEY"         -> centralSecretKey,
      ) ++ authExtras)

      val centralExtras = if isVps || isKubernetes then Seq("POSTGRES_PASSWORD" -> centralPgPass) else Seq.empty
      writeGeneratedSecrets(dir, secretTarget, "central", Seq(
        "CENTRAL_SECRET_KEY"    -> centralSecretKey,
        "CLIENT_SECRETS_SECRET" -> clientSecretsSecret,
        "ACCOUNT_RESOURCE_SECRET" -> accountResourceSecret,
        // Reached only when useOpenBao is true (isLocal, the only other target that ever
        // sets these, has its own pinned literals and never runs this far -- see
        // bootstrapResourceSecretLine/bootstrapUtilityClientLines above), so the placeholders
        // these var names back always resolve to exactly the generated value here.
        "CENTRAL_RESOURCE_SECRET" -> centralResourceSecretGenerated,
        // The public half only -- see bootstrapUtilityClientLines for where the private
        // half goes instead.
        "UTILITY_CLIENT_PUBLIC_JWK" -> utilityPublicJwk,
        // Not secret in the confidentiality sense (these are public keys),
        // but resolved through OpenBao the same as everything else here
        // regardless -- see the comment on jwks/public-key-jwk above for
        // why they have to travel with JWT_PRIVATE_KEY/EDGE_PRIVATE_KEY's
        // resolution rather than being written fresh every run.
        "JWKS_JSON"        -> jwks,
        "EDGE_PUBLIC_JWK"  -> edgeKey.jwk,
      ) ++ centralExtras)

      val edgeExtras = if isVps || isKubernetes then Seq("POSTGRES_PASSWORD" -> edgePgPass) else Seq.empty
      writeGeneratedSecrets(dir, secretTarget, "edge", Seq(
        "EDGE_PRIVATE_KEY"     -> edgeKey.privateB64,
        // Travels with EDGE_PRIVATE_KEY, not written separately -- see the
        // comment on edgeConf's key-id line for why a bare literal here
        // would drift out of sync with whichever private key actually ends
        // up resolved.
        "EDGE_KEY_ID"          -> edgeKey.kid,
        "EDGE_TOKEN_ENC_KEY"   -> edgeTokenEncKey,
        "EDGE_SESSIONS_SECRET" -> edgeSessionsSecret,
        "EDGE_INTERNAL_SECRET" -> edgeInternalSecret,
        "EDGE_DPOP_NONCE_SALT" -> edgeDpopNonceSalt,
        // native.blob-key: a secret like the rest, so it is placeholdered and resolved through
        // the secret store instead of being regenerated into edge.conf on every run (a blob
        // sealed under one run's key could not be opened after the next).
        "EDGE_NATIVE_BLOB_KEY" -> edgeNativeBlobKey,
      ) ++ edgeExtras)

      // What the keys above are, for versola-cli: see SecretSchema. Values are never in it.
      writeFile(dir, "secrets.schema.json", SecretSchema.toJson(secretTarget))

    // The private half of the `utils` pair, for whoever authenticates as that client (loadgen provision today) -- see bootstrapUtilityClientLines. A bare JWK file,
    // owner-readable only, written outside every *.generated-secrets.env on purpose. Written
    // whether or not secrets are placeholdered: an interactive prod run registers the public half
    // in central.conf as a literal, and nothing else keeps the private half.
    writeFile(dir, "utils.private-key.jwk", utilityPrivateJwk + "\n")
    java.nio.file.Files.setPosixFilePermissions(
      File(dir, "utils.private-key.jwk").toPath,
      java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
    )
    println("  Keep utils.private-key.jwk: it is the private key of the `utils` client (loadgen's provision.provisioner-private-key), and is in no generated-secrets file.")

    println(
      s"""
         |Done! Files written to .local/env/$target/
         |  - auth.conf     (auth service)
         |  - central.conf  (central service)
         |  - edge.conf     (edge service)
         |""".stripMargin,
    )
