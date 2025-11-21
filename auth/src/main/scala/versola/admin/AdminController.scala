package versola.admin

import versola.admin.AdminControllerDescription.*
import versola.http.Controller
import versola.oauth.model.*
import versola.oauth.{OAuthScopeRepository, OauthClientService}
import versola.util.SecureRandom
import zio.*
import zio.http.*
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object AdminController extends Controller:
  type Env = OauthClientService & OAuthScopeRepository & SecureRandom & Tracing

  // Frontend serving endpoints (non-declarative for simplicity)
  private val frontendRoutes = Routes(
    Method.GET / "admin" -> handler { (_: Request) =>
      serveStaticHtmlFile("admin.html")
    },
  )

  // Endpoints are now defined in AdminControllerDescription (shared)

  def routes: Routes[Env, Nothing] = frontendRoutes ++ Routes(

    createClientEndpoint.implement { request =>
      (for
        clientService <- ZIO.service[OauthClientService]
        clientSecret <- clientService.register(
          ClientId(request.id),
          request.clientName,
          request.redirectUris,
          request.allowedScopes,
        )
        response = CreateClientResponse(clientSecret)
      yield response)
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    rotateSecretEndpoint.implement { request =>
      (for
        clientService <- ZIO.service[OauthClientService]
        newSecret <- clientService.rotateSecret(ClientId(request.clientId))
        response = RotateSecretResponse(newSecret)
      yield response)
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deleteClientsEndpoint.implement { request =>
      ZIO.serviceWithZIO[OauthClientService](_.deleteClients(request.clientIds.toVector))
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deletePreviousSecretEndpoint.implement { request =>
      ZIO.serviceWithZIO[OauthClientService](_.deletePreviousSecret(ClientId(request.clientId)))
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },


    getAllDataEndpoint.implement { _ =>
      for
        clientService <- ZIO.service[OauthClientService]
        clients <- clientService.getAll
        scopes <- clientService.getAllScopes
        clientsResponse = clients.map { case (clientId, client) => OauthClientResponse(
          id = clientId,
          clientName = client.clientName,
          redirectUris = client.redirectUris,
          scope = client.scope,
          hasPreviousSecret = client.hasPreviousSecret
        )}.toVector
        scopesResponse = scopes.map { case (scopeName, scope) =>
          OneScope(
            name = scopeName,
            description = scope.description,
            claims = scope.claims.map(identity)
          )
        }.toVector
      yield AllDataResponse(clients = clientsResponse, scopes = scopesResponse)
    },
    createScopesEndpoint.implement { request =>
      val scopesToCreate = request.scopes.map { oneScope =>
        val backendScope = versola.oauth.model.Scope(
          claims = oneScope.claims.map(Claim(_)),
          description = ScopeDescription(oneScope.description)
        )
        (ScopeToken(oneScope.name), backendScope)
      }.toVector
      ZIO.serviceWithZIO[OauthClientService](_.registerScopes(scopesToCreate))
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deleteScopesEndpoint.implement { request =>
      val scopeNames = request.scopeNames.map(ScopeToken(_)).toVector
      ZIO.serviceWithZIO[OauthClientService](_.deleteScopes(scopeNames))
        .tapError(ex => Controller.exceptions.set(Some(ex)))
    },
  )

  private def serveStaticHtmlFile(filename: String): UIO[Response] =
    ZIO.attempt {
      val resourcePath = s"/static/$filename"
      Option(getClass.getResourceAsStream(resourcePath)) match {
        case Some(inputStream) =>
          try {
            val content = scala.io.Source.fromInputStream(inputStream).mkString
            Response.text(content).addHeader(Header.ContentType(MediaType.text.html))
          } finally {
            inputStream.close()
          }
        case None =>
          Response.status(Status.NotFound)
      }
    }.catchAll(_ => ZIO.succeed(Response.status(Status.NotFound)))

  // Models are now defined in AdminControllerDescription (shared)
