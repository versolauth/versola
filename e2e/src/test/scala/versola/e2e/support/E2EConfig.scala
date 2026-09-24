package versola.e2e.support

import zio.*

/** Runtime configuration read from environment variables.
  * Defaults match the local dev setup described in develop.md.
  */
final case class E2EConfig(
    authUrl: String,
    /** Auth's additional listener (`APORT`), which serves the Account Settings resource. */
    authAdditionalUrl: String,
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
    provisionerSecret: String,
    redirectUri: String,
)

object E2EConfig:

  val live: ULayer[E2EConfig] = ZLayer.fromZIO(load.orDie)

  private val load: Task[E2EConfig] =
    for
      authUrl       <- env("AUTH_URL",        "http://localhost:9003")
      authAdditionalUrl <- env("AUTH_ADDITIONAL_URL", "http://localhost:9007")
      centralUrl    <- env("CENTRAL_URL",     "http://localhost:9001")
      edgeUrl       <- env("EDGE_URL",        "http://localhost:9005")
      adminLogin       <- env("E2E_LOGIN",          "admin")
      adminPassword    <- env("E2E_PASSWORD",       "Admin1234!")
      adminNewPassword <- env("E2E_NEW_PASSWORD",   "Admin5678!")
      clientId         <- env("E2E_CLIENT_ID",      "central-admin")
      // Default matches the pinned local central resource secret.
      resourceSecret   <- env("E2E_RESOURCE_SECRET", "ZGV2LWNlbnRyYWwtYWRtaW4tc2VjcmV0LTMyYnl0ZXM")
      // Default matches the pinned local auth account-resource secret.
      accountResourceSecret <- env("E2E_ACCOUNT_RESOURCE_SECRET", "ZGV2LWF1dGgtYWNjb3VudC1zZWNyZXQtMzJieXRlcyE")
      // Default matches the pinned local edge internal secret (see gen-env.scala).
      edgeInternalSecret <- env("E2E_EDGE_INTERNAL_SECRET", "ZGV2LWVkZ2UtaW50ZXJuYWwtc2VjcmV0LTMyYnl0ZSE")
      // Defaults match the pinned local provisioner seed (see gen-env.scala).
      provisionerClientId <- env("E2E_PROVISIONER_CLIENT_ID", "loadgen-provisioner")
      provisionerSecret <- env("E2E_PROVISIONER_SECRET", "ZGV2LWxvYWRnZW4tcHJvdmlzaW9uZXItc2VjcmV0MzI")
      redirectUri      <- env("E2E_REDIRECT_URI",   "http://localhost:3000")
    yield E2EConfig(
      authUrl,
      authAdditionalUrl,
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
      provisionerSecret,
      redirectUri,
    )

  private def env(name: String, default: String): Task[String] =
    System.env(name).map(_.getOrElse(default))
