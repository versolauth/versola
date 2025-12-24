package versola.admin

import versola.admin.AdminControllerDescription.*
import versola.oauth.client.{OAuthClientService, OAuthScopeRepository, model}
import versola.oauth.client.model.{Claim, ClientId, ScopeDescription, ScopeToken}
import versola.oauth.model.*
import versola.oauth.client.{OAuthClientService, OAuthScopeRepository}
import versola.util.{Base64Url, SecureRandom}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.http.codec.HttpContentCodec
import zio.http.endpoint.Endpoint
import zio.schema.*
import zio.telemetry.opentelemetry.tracing.Tracing

object AdminController extends Controller:
  type Env = OAuthClientService & OAuthScopeRepository & SecureRandom & Tracing

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
        clientService <- ZIO.service[OAuthClientService]
        clientSecret <- clientService.register(
          ClientId(request.id),
          request.clientName,
          request.redirectUris,
          request.allowedScopes,
        )
        response = CreateClientResponse(Base64Url.encode(clientSecret))
      yield response)
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    rotateSecretEndpoint.implement { request =>
      (for
        clientService <- ZIO.service[OAuthClientService]
        newSecret <- clientService.rotateSecret(ClientId(request.clientId))
        response = RotateSecretResponse(Base64Url.encode(newSecret))
      yield response)
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deleteClientsEndpoint.implement { request =>
      ZIO.serviceWithZIO[OAuthClientService](_.deleteClients(request.clientIds.toVector))
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deletePreviousSecretEndpoint.implement { request =>
      ZIO.serviceWithZIO[OAuthClientService](_.deletePreviousSecret(ClientId(request.clientId)))
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
    },


    getAllDataEndpoint.implement { _ =>
      for
        clientService <- ZIO.service[OAuthClientService]
        clients <- clientService.getAll
        scopes <- clientService.getAllScopes
        clientsResponse = clients.map { case (clientId, client) => OauthClientResponse(
          id = clientId,
          clientName = client.clientName,
          redirectUris = client.redirectUris,
          scope = client.scope,
          hasPreviousSecret = client.previousSecret.nonEmpty
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
        val backendScope = model.Scope(
          claims = oneScope.claims.map(Claim(_)),
          description = ScopeDescription(oneScope.description)
        )
        (ScopeToken(oneScope.name), backendScope)
      }.toVector
      ZIO.serviceWithZIO[OAuthClientService](_.registerScopes(scopesToCreate))
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
    },
    deleteScopesEndpoint.implement { request =>
      val scopeNames = request.scopeNames.map(ScopeToken(_)).toVector
      ZIO.serviceWithZIO[OAuthClientService](_.deleteScopes(scopeNames))
        //.tapError(ex => Controller.exceptions.set(Some(ex)))
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
