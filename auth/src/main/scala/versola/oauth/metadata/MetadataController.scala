package versola.oauth.metadata

import versola.oauth.client.OAuthConfigurationService
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*

object MetadataController extends Controller:
  type Env = OAuthConfigurationService

  def routes: Routes[Env, Throwable] = Routes(
    oauthMetadataEndpoint,
    oidcMetadataEndpoint,
  )

  private val oauthMetadataEndpoint =
    Method.GET / ".well-known" / "oauth-authorization-server" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthConfigurationService]
        metadata <- service.getMetadata
      yield Response.json(metadata.toJson)
    }

  private val oidcMetadataEndpoint =
    Method.GET / ".well-known" / "openid-configuration" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthConfigurationService]
        metadata <- service.getMetadata
      yield Response.json(metadata.toJson)
    }
