#!/usr/bin/env -S scala-cli shebang
//> using scala 3.6.3
//> using jvm 21

import java.io.{File, PrintWriter}
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

def prompt(msg: String, default: String = ""): String =
  if !interactive then return default
  print(msg)
  val line = scala.io.StdIn.readLine()
  if line == null || line.trim.isEmpty then default else line.trim

def promptYN(msg: String, defaultYes: Boolean = false): Boolean =
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

@main def genEnv(): Unit =
  val rng = SecureRandom()

  // ── Key pairs ─────────────────────────────────────────────────────────────────
  println("Generating RSA-2048 key pairs...")

  case class RsaKey(privateB64: String, jwk: String, kid: String)

  def genRsaKey(kid: String): RsaKey =
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048, rng)
    val kp      = kpg.generateKeyPair()
    val privKey = kp.getPrivate.asInstanceOf[RSAPrivateCrtKey]
    val pubKey  = kp.getPublic.asInstanceOf[RSAPublicKey]
    val n       = b64url(pubKey.getModulus)
    val e       = b64url(pubKey.getPublicExponent)
    val jwk     = s"""{"kty":"RSA","e":"$e","use":"sig","kid":"$kid","alg":"RS256","n":"$n"}"""
    RsaKey(b64std(privKey.getEncoded), jwk, kid)

  val today = java.time.LocalDate.now.toString
  // JWT signing key: auth signs access tokens with the private half; central serves
  // the public half in the JWKS so auth/edge/admin-console can verify those tokens.
  val jwtKey = genRsaKey(s"jwt-$today")
  // Edge key: central encrypts each edge's client secrets with the public half and
  // verifies the edge's sync tokens against it; the edge signs/decrypts with the private half.
  val edgeKey = genRsaKey(s"edge-$today")
  val jwks    = s"""{"keys":[${jwtKey.jwk}]}"""

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
  val edgeTokenEncKey           = rand(rng, 32)
  val edgeSessionsSecret        = rand(rng, 32)

  // ── Environment ───────────────────────────────────────────────────────────────
  println("\n── Environment ───────────────────────────────────────────────────────")
  val env     = prompt("  Name [local]: ", "local")
  val isLocal = env == "local"
  // Local env is non-interactive: skip all remaining prompts and use defaults.
  interactive = !isLocal
  if isLocal then println("  local env — using defaults, skipping prompts")

  // ── Service URLs ──────────────────────────────────────────────────────────────
  // Each service's public base URL, prompted once and reused wherever another
  // service needs to reach it (auth is also the JWT issuer and edge's upstream).
  section("\n── Service URLs ──────────────────────────────────────────────────────")
  val authUrl    = prompt("  Auth URL [http://localhost:9003]: ",    "http://localhost:9003")
  val centralUrl = prompt("  Central URL [http://localhost:9001]: ", "http://localhost:9001")
  val edgeUrl    = prompt("  Edge URL [http://localhost:9005]: ",    "http://localhost:9005")

  section("\n── Auth service ──────────────────────────────────────────────────────")
  val authPgUrl       = prompt("  Postgres URL [jdbc:postgresql://localhost:5432/auth]: ", "jdbc:postgresql://localhost:5432/auth")
  val authPgUser      = prompt("  Postgres user [dev]: ",                             "dev")
  val authPgPass      = prompt("  Postgres password [1234]: ",                        "1234")

  section("\n── Auth bootstrap admin user ──────────────────────────────────────────────")
  val bootstrapLogin    = prompt("  Admin login [admin]: ", "admin")
  val bootstrapPassword = prompt("  Admin bootstrap password [Admin1234!]: ", "Admin1234!")

  section("\n── Central service ───────────────────────────────────────────────────")
  val centralRedirectUris = prompt("  Admin panel bootstrap redirect URIs (comma-separated) [http://localhost:3000]: ", "http://localhost:3000")
  val centralPgUrl        = prompt("  Postgres URL [jdbc:postgresql://localhost:5432/auth]: ", "jdbc:postgresql://localhost:5432/auth")
  val centralPgUser       = prompt("  Postgres user [dev]: ",                         "dev")
  val centralPgPass       = prompt("  Postgres password [1234]: ",                    "1234")

  section("\n── Edge service ──────────────────────────────────────────────────────")
  val edgePgUrl       = prompt("  Postgres URL [jdbc:postgresql://localhost:5432/auth]: ", "jdbc:postgresql://localhost:5432/auth")
  val edgePgUser      = prompt("  Postgres user [dev]: ",                             "dev")
  val edgePgPass      = prompt("  Postgres password [1234]: ",                        "1234")

  // Edge complete URL is always added as a registered redirect URI so the preset can use it.
  val edgeCompleteUrl        = s"$edgeUrl/complete"
  val centralRedirectUriList =
    (centralRedirectUris.split(",").map(_.trim) :+ edgeCompleteUrl)
      .distinct
      .map(u => s""""$u"""")
      .mkString(", ")
  val postLoginRedirectUri   = centralRedirectUris.split(",").map(_.trim).head

  // ── OTP provider ──────────────────────────────────────────────────────────────
  section("\n── OTP Provider ──────────────────────────────────────────────────────")
  val wantsOtp = promptYN("Configure OTP provider?")
  val otpBlock =
    if wantsOtp then
      val url    = prompt("  OTP provider URL: ", "http://localhost:9100/sms")
      val method = prompt("  HTTP method [POST]: ", "POST")
      val uname  = prompt("  Username (empty = none): ")
      val pass   = prompt("  Password (empty = none): ")
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
  val wantsSmtp = promptYN("Configure SMTP?")
  val smtpBlock =
    if wantsSmtp then
      val host    = prompt("  Host: ", "localhost")
      val portStr = prompt("  Port [587]: ", "587")
      val port    = portStr.toIntOption.getOrElse(587)
      val uname   = prompt("  Username: ", "dev")
      val pass    = prompt("  Password: ", "dev")
      val from    = prompt("  From email [noreply@example.com]: ", "noreply@example.com")
      val subj    = prompt("  Subject [Your verification code]: ", "Your verification code")
      val tls     = promptYN("  Use STARTTLS?", defaultYes = true)
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
       |# otel-exporter = "http://localhost:4317"
       |
       |bootstrap {
       |  login = "$bootstrapLogin"
       |  password = "$bootstrapPassword"
       |  admin-user-id = "$adminUserId"
       |}
       |
       |security {
       |  access-tokens-secret         = "$accessTokensSecret"
       |  client-secrets-secret        = "$clientSecretsSecret"
       |  refresh-tokens-secret        = "$refreshTokensSecret"
       |  auth-codes-secret            = "$authCodesSecret"
       |  sessions-secret              = "$sessionsSecret"
       |  passwords-secret             = "$passwordsSecret"
       |  conversation-cookie-secret   = "$conversationCookieSecret"
       |}
       |
       |jwt {
       |  issuer = "$authUrl"
       |  private-key = \"\"\"${jwtKey.privateB64}\"\"\"
       |}
       |
       |central {
       |  url = "$centralUrl"
       |  secret-key = "$centralSecretKey"
       |}
       |$otpBlock$smtpBlock
       |postgres {
       |  url = "$authPgUrl"
       |  user = "$authPgUser"
       |  password = "$authPgPass"
       |  maximum-pool-size = 10
       |  minimum-idle = 10
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "60 seconds"
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
       |  ]
       |}
       |""".stripMargin

  val centralConf =
    s"""env = $env
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
       |      public-key-jwk = \"\"\"${edgeKey.jwk}\"\"\"
       |    }
       |  ]
       |  # Matches the JWT signing key in auth (jwt.private-key).
       |  jwks = \"\"\"$jwks\"\"\"
       |  presets = [
       |    {
       |      id = "central-admin"
       |      description = "Central Admin Login"
       |      redirect-uri = "$edgeCompleteUrl"
       |      post-login-redirect-uri = "$postLoginRedirectUri"
       |    }
       |  ]
       |  central-url = "$centralUrl"
       |}
       |
       |secret-key = "$centralSecretKey"
       |client-secrets-secret = "$clientSecretsSecret"
       |
       |auth {
       |  url = "$authUrl"
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
       |  password = "$centralPgPass"
       |  maximum-pool-size = 15
       |  minimum-idle = 15
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "0 seconds"
       |}
       |""".stripMargin

  val edgeConf =
    s"""env = $env
       |
       |# otel-exporter = "http://localhost:4317"
       |
       |id = "edge-default"
       |
       |key-id = "${edgeKey.kid}"
       |private-key = \"\"\"${edgeKey.privateB64}\"\"\"
       |
       |security {
       |  token-encryption {
       |    key = "$edgeTokenEncKey"
       |  }
       |
       |  edge-sessions {
       |    secret = "$edgeSessionsSecret"
       |    ttl = 30 days
       |  }
       |}
       |
       |postgres {
       |  url = "$edgePgUrl"
       |  user = "$edgePgUser"
       |  password = "$edgePgPass"
       |  maximum-pool-size = 10
       |  minimum-idle = 10
       |  connection-timeout = "30 seconds"
       |  max-lifetime = "30 minutes"
       |  leak-detection-threshold = "60 seconds"
       |}
       |
       |cleanup {
       |  max-threads = 2
       |  tables = [
       |    {
       |      table-name = "pending_logins"
       |      batch-size = 1000
       |      interval   = "5 minutes"
       |      key-column = "login_id"
       |    }
       |    {
       |      table-name = "edge_refresh_tokens"
       |      batch-size = 500
       |      interval   = "1 hour"
       |    }
       |  ]
       |}
       |
       |central {
       |  url = "$centralUrl"
       |}
       |
       |versola-url = "$authUrl"
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
    val dir = File(s".local/env/$env")
    writeFile(dir, "auth.conf",    authConf)
    writeFile(dir, "central.conf", centralConf)
    writeFile(dir, "edge.conf",    edgeConf)
    println(
      s"""
         |Done! Files written to .local/env/$env/
         |  - auth.conf     (auth service)
         |  - central.conf  (central service)
         |  - edge.conf     (edge service)
         |""".stripMargin,
    )
