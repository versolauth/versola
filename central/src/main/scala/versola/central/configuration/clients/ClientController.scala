package versola.central.configuration.clients

import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.central.configuration.*
import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.configuration.tenants.TenantId
import versola.util.http.{Controller, Unauthorized}
import versola.util.{Base64Url, Patch, Secret, SecurityService}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.*
import zio.prelude.These

object ClientController extends Controller:
  type Env = Tracing & OAuthClientService & ResourceService & CentralConfig & SecurityService & EdgeService

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
        _ <- authorizeBasic(request)
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
              authMethod = client.authMethod,
              accessTokenTtl = client.accessTokenTtl.toSeconds,
              refreshTokenTtl = client.refreshTokenTtl.toSeconds,
              theme = client.theme,
              authFlow = client.authFlow,
              registrationFlow = client.registrationFlow,
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
            )
          })
      yield Response.json(GetAllClientsResponse(clients.toList).toJson)
    }

  val getAllClientsSyncEndpoint =
    Method.GET / "configuration" / "clients" / "sync" -> handler { (request: Request) =>
      for
        clientService <- ZIO.service[OAuthClientService]
        centralConfig <- ZIO.service[CentralConfig]
        securityService <- ZIO.service[SecurityService]
        edgeService <- ZIO.service[EdgeService]
        edgeId <- authorizeInternal(request)
        transportEncrypt <- edgeId match
          case Some(id) =>
            edgeService.find(id).someOrFail(Unauthorized).map { edge =>
              // Hybrid, not encryptRsa directly: a generated client secret fits in one RSA-
              // OAEP block, but edgeSigningKey's stored JWK document does not, and this is
              // the one transport both go through.
              (secret: Secret) =>
                securityService.encryptRsaHybrid(secret, edge.activeRsaPublicKey).map(Base64Url.encode)
            }
          case None =>
            ZIO.succeed: (secret: Secret) =>
              securityService.encryptAes256(secret, centralConfig.secretKey).map(Base64Url.encode)
        clients <- clientService.getClientsForSync(edgeId)
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
            registrationFlow = client.registrationFlow,
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
          )
        }
      yield Response.json(GetOAuthClientsSyncResponse(clients = encryptedClients).toJson)
    }


  val createClientEndpoint =
    Method.POST / "configuration" / "clients" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        body <- request.bodyAs[CreateClientRequest]
        _ <- ZIO.when(body.frontChannelLogoutUri.isDefined && body.backChannelLogoutUri.isDefined):
          ZIO.fail(InvalidClientLogoutConfiguration(body.id))
        secret <- service.registerClient(body)
        response = CreateClientResponse(secret.map(Base64Url.encode))
      yield Response.json(response.toJson).status(Status.Created))
        .catchAll {
          case error: ClientAlreadyExists =>
            ZIO.succeed:
              Response.status(Status.Conflict)
          case error: InvalidClientLogoutConfiguration =>
            ZIO.succeed:
              Response.text("A client can only have one of frontChannelLogoutUri or backChannelLogoutUri configured")
                .status(Status.BadRequest)
          case error: InvalidConsentUri =>
            ZIO.succeed(Response.text(error.getMessage).status(Status.BadRequest))
          case error: InvalidRegistrationConfiguration =>
            ZIO.succeed:
              Response.text(s"Invalid registration configuration: ${error.reason}").status(Status.BadRequest)
          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  val updateClientEndpoint =
    Method.PUT / "configuration" / "clients" -> handler { (request: Request) =>
      (for
        _ <- authorizeBasic(request)
        service <- ZIO.service[OAuthClientService]
        body <- request.bodyAs[UpdateClientRequest]
        _ <- ZIO.when(hasInvalidLogoutConfiguration(body)):
          ZIO.fail(InvalidClientLogoutConfiguration(body.clientId))
        _ <- service.updateClient(body)
      yield Response.status(Status.NoContent))
        .catchAll {
          case error: InvalidClientLogoutConfiguration =>
            ZIO.succeed:
              Response.text("A client can only have one of frontChannelLogoutUri or backChannelLogoutUri configured")
                .status(Status.BadRequest)
          case error: InvalidConsentUri =>
            ZIO.succeed(Response.text(error.getMessage).status(Status.BadRequest))
          case error: InvalidRegistrationConfiguration =>
            ZIO.succeed:
              Response.text(s"Invalid registration configuration: ${error.reason}").status(Status.BadRequest)
          case error: Throwable =>
            ZIO.fail(error)
        }
    }

  private def hasInvalidLogoutConfiguration(request: UpdateClientRequest): Boolean =
    isSettingValue(request.frontChannelLogoutUri) && isSettingValue(request.backChannelLogoutUri)

  private def isSettingValue(patch: Option[Patch[String]]): Boolean =
    patch.exists:
      case Patch.Modified(_) => true
      case Patch.Deleted     => false

  /** A client that did not register [[AuthMethod.client_secret]] has no secret to rotate or
    * forget - a public client because it holds no credential at all, a private_key_jwt or
    * mTLS client because its credential is a key or a certificate. Like an already-taken
    * client id, this is a conflict with the client's own state rather than a malformed
    * request.
    */
  private def secretlessClientConflict(error: ClientHasNoSecret): Response =
    Response.text(s"Client '${error.clientId}' has no secret")
      .status(Status.Conflict)

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
          case error: Throwable         => ZIO.fail(error)
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
          case error: Throwable         => ZIO.fail(error)
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

