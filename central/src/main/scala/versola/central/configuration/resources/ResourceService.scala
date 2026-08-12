package versola.central.configuration.resources

import versola.central.CentralConfig
import versola.central.configuration.edges.EdgeId
import versola.central.configuration.sync.{SyncEvent, SyncOps}
import versola.central.configuration.tenants.{TenantId, TenantRepository}
import versola.central.configuration.{CreateResourceEndpointRequest, CreateResourceRequest, UpdateResourceRequest}
import versola.util.{CacheSource, ReloadingCache, Secret, SecureRandom, SecurityService}
import versola.util.cel.CelEvaluator
import dev.cel.common.types.{CelType, SimpleType}
import zio.{Schedule, Scope, Task, URLayer, ZIO, ZLayer}
import java.security.MessageDigest

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

trait ResourceService:
  def getTenantResources(
      tenantId: TenantId,
      offset: Int,
      limit: Option[Int],
  ): Task[Vector[ResourceRecord]]

  def getResourcesForSync(edgeId: Option[EdgeId]): Task[Vector[ResourceRecord]]

  def verifySecret(provided: Secret): Task[Boolean]

  def createResource(request: CreateResourceRequest): Task[Either[ResourceValidationError, (ResourceId, Option[Secret])]]

  def updateResource(request: UpdateResourceRequest): Task[Either[ResourceValidationError, Unit]]

  def rotateSecret(resourceId: ResourceId): Task[Secret]

  def deletePreviousSecret(resourceId: ResourceId): Task[Unit]

  def deleteResource(resourceId: ResourceId): Task[Unit]

  def sync(event: SyncEvent.ResourcesUpdated): Task[Unit]

object ResourceService:
  case object SecretRotationInProgress extends RuntimeException("Resource secret rotation is already in progress")

  def live: ZLayer[
    ResourceRepository & TenantRepository & CelEvaluator & SecureRandom & SecurityService & CentralConfig & Scope,
    Throwable,
    ResourceService,
  ] =
    decryptingCacheSource >>>
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[CentralConfig](config =>
          ReloadingCache.make[Vector[ResourceRecord]](Schedule.spaced(config.configurationCacheRefreshInterval)),
        )
      ) >>>
      ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  /** A [[CacheSource]] that reads the resource records from the repository and decrypts
    * their secrets, so the in-memory cache holds plaintext secrets and no
    * decryption is needed on cache reads.
    */
  private val decryptingCacheSource
      : URLayer[ResourceRepository & SecurityService & CentralConfig, CacheSource[Vector[ResourceRecord]]] =
    ZLayer.fromFunction: (repository: ResourceRepository, securityService: SecurityService, config: CentralConfig) =>
      new CacheSource[Vector[ResourceRecord]]:
        override def getAll: Task[Vector[ResourceRecord]] =
          repository.getAll.flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, resourceSecretsKey(config))))

  private def resourceSecretsKey(config: CentralConfig): SecretKey =
    SecretKeySpec(config.clientSecretsSecret, "AES")

  /** Decrypts the at-rest encrypted `secret` and `previousSecret` of a resource record. */
  private def decryptSecrets(
      record: ResourceRecord,
      securityService: SecurityService,
      key: SecretKey,
  ): Task[ResourceRecord] =
    for
      secret         <- ZIO.foreach(record.secret)(c => securityService.decryptAes256(c, key).map(Secret(_)))
      previousSecret <- ZIO.foreach(record.previousSecret)(c => securityService.decryptAes256(c, key).map(Secret(_)))
    yield record.copy(secret = secret, previousSecret = previousSecret)

  class Impl(
      cache: ReloadingCache[Vector[ResourceRecord]],
      resourceRepository: ResourceRepository,
      tenantRepository: TenantRepository,
      celEvaluator: CelEvaluator,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      config: CentralConfig,
  ) extends ResourceService:
    export resourceRepository.deleteResource

    private val edgeResourceId = ResourceId("edge")

    override def getTenantResources(
        tenantId: TenantId,
        offset: Int,
        limit: Option[Int],
    ): Task[Vector[ResourceRecord]] =
      cache.get.map { records =>
        records
          .filter(_.tenantId == tenantId)
          .slice(offset, limit.fold(records.size)(offset + _))
      }

    override def getResourcesForSync(edgeId: Option[EdgeId]): Task[Vector[ResourceRecord]] =
      edgeId match
        case None => cache.get
        case Some(id) =>
          for
            resources <- cache.get
            tenants <- tenantRepository.getAll
            allowedTenantIds = tenants.filter(_.edgeId.contains(id)).map(_.id).toSet
          yield resources.filter(r => allowedTenantIds.contains(r.tenantId))

    override def verifySecret(provided: Secret): Task[Boolean] =
      cache.get.map: resources =>
        resources.find(_.resourceId == ResourceId("central")).exists: resource =>
          resource.secret.exists(MessageDigest.isEqual(provided, _)) ||
            resource.previousSecret.exists(MessageDigest.isEqual(provided, _))

    override def createResource(request: CreateResourceRequest): Task[Either[ResourceValidationError, (ResourceId, Option[Secret])]] =
      if !ResourceId.isValid(request.resourceId) then ZIO.left(ResourceValidationError.InvalidResourceId)
      else if request.resourceId == edgeResourceId then ZIO.left(ResourceValidationError.ReservedResourceId)
      else
        validateEndpoints(request.endpoints).flatMap:
          case Some(error) => ZIO.left(error)
          case None =>
            for
              secret <- if request.internal then generateSecret.map(Some(_)) else ZIO.none
              encryptedSecret <- ZIO.foreach(secret)(encryptRawSecret)
              _ <- resourceRepository.createResource(
                tenantId = request.tenantId,
                resourceId = request.resourceId,
                resource = request.resource,
                audience = request.audience,
                endpoints = request.endpoints.map(asRecord),
                secret = encryptedSecret,
              )
            yield Right((request.resourceId, secret))

    override def updateResource(request: UpdateResourceRequest): Task[Either[ResourceValidationError, Unit]] =
      validateEndpoints(request.createEndpoints).flatMap:
        case Some(error) => ZIO.left(error)
        case None =>
          resourceRepository.updateResource(
            resourceId = request.resourceId,
            resourcePatch = request.resource,
            audiencePatch = request.audience,
            deleteEndpoints = request.deleteEndpoints,
            addEndpoints = request.createEndpoints.map(asRecord),
          ).map(Right(_))

    override def rotateSecret(resourceId: ResourceId): Task[Secret] =
      for
        newSecret <- generateSecret
        encryptedSecret <- encryptRawSecret(newSecret)
        rotated <- resourceRepository.rotateSecret(resourceId, encryptedSecret)
        _ <- ZIO.fail(SecretRotationInProgress).unless(rotated)
      yield newSecret

    override def deletePreviousSecret(resourceId: ResourceId): Task[Unit] =
      resourceRepository.deletePreviousSecret(resourceId)

    override def sync(event: SyncEvent.ResourcesUpdated): Task[Unit] =
      SyncOps.syncCache(event)(
        cache,
        resourceRepository.findResource(event.id).flatMap(ZIO.foreach(_)(decryptSecrets(_, securityService, secretsKey))),
      )

    private val secretsKey: SecretKey = ResourceService.resourceSecretsKey(config)

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    private def encryptRawSecret(secret: Secret): Task[Array[Byte]] =
      securityService.encryptAes256(secret, secretsKey)

    private def validateEndpoints(
        endpoints: Vector[CreateResourceEndpointRequest],
    ): Task[Option[ResourceValidationError]] =
      ZIO.foldLeft(endpoints)(Option.empty[ResourceValidationError]):
        case (Some(err), _) => ZIO.succeed(Some(err))
        case (None, endpoint) => validateEndpoint(endpoint)

    private val validPathRegex = "^/([a-zA-Z0-9-]+(/[a-zA-Z0-9-]+)*)?$".r

    private def validateEndpoint(
        endpoint: CreateResourceEndpointRequest,
    ): Task[Option[ResourceValidationError]] =
      val trimmedPath = endpoint.path.trim
      if !validPathRegex.matches(trimmedPath) then
        return ZIO.some(ResourceValidationError.InvalidEndpointPath(endpoint.id))
      val allowCheck = endpoint.allow.filter(_.trim.nonEmpty) match
        case None => ZIO.none
        case Some(expression) =>
          celEvaluator.validate(expression, Some(SimpleType.BOOL))
            .as(Option.empty[ResourceValidationError])
            .catchAll: err =>
              ZIO.some(ResourceValidationError.InvalidAllowExpression(endpoint.id, err.expression, err.message))

      allowCheck.flatMap:
        case Some(err) => ZIO.some(err)
        case None =>
          val injectCheck = ZIO.foldLeft(endpoint.inject)(Option.empty[ResourceValidationError]):
            case (Some(err), _) => ZIO.succeed(Some(err))
            case (None, rule) =>
              celEvaluator.validate(rule.expression, None)
                .as(Option.empty[ResourceValidationError])
                .catchAll: err =>
                  ZIO.some(ResourceValidationError.InvalidInjectExpression(endpoint.id, rule.name, err.expression, err.message))

          injectCheck.flatMap:
            case Some(err) => ZIO.some(err)
            case None =>
              endpoint.stepUpCondition.filter(_.trim.nonEmpty) match
                case None => ZIO.none
                case Some(expression) =>
                  celEvaluator.validate(expression, Some(SimpleType.BOOL))
                    .as(Option.empty[ResourceValidationError])
                    .catchAll: err =>
                      ZIO.some(ResourceValidationError.InvalidStepUpConditionExpression(endpoint.id, err.expression, err.message))

    private def asRecord(request: CreateResourceEndpointRequest) =
      ResourceEndpointRecord(
        id = request.id,
        path = request.path,
        method = request.method,
        fetchUserInfo = request.fetchUserInfo,
        allowExpression = request.allow,
        inject = request.inject,
        stepUpCondition = request.stepUpCondition,
        stepUpAcr = request.stepUpAcr,
        maxAge = request.maxAge,
      )
