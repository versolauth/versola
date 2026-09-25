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
def writeGeneratedSecrets(dir: File, name: String, secrets: Seq[(String, String)]): Unit =
  val content = secrets.map((k, v) => s"$k=$v").mkString("\n") + "\n"
  writeFile(dir, name, content)

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

  case class EcKey(privateB64: String, jwk: String, kid: String)

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
    EcKey(b64std(privKey.getEncoded), jwk, kid)

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
  val centralSecretKey          = rand(rng, 32) // shared: auth↔central & edge↔central
  val clientSecretsSecret       = rand(rng, 16) // shared: auth + central (client MAC)
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
  val accountResourceSecretGenerated = rand(rng, 32) // central: seeds the "auth" resource record; auth fetches it decrypted via registry sync
  val centralResourceSecretGenerated = rand(rng, 32) // central: seeds its own "central" resource record; edge fetches it to proxy admin calls (auth.scala's authorizeBasic)
  val utilityClientSecretGenerated   = rand(rng, 32) // central: seeds bootstrap.utility-client; must match whatever configures loadgen's own provision.provisioner-secret

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
  // occasional bootstrap (there is no CI pipeline driving it, unlike vps's
  // Deploy workflow), and the campaign topology genuinely needs a human's
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
  // over this freshly generated one. The first time this ever runs against
  // a fresh OpenBao, it WILL store this random value and it WILL be wrong:
  // the VPS's `versola_app` Postgres role already has a real password this
  // script has no way to know. That first run needs the real password
  // seeded into OpenBao by hand first (see develop.md's OpenBao section).
  // After that, every run reuses it.
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
  // empty, regardless of target -- BootstrapService only fills either in ONCE, the first
  // time the resource/client doesn't exist yet, so a value that isn't here at first boot
  // has no later config-only recovery (versolauth/versola#380). Pinned in local dev so
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
  // config (provision.provisioner-client-id) to name the same string, not to keep it out
  // of sight. The secret is what a deployment that runs a campaign also has to give
  // loadgen, out of band, the same way Postgres's password is reused rather than
  // reconciled. Several lines rather than one, so unlike the line above it supplies its
  // own newlines and nothing follows it on a line.
  val bootstrapUtilityClientLines =
    "  utility-client {\n" +
      "    client-id = \"utils\"\n" +
      (if isLocal then "    secret = \"ZGV2LWxvYWRnZW4tcHJvdmlzaW9uZXItc2VjcmV0MzI\"\n"
       else s"    secret = ${secretField(useOpenBao, utilityClientSecretGenerated, "UTILITY_CLIENT_SECRET")}\n") +
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
  val authAdditionalDefault =
    if isDockerLocal then "http://auth:8082"
    else if isVps then "http://127.0.0.1:8082"
    else if isLocal then "http://localhost:9007"
    else "http://localhost:8082"
  val authAdditionalUrl = prompt(s"  Auth additional URL [$authAdditionalDefault]: ", authAdditionalDefault, flag = "auth-additional-url")
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
       |
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

  val centralConf =
    s"""env = $env
       |
       |configuration-cache-refresh-interval = "$configurationCacheRefreshInterval"
       |
       |# otel-exporter = "http://localhost:4317"
       |
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
       |${bootstrapUtilityClientLines}${bootstrapResourceSecretLine}|}
       |
       |secret-key = ${secretField(useOpenBao, centralSecretKey, "CENTRAL_SECRET_KEY")}
       |client-secrets-secret = ${secretField(useOpenBao, clientSecretsSecret, "CLIENT_SECRETS_SECRET")}
       |
       |auth {
       |  url = "$authInternalUrl"
       |}
       |
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
       |versola-internal-url = "$authInternalUrl"
       |# The origin clients reach this edge on -- what a DPoP proof's htu is
       |# rebuilt against (DpopVerifier), not trusting a forwarded Host header.
       |edge-url = "$edgeUrl"
       |""".stripMargin

  // ── Write files ───────────────────────────────────────────────────────────────
  println("\nGenerating config files...")
  if isLocal then
    writeFile(File("auth/dev"),     "env.conf", authConf)
    writeFile(File("central/dev"),  "env.conf", centralConf)
    writeFile(File("edge/dev"),     "env.conf", edgeConf)
    println(
      s"""
         |Done! Files written to service dev directories:
         |  - auth/dev/env.conf
         |  - central/dev/env.conf
         |  - edge/dev/env.conf
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
      writeGeneratedSecrets(dir, "auth.generated-secrets.env", Seq(
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
      writeGeneratedSecrets(dir, "central.generated-secrets.env", Seq(
        "CENTRAL_SECRET_KEY"    -> centralSecretKey,
        "CLIENT_SECRETS_SECRET" -> clientSecretsSecret,
        "ACCOUNT_RESOURCE_SECRET" -> accountResourceSecret,
        // Reached only when useOpenBao is true (isLocal, the only other target that ever
        // sets these two, has its own pinned literals and never runs this far -- see
        // bootstrapResourceSecretLine/bootstrapUtilityClientLines above), so the placeholder
        // these two var names back always resolves to exactly the generated value here.
        "CENTRAL_RESOURCE_SECRET" -> centralResourceSecretGenerated,
        "UTILITY_CLIENT_SECRET"   -> utilityClientSecretGenerated,
        // Not secret in the confidentiality sense (these are public keys),
        // but resolved through OpenBao the same as everything else here
        // regardless -- see the comment on jwks/public-key-jwk above for
        // why they have to travel with JWT_PRIVATE_KEY/EDGE_PRIVATE_KEY's
        // resolution rather than being written fresh every run.
        "JWKS_JSON"        -> jwks,
        "EDGE_PUBLIC_JWK"  -> edgeKey.jwk,
      ) ++ centralExtras)

      val edgeExtras = if isVps || isKubernetes then Seq("POSTGRES_PASSWORD" -> edgePgPass) else Seq.empty
      writeGeneratedSecrets(dir, "edge.generated-secrets.env", Seq(
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
      ) ++ edgeExtras)

    println(
      s"""
         |Done! Files written to .local/env/$target/
         |  - auth.conf     (auth service)
         |  - central.conf  (central service)
         |  - edge.conf     (edge service)
         |""".stripMargin,
    )
