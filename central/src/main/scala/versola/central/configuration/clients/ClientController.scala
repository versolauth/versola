package versola.central.configuration.clients

import versola.central.{CentralConfig, authorizeInternal}
import versola.central.configuration.*
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.util.http.Controller
import versola.util.{Base64Url, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.*
import zio.prelude.These

object ClientController extends Controller:
  type Env = Tracing & OAuthClientService & CentralConfig & SecurityService & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllClientsEndpoint,
    getAllClientsSyncEndpoint,
    createClientEndpoint,
    updateClientEndpoint,
    rotateSecretEndpoint,
    deletePreviousSecretEndpoint,
    deleteClientEndpoint,
  )

  val getAllClientsEndpoint =
    Method.GET / "configuration" / "clients" -> handler { (request: Request) =>
      for
        clientService <- ZIO.service[OAuthClientService]

        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")

        clients <- clientService.getTenantClients(tenantId, offset, limit)
          .map(_.map { client =>
            OAuthClientResponse(
              id = client.id,
              clientName = client.clientName,
              redirectUris = client.redirectUris,
              scope = client.scope,
              permissions = client.permissions,
              secretRotation = client.previousSecret.nonEmpty,
            )
          })
      yield Response.json(GetAllClientsResponse(clients.toList).toJson)
    }

  val getAllClientsSyncEndpoint =
    Method.GET / "configuration" / "clients" / "sync" -> handler { (request: Request) =>
      for
        clientService <- ZIO.service[OAuthClientService]
        centralConfig <- ZIO.service[CentralConfig]
        edgeId <- authorizeInternal(request)
        clients <- clientService.getClientsForSync(edgeId)
        encryptedClients <- ZIO.foreach(clients) { client =>
          for
            secret <- ZIO.foreach(client.secret)(encryptSecret)
            previousSecret <- ZIO.foreach(client.previousSecret)(encryptSecret)
          yield SyncOAuthClientRecord(
            id = client.id,
            tenantId = client.tenantId,
            clientName = client.clientName,
            redirectUris = client.redirectUris,
            scope = client.scope,
            externalAudience = client.externalAudience,
            secret = secret,
            previousSecret = previousSecret,
            accessTokenTtl = client.accessTokenTtl,
            refreshTokenTtl = client.refreshTokenTtl,
            permissions = client.permissions,
          )
        }
        encryptedPepper <- encryptSecret(centralConfig.clientSecretsPepper)
      yield Response.json(GetOAuthClientsSyncResponse(clients = encryptedClients, pepper = encryptedPepper).toJson)
    }


  val createClientEndpoint =
    Method.POST / "configuration" / "clients" -> handler { (request: Request) =>
      (for
        service <- ZIO.service[OAuthClientService]
        body <- request.body.asJson[CreateClientRequest]
        secret <- service.registerClient(body)
        response = CreateClientResponse(Base64Url.encode(secret))
      yield Response.json(response.toJson).status(Status.Created))
        .catchAll {
          case error: ClientAlreadyExists =>
            ZIO.succeed:
              Response.status(Status.Conflict)
          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  val updateClientEndpoint =
    Method.PUT / "configuration" / "clients" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthClientService]
        body <- request.body.asJson[UpdateClientRequest]
        _ <- service.updateClient(body)
      yield Response.status(Status.NoContent)
    }

  val rotateSecretEndpoint =
    Method.POST / "configuration" / "clients" / "rotate-secret" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        newSecret <- service.rotateClientSecret(clientId)
        response = RotateSecretResponse(Base64Url.encode(newSecret))
      yield Response.json(response.toJson)
    }

  val deletePreviousSecretEndpoint =
    Method.DELETE / "configuration" / "clients" / "previous-secret" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- service.deletePreviousClientSecret(clientId)
      yield Response.status(Status.NoContent)
    }

  val deleteClientEndpoint =
    Method.DELETE / "configuration" / "clients" -> handler { (request: Request) =>
      for
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- service.deleteClient(clientId)
      yield Response.status(Status.NoContent)
    }

  private def encryptSecret(secret: Secret): ZIO[CentralConfig & SecurityService, Throwable, String] =
    for
      config <- ZIO.service[CentralConfig]
      securityService <- ZIO.service[SecurityService]
      encrypted <- securityService.encryptAes256(secret, config.secretKey)
    yield Base64Url.encode(encrypted)
