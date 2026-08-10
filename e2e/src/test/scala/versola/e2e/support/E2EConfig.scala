package versola.e2e.support

import zio.*

/** Runtime configuration read from environment variables.
  * Defaults match the local dev setup described in develop.md.
  */
final case class E2EConfig(
    authUrl: String,
    centralUrl: String,
    adminLogin: String,
    adminPassword: String,
    adminNewPassword: String,
    clientId: String,
    resourceSecret: String,
    redirectUri: String,
)

object E2EConfig:

  val live: ULayer[E2EConfig] = ZLayer.fromZIO(load.orDie)

  private val load: Task[E2EConfig] =
    for
      authUrl       <- env("AUTH_URL",        "http://localhost:9003")
      centralUrl    <- env("CENTRAL_URL",     "http://localhost:9001")
      adminLogin       <- env("E2E_LOGIN",          "admin")
      adminPassword    <- env("E2E_PASSWORD",       "Admin1234!")
      adminNewPassword <- env("E2E_NEW_PASSWORD",   "Admin5678!")
      clientId         <- env("E2E_CLIENT_ID",      "central-admin")
      // Default matches the pinned local central resource secret.
      resourceSecret   <- env("E2E_RESOURCE_SECRET", "ZGV2LWNlbnRyYWwtYWRtaW4tc2VjcmV0LTMyYnl0ZXM")
      redirectUri      <- env("E2E_REDIRECT_URI",   "http://localhost:3000")
    yield E2EConfig(authUrl, centralUrl, adminLogin, adminPassword, adminNewPassword, clientId, resourceSecret, redirectUri)

  private def env(name: String, default: String): Task[String] =
    System.env(name).map(_.getOrElse(default))
