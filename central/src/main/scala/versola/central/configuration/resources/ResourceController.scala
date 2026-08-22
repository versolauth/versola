package versola.central.configuration.resources

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{CreateResourceRequest, CreateResourceResponse, GetAllResourcesResponse, GetResourcesRegistryResponse, GetResourcesSyncResponse, ResourceEndpointResponse, ResourceEndpointSyncResponse, ResourceRegistryEntry, ResourceResponse, ResourceSyncResponse, RotateResourceSecretResponse, UpdateResourceRequest}
import versola.util.http.{Controller, Unauthorized}
import versola.util.{Base64Url, Secret, SecurityService}
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.EncoderOps
import zio.{RIO, Task, ZIO}

object ResourceController extends Controller:
  type Env = Tracing & ResourceService & CentralConfig & EdgeService & SecurityService

  def routes: Routes[Env, Throwable] = Routes(
    getAllResourcesEndpoint,
    createResourceRoute,
    updateResourceRoute,
    rotateSecretEndpoint,
    deletePreviousSecretEndpoint,
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
        body <- request.bodyAs[CreateResourceRequest]
        result <- service.createResource(body)
      yield result match
        case Right((resourceId, secret)) =>
          Response.json(CreateResourceResponse(resourceId, secret.map(Base64Url.encode)).toJson).status(Status.Created)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val updateResourceRoute =
    Method.PUT / "configuration" / "resources" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        body <- request.bodyAs[UpdateResourceRequest]
        result <- service.updateResource(body)
      yield result match
        case Right(_) => Response.status(Status.NoContent)
        case Left(error) => Response.json(error.toJson).status(Status.BadRequest)
    }

  val rotateSecretEndpoint =
    Method.POST / "configuration" / "resources" / "rotate-secret" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        newSecret <- service.rotateSecret(resourceId)
        response = RotateResourceSecretResponse(Base64Url.encode(newSecret))
      yield Response.json(response.toJson)).catchSome:
        case ResourceService.SecretRotationInProgress =>
          ZIO.succeed(Response.status(Status.Conflict))
    }

  val deletePreviousSecretEndpoint =
    Method.DELETE / "configuration" / "resources" / "previous-secret" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ResourceService]
        resourceId <- request.url.queryZIO[ResourceId]("resourceId")
        _ <- service.deletePreviousSecret(resourceId)
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
        edgeId <- authorizeInternal(request)
        resources <- service.getResourcesForSync(edgeId)
        encryptedResources <- ZIO.foreach(resources)(toResourceSyncResponse(_, edgeId))
        response = GetResourcesSyncResponse(encryptedResources)
      yield Response.json(response.toJson)
    }

  private def transportEncryption(edgeId: Option[EdgeId])(secret: Secret) =
    for
      centralConfig <- ZIO.service[CentralConfig]
      securityService <- ZIO.service[SecurityService]
      edgeService <- ZIO.service[EdgeService]
      encrypted <- edgeId match
        case Some(id) =>
          edgeService.find(id).someOrFail(Unauthorized).flatMap: edge =>
            securityService.encryptRsa(secret, edge.activeRsaPublicKey).map(Base64Url.encode)
        case None =>
          securityService.encryptAes256(secret, centralConfig.secretKey).map(Base64Url.encode)
    yield encrypted

  /** Lightweight resource registry for auth's RFC 8707 `resource` parameter validation:
    * only what's needed to resolve a requested resource URI to its id and tenant.
    */
  val resourcesRegistryEndpoint =
    Method.GET / "configuration" / "resources" / "registry" -> handler { (request: Request) =>
      for
        service <- ZIO.service[ResourceService]
        edgeId <- authorizeInternal(request)
        resources <- service.getResourcesForSync(edgeId)
        response = GetResourcesRegistryResponse(resources.map { r =>
          ResourceRegistryEntry(
            resourceId = r.resourceId,
            tenantId = r.tenantId,
            resource = r.resource,
            audience = r.audience,
            internal = r.isInternal,
          )
        })
      yield Response.json(response.toJson)
    }

  private def toResourceResponse(record: ResourceRecord): ResourceResponse =
    ResourceResponse(
      resourceId = record.resourceId,
      resource = record.resource,
      audience = record.audience,
      endpoints = record.endpoints.sortBy(endpoint => (endpoint.path, endpoint.method)).map { endpoint =>
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
      secretRotation = record.previousSecret.nonEmpty,
    )

  private def toResourceSyncResponse(
      record: ResourceRecord,
      edgeId: Option[EdgeId]
  ) =
    // Keep Edge on the old secret until the previous secret is explicitly removed.
    val effectiveSecret = record.previousSecret.orElse(record.secret)
    ZIO.foreach(effectiveSecret)(transportEncryption(edgeId)).map { encryptedSecret =>
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
        secret = encryptedSecret,
      )
    }
