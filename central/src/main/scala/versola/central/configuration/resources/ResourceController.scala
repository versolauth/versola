package versola.central.configuration.resources

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.clients.OAuthClientService
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateResourceRequest, CreateResourceResponse, GetAllResourcesResponse, GetResourcesRegistryResponse, GetResourcesSyncResponse, ResourceEndpointResponse, ResourceEndpointSyncResponse, ResourceRegistryEntry, ResourceResponse, RotateResourceCredentialResponse, ResourceSyncResponse, UpdateResourceRequest}
import versola.util.http.{Controller, Unauthorized}
import versola.util.{Base64Url, Secret, SecurityService}
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.{DecoderOps, EncoderOps, JsonDecoder}
import zio.ZIO

object ResourceController extends Controller:
  type Env = Tracing & ResourceService & OAuthClientService & CentralConfig & EdgeService & SecurityService

  def routes: Routes[Env, Throwable] = Routes(
    getAllResourcesEndpoint,
    createResourceRoute,
    updateResourceRoute,
    rotateCredentialEndpoint,
    deletePreviousCredentialEndpoint,
    deleteResourceRoute,
    syncResourcesEndpoint,
    resourcesRegistryEndpoint,
  )

  val getAllResourcesEndpoint =
    Method.GET / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        tenantId <- request.url.queryZIO[TenantId]("tenantId")
        offset <- request.url.queryZIO[Option[Int]]("offset").someOrElse(0)
        limit <- request.url.queryZIO[Option[Int]]("limit")
        resources <- service.getTenantResources(tenantId, offset, limit).map(_.map(toResourceResponse))
      yield Response.json(GetAllResourcesResponse(resources).toJson)
    }

  val createResourceRoute =
    Method.POST / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[CreateResourceRequest](request)
        result <- service.createResource(body)
      yield result match
        case Right((resourceId, credential)) =>
          Response.json(CreateResourceResponse(resourceId, credential.map(Base64Url.encode)).toJson).status(Status.Created)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val updateResourceRoute =
    Method.PUT / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        body <- decodeJsonBody[UpdateResourceRequest](request)
        result <- service.updateResource(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val rotateCredentialEndpoint =
    Method.POST / "configuration" / "resources" / "rotate-credential" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        newCredential <- service.rotateCredential(resourceId)
        response = RotateResourceCredentialResponse(Base64Url.encode(newCredential))
      yield Response.json(response.toJson)
    }

  val deletePreviousCredentialEndpoint =
    Method.DELETE / "configuration" / "resources" / "previous-credential" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        _ <- service.deletePreviousCredential(resourceId)
      yield Response.status(Status.NoContent)
    }

  val deleteResourceRoute =
    Method.DELETE / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        _ <- service.deleteResource(resourceId)
      yield Response.status(Status.NoContent)
    }

  val syncResourcesEndpoint =
    Method.GET / "configuration" / "resources" / "sync" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        centralConfig <- ZIO.service[CentralConfig]
        securityService <- ZIO.service[SecurityService]
        edgeService <- ZIO.service[EdgeService]
        edgeId <- authorizeInternal(request)
        transportEncrypt <- edgeId match
          case Some(id) =>
            edgeService.find(id).someOrFail(Unauthorized).map { edge =>
              (credential: Secret) =>
                securityService.encryptRsa(credential, edge.activeRsaPublicKey).map(Base64Url.encode)
            }
          case None =>
            ZIO.succeed: (credential: Secret) =>
              securityService.encryptAes256(credential, centralConfig.secretKey).map(Base64Url.encode)
        resources <- service.getResourcesForSync(edgeId)
        encryptedResources <- ZIO.foreach(resources)(toResourceSyncResponse(_, transportEncrypt))
        response = GetResourcesSyncResponse(encryptedResources)
      yield Response.json(response.toJson)
    }

  /** Lightweight resource registry for auth's RFC 8707 `resource` parameter validation:
    * only what's needed to resolve a requested resource URI to its id and tenant.
    */
  val resourcesRegistryEndpoint =
    Method.GET / "configuration" / "resources" / "registry" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        _ <- authorizeInternal(request)
        resources <- service.getResourcesForSync(None)
        response = GetResourcesRegistryResponse(resources.map { r =>
          ResourceRegistryEntry(resourceId = r.resourceId, tenantId = r.tenantId, resource = r.resource)
        })
      yield Response.json(response.toJson)
    }

  private def decodeJsonBody[A: JsonDecoder](request: Request) =
    request.body.asString.flatMap { body =>
      ZIO.fromEither(body.fromJson[A])
        .mapError(message => RuntimeException(s"Failed to decode JSON: $message"))
    }

  private def toResourceResponse(record: ResourceRecord): ResourceResponse =
    ResourceResponse(
      resourceId = record.resourceId,
      resource = record.resource,
      endpoints = record.endpoints.map { endpoint =>
        ResourceEndpointResponse(
          id = endpoint.id,
          method = endpoint.method,
          path = endpoint.path,
          fetchUserInfo = endpoint.fetchUserInfo,
          allow = endpoint.allowExpression,
          inject = endpoint.inject,
          stepUpCondition = endpoint.stepUpCondition,
          stepUpAcr = endpoint.stepUpAcr,
          maxAge = endpoint.maxAge,
        )
      },
      internal = record.isInternal,
      credentialRotation = record.previousCredential.nonEmpty,
    )

  private def toResourceSyncResponse(
      record: ResourceRecord,
      transportEncrypt: Secret => zio.Task[String],
  ): zio.Task[ResourceSyncResponse] =
    ZIO.foreach(record.credential)(transportEncrypt).map { credential =>
      ResourceSyncResponse(
        resourceId = record.resourceId,
        tenantId = record.tenantId,
        resource = record.resource,
        endpoints = record.endpoints.map { endpoint =>
          ResourceEndpointSyncResponse(
            id = endpoint.id,
            method = endpoint.method,
            path = endpoint.path,
            fetchUserInfo = endpoint.fetchUserInfo,
            allow = endpoint.allowExpression,
            inject = endpoint.inject,
            stepUpCondition = endpoint.stepUpCondition,
            stepUpAcr = endpoint.stepUpAcr,
            maxAge = endpoint.maxAge,
          )
        },
        credential = credential,
      )
    }
