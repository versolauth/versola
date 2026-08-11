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

  private val oauthMetadataEndpoint = metadata("oauth-authorization-server")
  private val oidcMetadataEndpoint = metadata("openid-configuration")

  private def metadata(name: String) =
    Method.GET / ".well-known" / name -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthConfigurationService]
        metadata <- service.getMetadata
      yield Response.json(metadata.toJson)
    }
