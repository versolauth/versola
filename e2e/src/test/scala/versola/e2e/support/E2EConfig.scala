package versola.e2e.support

import zio.*

/** Runtime configuration read from environment variables.
  * Defaults match the local dev setup described in develop.md.
  */
final case class E2EConfig(
    authUrl: String,
    /** Auth's additional listener (`APORT`), which serves the Account Settings resource. */
    authAdditionalUrl: String,
    /** Auth's diagnostics listener (`DPORT`), which serves `/metrics`, `/liveness` and
      * `/readiness` -- separate from application traffic, and never publicly exposed. */
    authDiagnosticsUrl: String,
    centralUrl: String,
    /** Edge's public listener, which proxies the Account Settings resource back to auth. */
    edgeUrl: String,
    adminLogin: String,
    adminPassword: String,
    adminNewPassword: String,
    clientId: String,
    resourceSecret: String,
    /** Basic credential edge uses against auth's Account Settings resource. */
    accountResourceSecret: String,
    /** Basic credential authorizing edge's non-prod /service/configuration/sync endpoint. */
    edgeInternalSecret: String,
    /** The `client_credentials` client central seeds for `loadgen provision`, which reaches
      * central's admin API through edge's proxy rather than with the resource secret. */
    provisionerClientId: String,
    /** The private half, as a JWK, of the key central registered for it as `private_key_jwt`
      * (`bootstrap.utility-client.public-key-jwk`) -- see [[ProvisionerCredential]]. */
    provisionerPrivateKey: String,
    redirectUri: String,
    /** RFC 8705 §5: `auth`'s own mutual-TLS listener (`MPORT`), terminating TLS itself rather
      * than reading a header a proxy forwarded -- the header path is `Fixtures.ClientCertificates`
      * and `MutualTlsSpec`; this is the separate, unrelated listener `MutualTlsListenerSpec`
      * reaches directly. Defaults match `scripts/gen-env.scala`'s `local` target. */
    authMutualTlsUrl: String,
    /** PEM paths `scripts/gen-env.scala` writes alongside the listener's own certificate --
      * see its `genAuthMutualTlsCertificate`. All three signed by the same CA, so the client
      * certificate chains to the one anchor the listener trusts. */
    authMutualTlsClientCertificate: String,
    authMutualTlsClientKey: String,
    authMutualTlsTrustedCertificates: String,
    /** The staged central launcher and the `env.conf` the running one was started with, for the
      * specs that start a second central to see what its bootstrap does. */
    centralLauncher: String,
    centralEnvConf: String,
)

object E2EConfig:

  val live: ULayer[E2EConfig] = ZLayer.fromZIO(load.orDie)

  private val load: Task[E2EConfig] =
    for
      authUrl <- env("AUTH_URL", "http://localhost:9003")
      authAdditionalUrl <- env("AUTH_ADDITIONAL_URL", "http://localhost:9007")
      authDiagnosticsUrl <- env("AUTH_DIAGNOSTICS_URL", "http://localhost:9004")
      centralUrl <- env("CENTRAL_URL", "http://localhost:9001")
      edgeUrl <- env("EDGE_URL", "http://localhost:9005")
      adminLogin <- env("E2E_LOGIN", "admin")
      adminPassword <- env("E2E_PASSWORD", "Admin1234!")
      adminNewPassword <- env("E2E_NEW_PASSWORD", "Admin5678!")
      clientId <- env("E2E_CLIENT_ID", "central-admin")
      // Default matches the pinned local central resource secret.
      resourceSecret <- env("E2E_RESOURCE_SECRET", "ZGV2LWNlbnRyYWwtYWRtaW4tc2VjcmV0LTMyYnl0ZXM")
      // Default matches the pinned local auth account-resource secret.
      accountResourceSecret <- env("E2E_ACCOUNT_RESOURCE_SECRET", "ZGV2LWF1dGgtYWNjb3VudC1zZWNyZXQtMzJieXRlcyE")
      // Default matches the pinned local edge internal secret (see gen-env.scala).
      edgeInternalSecret <- env("E2E_EDGE_INTERNAL_SECRET", "ZGV2LWVkZ2UtaW50ZXJuYWwtc2VjcmV0LTMyYnl0ZSE")
      // Defaults match the pinned local provisioner seed (see gen-env.scala).
      provisionerClientId <- env("E2E_PROVISIONER_CLIENT_ID", "utils")
      provisionerPrivateKey <- env(
        "E2E_PROVISIONER_PRIVATE_KEY",
        """{"kty":"EC","crv":"P-256","x":"Rst-brXjn7AQChQkaCwR6Vf5-nlVw4SDw-swh8g3GdU","y":"gD6MZlaRGOf1MColB6GhG5N3TdvJGsiF1J7_jYNAgfo","d":"jWGh5lV46NJ3RwT8kJ5lfBeBTGBtXnM5V3gwgAEYpXM","use":"sig","kid":"utils-local","alg":"ES256"}""",
      )
      redirectUri <- env("E2E_REDIRECT_URI", "http://localhost:3000")
      authMutualTlsUrl <- env("AUTH_MTLS_URL", "https://localhost:9008")
      // Relative to this module's own directory, not the repo root: `Test / fork := true`
      // (see build.sbt) runs specs in a JVM whose working directory is `e2e/`, not wherever
      // `sbt` itself was launched from.
      authMutualTlsClientCertificate <- env("AUTH_MTLS_CLIENT_CERT", "../auth/dev/mtls/client.crt")
      authMutualTlsClientKey <- env("AUTH_MTLS_CLIENT_KEY", "../auth/dev/mtls/client.key")
      authMutualTlsTrustedCertificates <- env("AUTH_MTLS_CA", "../auth/dev/mtls/ca.crt")
      centralLauncher <- env("CENTRAL_LAUNCHER", "../central/implementations/postgres/target/universal/stage/bin/central-postgres-impl")
      centralEnvConf <- env("CENTRAL_ENV_CONF", "../central/dev/env.conf")
    yield E2EConfig(
      authUrl,
      authAdditionalUrl,
      authDiagnosticsUrl,
      centralUrl,
      edgeUrl,
      adminLogin,
      adminPassword,
      adminNewPassword,
      clientId,
      resourceSecret,
      accountResourceSecret,
      edgeInternalSecret,
      provisionerClientId,
      provisionerPrivateKey,
      redirectUri,
      authMutualTlsUrl,
      authMutualTlsClientCertificate,
      authMutualTlsClientKey,
      authMutualTlsTrustedCertificates,
      centralLauncher,
      centralEnvConf,
    )

  private def env(name: String, default: String): Task[String] =
    System.env(name).map(_.getOrElse(default))
