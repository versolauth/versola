package versola.central.configuration.clients

import versola.central.configuration.*
import versola.central.configuration.clients.certificates.ClientCertificateService
import versola.central.configuration.clients.est.ClientEstService
import versola.central.configuration.edges.{EdgeId, EdgeService}
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.http.{Controller, Unauthorized}
import versola.util.{Base64Url, Patch, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.prelude.These
import zio.schema.*

object ClientController extends Controller:
  type Env =
    Tracing & OAuthClientService & ClientCertificateService & ClientEstService & ResourceService & CentralConfig & SecurityService & EdgeService

  def routes: Routes[Env, Throwable] = Routes(
    getAllClientsEndpoint,
    getAllClientsSyncEndpoint,
    createWebClientEndpoint,
    createNativeClientEndpoint,
    createServiceClientEndpoint,
    updateWebClientEndpoint,
    updateNativeClientEndpoint,
    updateServiceClientEndpoint,
    renewEdgeCertificateEndpoint,
    rotateSecretEndpoint,
    deletePreviousSecretEndpoint,
    deleteClientEndpoint,
  )

  val getAllClientsEndpoint =
    Method.GET / "configuration" / "clients" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        clientService <- ZIO.service[OAuthClientService]
        estStates <- ZIO.serviceWithZIO[ClientEstService](_.states)

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
              authMethod = client.authMethod,
              accessTokenTtl = client.accessTokenTtl.toSeconds,
              refreshTokenTtl = client.refreshTokenTtl.toSeconds,
              theme = client.theme,
              authFlow = client.authFlow,
              otpTemplateId = client.otpTemplateId,
              frontChannelLogoutUri = client.frontChannelLogoutUri.map(_.encode),
              frontChannelLogoutSessionRequired = client.frontChannelLogoutSessionRequired,
              backChannelLogoutUri = client.backChannelLogoutUri.map(_.encode),
              logoUri = client.logoUri,
              policyUri = client.policyUri,
              tosUri = client.tosUri,
              consentFlow = client.consentFlow.map(ConsentFlowDto.fromDomain),
              dpopBoundAccessTokens = client.dpopBoundAccessTokens,
              dpopSigningAlgs = client.dpopSigningAlgs,
              dpopMinRsaKeySize = client.dpopMinRsaKeySize,
              mtlsAuth = client.mtlsAuth,
              certificateBoundAccessTokens = client.certificateBoundAccessTokens,
              jwks = client.jwks,
              requireSignedRequestObject = client.requireSignedRequestObject,
              requirePushedAuthorizationRequests = client.requirePushedAuthorizationRequests,
              template = client.template,
              createdAt = client.createdAt,
              applicationType = client.applicationType,
              kind = ClientKind.of(client),
              themeOverrides = client.themeOverrides,
              rateLimit = client.rateLimit,
              est = estStates.get(client.id),
            )
          })
      yield Response.json(GetAllClientsResponse(clients.toList).toJson)
    }

  val getAllClientsSyncEndpoint =
    Method.GET / "configuration" / "clients" / "sync" -> handler { (request: Request) =>
      for
        edgeId <- authorizeInternal(request)
        response <- clientsSyncResponse(edgeId)
      yield Response.json(response.toJson)
    }

  /** The clients a sync caller is served, with every secret encrypted to that caller alone: to
    * auth under the shared transport secret, to an edge under its registered RSA key. Shared
    * with `EdgeSyncController`, which is the only route an edge reaches it through (#554); here
    * `edgeId` is what `authorizeInternal` proved, so an edge is never served another's tenants.
    */
  def clientsSyncResponse(
      edgeId: Option[EdgeId],
  ): RIO[OAuthClientService & CentralConfig & SecurityService & EdgeService & ClientCertificateService, GetOAuthClientsSyncResponse] =
    for
      clientService <- ZIO.service[OAuthClientService]
      centralConfig <- ZIO.service[CentralConfig]
      securityService <- ZIO.service[SecurityService]
      edgeService <- ZIO.service[EdgeService]
      transportEncrypt <- edgeId match
        case Some(id) =>
          edgeService.find(id).someOrFail(Unauthorized).map {
            edge =>
              // Hybrid, not encryptRsa directly: a generated client secret fits in one RSA-
              // OAEP block, but edgeSigningKey's stored JWK document does not, and this is
              // the one transport both go through.
              (secret: Secret) =>
                securityService.encryptRsaHybrid(secret, edge.activeRsaPublicKey).map(Base64Url.encode)
          }
        case None =>
          ZIO.succeed: (secret: Secret) =>
            securityService.encryptAes256(secret, centralConfig.secretKey).map(Base64Url.encode)
      certificateService <- ZIO.service[ClientCertificateService]
      clients <- clientService.getClientsForSync(edgeId)
      // Only an edge enrols (#463), so only an edge is told what its certificates must say.
      enrollmentSubjects <- certificateService.enrollmentSubjects(clients).when(edgeId.isDefined).map(_.getOrElse(Map.empty))
      encryptedClients <- ZIO.foreach(clients) { client =>
        for
          secret <- ZIO.foreach(client.secret)(transportEncrypt)
          previousSecret <- ZIO.foreach(client.previousSecret)(transportEncrypt)
          // Only an edge can decrypt this, and only an edge has any use for it: the key is
          // how it authenticates as this client. A caller that is not one gets the field
          // absent rather than encrypted to a key it does not hold.
          edgeSigningKey <- ZIO.foreach(client.edgeSigningKey.filter(_ => edgeId.isDefined))(transportEncrypt)
          edgeClientCertificate <- ZIO.foreach(
            client.edgeClientCertificate.filter(_ => edgeId.isDefined),
          )(transportEncrypt)
        yield SyncOAuthClientRecord(
          id = client.id,
          tenantId = client.tenantId,
          clientName = client.clientName,
          redirectUris = client.redirectUris,
          scope = client.scope,
          secret = secret,
          previousSecret = previousSecret,
          accessTokenTtl = client.accessTokenTtl,
          refreshTokenTtl = client.refreshTokenTtl,
          permissions = client.permissions,
          theme = client.theme,
          authFlow = client.authFlow,
          otpTemplateId = client.otpTemplateId,
          frontChannelLogoutUri = client.frontChannelLogoutUri.map(_.encode),
          frontChannelLogoutSessionRequired = client.frontChannelLogoutSessionRequired,
          backChannelLogoutUri = client.backChannelLogoutUri.map(_.encode),
          logoUri = client.logoUri,
          policyUri = client.policyUri,
          tosUri = client.tosUri,
          consentFlow = client.consentFlow,
          dpopBoundAccessTokens = client.dpopBoundAccessTokens,
          dpopSigningAlgs = client.dpopSigningAlgs,
          dpopMinRsaKeySize = client.dpopMinRsaKeySize,
          authMethod = client.authMethod,
          mtlsAuth = client.mtlsAuth,
          certificateBoundAccessTokens = client.certificateBoundAccessTokens,
          jwks = client.jwks,
          requireSignedRequestObject = client.requireSignedRequestObject,
          requirePushedAuthorizationRequests = client.requirePushedAuthorizationRequests,
          edgeSigningKey = edgeSigningKey,
          edgeClientCertificate = edgeClientCertificate,
          applicationType = client.applicationType,
          edgeCertificateSubject = enrollmentSubjects.get(client.id),
          themeOverrides = client.themeOverrides,
          rateLimit = client.rateLimit,
        )
      }
    yield GetOAuthClientsSyncResponse(clients = encryptedClients)

  // ── One registration and one update per kind of client ─────────────────────────────────────
  //
  // Each takes the request type of its kind (TypedClientRequests.scala), fixes what the kind
  // fixes, holds the result to the kind's rules, and then registers or updates exactly as the
  // general endpoint does -- the same checks, the same tenant security profile.

  val createWebClientEndpoint =
    Method.POST / "configuration" / "clients" / "web" ->
      createTyped[CreateWebClientRequest](ClientKind.web)(_.toCreateClientRequest)

  val createNativeClientEndpoint =
    Method.POST / "configuration" / "clients" / "native" ->
      createTyped[CreateNativeClientRequest](ClientKind.device)(_.toCreateClientRequest)

  val createServiceClientEndpoint =
    Method.POST / "configuration" / "clients" / "service" ->
      createTyped[CreateServiceClientRequest](ClientKind.service)(_.toCreateClientRequest)

  val updateWebClientEndpoint =
    Method.PUT / "configuration" / "clients" / "web" ->
      updateTyped[UpdateWebClientRequest](ClientKind.web)(_.clientId, _.toUpdateClientRequest)

  val updateNativeClientEndpoint =
    Method.PUT / "configuration" / "clients" / "native" ->
      updateTyped[UpdateNativeClientRequest](ClientKind.device)(_.clientId, _.toUpdateClientRequest)

  val updateServiceClientEndpoint =
    Method.PUT / "configuration" / "clients" / "service" ->
      updateTyped[UpdateServiceClientRequest](ClientKind.service)(_.clientId, _.toUpdateClientRequest)

  private def createTyped[A: JsonDecoder](kind: ClientKind)(
      toRequest: A => CreateClientRequest,
  ): Handler[Env, Throwable, Request, Response] =
    handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ClientCertificateService]
        est <- ZIO.service[ClientEstService]
        body <- request.bodyAs[A]
        create = toRequest(body)
        _ <- ZIO.when(create.frontChannelLogoutUri.isDefined && create.backChannelLogoutUri.isDefined):
          ZIO.fail(InvalidClientLogoutConfiguration(create.id))
        _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateKindRegistration(kind, create))(ZIO.fail(_))
        registered <- if create.estEnrollment then est.registerClient(create) else service.register(create)
        response = CreateClientResponse(registered.secret.map(Base64Url.encode), registered.createdAt, registered.privateKey)
      yield Response.json(response.toJson).status(Status.Created))
        .catchAll(typedFailure)
    }

  private def updateTyped[A: JsonDecoder](kind: ClientKind)(
      clientIdOf: A => ClientId,
      toRequest: A => UpdateClientRequest,
  ): Handler[Env, Throwable, Request, Response] =
    handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        certificates <- ZIO.service[ClientCertificateService]
        body <- request.bodyAs[A]
        update = toRequest(body)
        _ <- ZIO.when(hasInvalidLogoutConfiguration(update)):
          ZIO.fail(InvalidClientLogoutConfiguration(update.clientId))
        current <- service.findClient(clientIdOf(body))
        response <- current match
          case None => ZIO.succeed(Response.status(Status.NotFound))
          // A client's kind is fixed when it is registered: a request to another kind's endpoint
          // is a conflict with the client's own state, like rotating a secret it does not have.
          case Some(client) if ClientKind.of(client) != kind =>
            ZIO.succeed:
              Response.text(
                s"Client '${client.id}' is a ${ClientKind.of(client)} client; update it at " +
                  s"/configuration/clients/${path(ClientKind.of(client))}",
              ).status(Status.Conflict)
          case Some(client) =>
            for
              enrolled <- certificates.isEnrolled(client.id)
              edgeFronted = client.edgeClientCertificate.nonEmpty || enrolled
              _ <- ZIO.foreachDiscard(InvalidRegistrationConfiguration.validateKindUpdate(kind, update, client, edgeFronted))(ZIO.fail(_))
              fixed = InvalidRegistrationConfiguration.withKindFixed(kind, update, client, edgeFronted)
              _ <- service.updateClient(fixed, edgeCertificateEnrolled = enrolled)
              _ <- certificates.forgetIfReplaced(fixed)
            yield Response.status(Status.NoContent)
      yield response)
        .catchAll(typedFailure)
    }

  private def path(kind: ClientKind): String = kind match
    case ClientKind.web => "web"
    case ClientKind.device => "native"
    case ClientKind.service => "service"

  private def typedFailure(
      error: Throwable | ClientAlreadyExists | InvalidClientLogoutConfiguration | InvalidConsentUri | InvalidRegistrationConfiguration,
  ): IO[Throwable, Response] =
    error match
      case _: ClientAlreadyExists => ZIO.succeed(Response.status(Status.Conflict))
      case _: InvalidClientLogoutConfiguration =>
        ZIO.succeed:
          Response.text("A client can only have one of frontChannelLogoutUri or backChannelLogoutUri configured")
            .status(Status.BadRequest)
      case error: InvalidConsentUri => ZIO.succeed(Response.text(error.getMessage).status(Status.BadRequest))
      case error: InvalidRegistrationConfiguration =>
        ZIO.succeed(Response.text(s"Invalid registration configuration: ${error.reason}").status(Status.BadRequest))
      case error: Throwable => ZIO.fail(error)

  private def hasInvalidLogoutConfiguration(request: UpdateClientRequest): Boolean =
    isSettingValue(request.frontChannelLogoutUri) && isSettingValue(request.backChannelLogoutUri)

  private def isSettingValue(patch: Option[Patch[String]]): Boolean =
    patch.exists:
      case Patch.Modified(_) => true
      case Patch.Deleted => false

  /** A client that did not register [[AuthMethod.client_secret]] has no secret to rotate or
    * forget - a public client because it holds no credential at all, a private_key_jwt or
    * mTLS client because its credential is a key or a certificate. Like an already-taken
    * client id, this is a conflict with the client's own state rather than a malformed
    * request.
    */
  private def secretlessClientConflict(error: ClientHasNoSecret): Response =
    Response.text(s"Client '${error.clientId}' has no secret")
      .status(Status.Conflict)

  /** #440: issues a new certificate for a client whose certificate central manages, now rather
    * than at its renewal time -- after a CA change, say. 404 for a client central does not
    * manage the certificate of. */
  val renewEdgeCertificateEndpoint =
    Method.POST / "configuration" / "clients" / "edge-certificate" / "renew" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[ClientCertificateService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        renewed <- service.renew(clientId)
      yield
        if renewed then Response.status(Status.NoContent)
        else Response.text(s"Central does not manage the certificate of client '$clientId'").status(Status.NotFound))
        .catchAll {
          case error: InvalidRegistrationConfiguration =>
            ZIO.succeed(Response.text(s"Invalid registration configuration: ${error.reason}").status(Status.BadRequest))
          case error: Throwable => ZIO.fail(error)
        }
    }

  val rotateSecretEndpoint =
    Method.POST / "configuration" / "clients" / "rotate-secret" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        newSecret <- service.rotateClientSecret(clientId)
        response = RotateSecretResponse(Base64Url.encode(newSecret))
      yield Response.json(response.toJson))
        .catchAll {
          case error: ClientHasNoSecret => ZIO.succeed(secretlessClientConflict(error))
          case error: Throwable => ZIO.fail(error)
        }
    }

  val deletePreviousSecretEndpoint =
    Method.DELETE / "configuration" / "clients" / "previous-secret" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- service.deletePreviousClientSecret(clientId)
      yield Response.status(Status.NoContent))
        .catchAll {
          case error: ClientHasNoSecret => ZIO.succeed(secretlessClientConflict(error))
          case error: Throwable => ZIO.fail(error)
        }
    }

  val deleteClientEndpoint =
    Method.DELETE / "configuration" / "clients" -> handler { (request: Request) =>
      for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        clientId <- request.url.queryZIO[ClientId]("clientId")
        _ <- service.deleteClient(clientId)
      yield Response.status(Status.NoContent)
    }
