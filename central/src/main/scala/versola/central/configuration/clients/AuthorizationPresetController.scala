package versola.central.configuration.clients

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.*
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.json.*

object AuthorizationPresetController extends Controller:
  type Env = Tracing & AuthorizationPresetService & CentralConfig

  def routes: Routes[Env, Throwable] = Routes(
    getPresetsEndpoint,
    savePresetsEndpoint,
    syncPresetsEndpoint,
  )

  val getPresetsEndpoint =
    Method.GET / "v1" / "configuration" / "auth-request-presets" -> handler { (req: Request) =>
      for
        tenantId <- req.url.queryZIO[TenantId]("tenantId")
        clientId <- req.url.queryZIO[ClientId]("clientId")
        service <- ZIO.service[AuthorizationPresetService]
        response <- service.getClientPresets(tenantId, clientId).map(_.map { preset =>
          AuthorizationPresetResponse(
            id = preset.id,
            clientId = preset.clientId,
            description = preset.description,
            redirectUri = preset.redirectUri,
            scope = preset.scope,
            responseType = preset.responseType,
            uiLocales = preset.uiLocales,
            customParameters = preset.customParameters,
          )
        })
      yield Response.json(response.toJson)
    }

  val savePresetsEndpoint =
    Method.POST / "v1" / "configuration" / "auth-request-presets" -> handler { (req: Request) =>
      for
        service <- ZIO.service[AuthorizationPresetService]
        body <- req.body.asJson[SaveAuthorizationPresetsRequest]
        result <- service.savePresets(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.badRequest
    }

  val syncPresetsEndpoint =
    Method.GET / "v1" / "configuration" / "auth-request-presets" / "sync" -> handler { (req: Request) =>
      for
        service <- ZIO.service[AuthorizationPresetService]
        tenantIds <- authorizeInternal(req)
        presets <- service.getPresetsForSync(tenantIds)
        response = GetAuthorizationPresetsSyncResponse(presets.map { preset =>
          AuthorizationPresetSyncResponse(
            id = preset.id,
            clientId = preset.clientId,
            description = preset.description,
            redirectUri = preset.redirectUri,
            scope = preset.scope,
            responseType = preset.responseType,
            uiLocales = preset.uiLocales,
            customParameters = preset.customParameters,
          )
        })
      yield Response.json(response.toJson)
    }
