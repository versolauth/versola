package versola.central.configuration.clients

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.*
import versola.central.configuration.edges.EdgeService
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*

object AuthorizationPresetController extends Controller:
  type Env = Tracing & AuthorizationPresetService & OAuthClientService & CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getPresetsEndpoint,
    savePresetsEndpoint,
    syncPresetsEndpoint,
  )

  val getPresetsEndpoint =
    Method.GET / "configuration" / "auth-request-presets" -> handler { (req: Request) =>
      for
        _ <- authorizeBasic(req)
        clientId <- req.url.queryZIO[ClientId]("clientId")
        service <- ZIO.service[AuthorizationPresetService]
        response <- service.getClientPresets(clientId).map(_.map { preset =>
          AuthorizationPresetResponse(
            id = preset.id,
            clientId = preset.clientId,
            description = preset.description,
            redirectUri = preset.redirectUri,
            postLoginRedirectUri = preset.postLoginRedirectUri,
            scope = preset.scope,
            responseType = preset.responseType,
            uiLocales = preset.uiLocales,
            customParameters = preset.customParameters,
            cookieDomain = preset.cookieDomain,
            cookiePath = preset.cookiePath,
          )
        })
      yield Response.json(response.toJson)
    }

  val savePresetsEndpoint =
    Method.POST / "configuration" / "auth-request-presets" -> handler { (req: Request) =>
      for
        _ <- authorizeBasic(req)
        service <- ZIO.service[AuthorizationPresetService]
        body <- req.body.asJson[SaveAuthorizationPresetsRequest]
        result <- service.savePresets(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.badRequest
    }

  val syncPresetsEndpoint =
    Method.GET / "configuration" / "auth-request-presets" / "sync" -> handler { (req: Request) =>
      for
        service <- ZIO.service[AuthorizationPresetService]
        edgeId <- authorizeInternal(req)
        presets <- service.getPresetsForSync(edgeId)
        response = GetAuthorizationPresetsSyncResponse(presets.map { preset =>
          AuthorizationPresetSyncResponse(
            id = preset.id,
            clientId = preset.clientId,
            description = preset.description,
            redirectUri = preset.redirectUri,
            postLoginRedirectUri = preset.postLoginRedirectUri,
            scope = preset.scope,
            responseType = preset.responseType,
            uiLocales = preset.uiLocales,
            customParameters = preset.customParameters,
            cookieDomain = preset.cookieDomain,
            cookiePath = preset.cookiePath,
          )
        })
      yield Response.json(response.toJson)
    }
