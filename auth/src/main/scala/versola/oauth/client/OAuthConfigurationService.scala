package versola.oauth.client

import versola.oauth.client.model.{ClientId, ClientSecret, ClientsWithPepper, OAuthClientRecord, ScopeRecord, ScopeToken}
import versola.util.{CoreConfig, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.http.Client
import zio.prelude.{EqualOps, NonEmptySet}

trait OAuthConfigurationService:
  def find(id: ClientId): UIO[Option[OAuthClientRecord]]

  def verifySecret(
      id: ClientId,
      providedSecret: Option[Secret],
  ): UIO[Option[OAuthClientRecord]]

  def getScopes: UIO[Vector[ScopeRecord]]

object OAuthConfigurationService:
  def live(schedule: Schedule[Any, Any, Any]): ZLayer[
    Client & SecurityService & Scope & CoreConfig,
    Throwable,
    OAuthConfigurationService,
  ] = {
    val syncClients =
      CentralSyncTokenService.live >+>
        ((OAuthClientSyncClient.live >+> ZLayer(ReloadingCache.make[ClientsWithPepper](schedule)) >+>
          (OAuthScopeSyncClient.live >+> ZLayer(ReloadingCache.make[Vector[ScopeRecord]](schedule)))))
    syncClients >>> ZLayer.fromFunction(Impl(_, _, _, _, _))
  }

  case class Impl(
      clientCache: ReloadingCache[ClientsWithPepper],
      clientRepository: OAuthClientSyncClient,
      scopeCache: ReloadingCache[Vector[ScopeRecord]],
      scopeRepository: OAuthScopeSyncClient,
      securityService: SecurityService,
  ) extends OAuthConfigurationService:

    private def getClients: UIO[Map[ClientId, OAuthClientRecord]] =
      clientCache.get.map(_.clients)

    def find(id: ClientId): UIO[Option[OAuthClientRecord]] =
      getClients.map(_.get(id))

    private def verifyOneSecret(
        secret: Secret,
        stored: Option[Secret],
    ): Task[Boolean] =
      clientCache.get.map(_.pepper).flatMap: pepper =>
        stored match
          case Some(stored) =>
            val (mac, salt) = stored.splitAt(32)
            securityService.mac(secret = secret, key = salt ++ pepper)
              .map(_.sameElements(mac))

          case None =>
            ZIO.succeed(false)

    override def verifySecret(clientId: ClientId, secret: Option[Secret]): UIO[Option[OAuthClientRecord]] =
      find(clientId).some.foldZIO(
        _ => ZIO.none,
        client =>
          secret match
            case Some(secret) if client.isConfidential =>
              verifyOneSecret(secret, client.secret)
                .flatMap {
                  case false => verifyOneSecret(secret, client.previousSecret)
                  case true => ZIO.succeed(true)
                }
                .map(Option.when(_)(client))
                .orElseSucceed(None)

            case None if client.isPublic =>
              ZIO.some(client)

            case _ =>
              ZIO.none,
      )

    override def getScopes: UIO[Vector[ScopeRecord]] =
      scopeCache.get
