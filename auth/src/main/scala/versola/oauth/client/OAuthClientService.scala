package versola.oauth.client

import versola.oauth.client.model.{AccessTokenType, ClientId, ClientSecret, OAuthClientRecord, ScopeRecord, ScopeToken}
import versola.util.{CoreConfig, ReloadingCache, Secret, SecureRandom, SecurityService}
import zio.*
import zio.prelude.NonEmptySet

trait OAuthClientService:
  /** Returns all clients from database **/
  def getAll: Task[Map[ClientId, OAuthClientRecord]]

  /** Returns all clients from in-memory cache **/
  def getAllCached: UIO[Map[ClientId, OAuthClientRecord]]

  /** Searches a client in in-memory cache */
  def findCached(id: ClientId): UIO[Option[OAuthClientRecord]]

  /** Validate a client secret against both active and previous secrets */
  def verifySecret(id: ClientId, providedSecret: Option[Secret]): UIO[Option[OAuthClientRecord]]

  /** Create a private client with a generated secret and return both the client and the plain secret */
  def register(
      id: ClientId,
      clientName: String,
      redirectUris: NonEmptySet[String],
      allowedScopes: Set[String],
  ): Task[Secret]

  /** Rotate a client secret: generate new secret, update client, return new secret */
  def rotateSecret(clientId: ClientId): Task[Secret]

  /** Delete the previous secret for a client */
  def deletePreviousSecret(clientId: ClientId): Task[Unit]

  /** Delete multiple clients by ID */
  def deleteClients(clientIds: Vector[ClientId]): IO[Throwable, Unit]

  /** Get all available scopes */
  def getAllScopes: Task[Map[ScopeToken, model.Scope]]

  /** Get all available scopes from in-memory cache */
  def getAllScopesCached: UIO[Map[ScopeToken, model.Scope]]

  /** Register multiple scopes */
  def registerScopes(scopes: Vector[(ScopeToken, model.Scope)]): IO[Throwable, Unit]

  /** Delete multiple scopes */
  def deleteScopes(names: Vector[ScopeToken]): IO[Throwable, Unit]

object OAuthClientService:
  case class Impl(
      cache: ReloadingCache[Map[ClientId, OAuthClientRecord]],
      repository: OAuthClientRepository,
      scopeCache: ReloadingCache[Map[ScopeToken, model.Scope]],
      scopeRepository: OAuthScopeRepository,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      clientSecretsConfig: CoreConfig.Security.ClientSecrets,
  ) extends OAuthClientService:

    def getAll: Task[Map[ClientId, OAuthClientRecord]] =
      repository.getAll

    override def getAllCached: UIO[Map[ClientId, OAuthClientRecord]] =
      cache.get

    def findCached(id: ClientId): UIO[Option[OAuthClientRecord]] =
      getAllCached.map(_.get(id))

    /** Create a private client with a generated secret and return both the client and the plain secret */
    override def register(
        id: ClientId,
        clientName: String,
        redirectUris: NonEmptySet[String],
        scope: Set[String],
    ): Task[Secret] =
      for
        secret <- generateSecret
        macWithSalt <- generateMacWithSalt(secret)
        client = OAuthClientRecord(
          id = id,
          clientName = clientName,
          redirectUris = redirectUris,
          scope = scope,
          secret = Some(macWithSalt),
          previousSecret = None,
          accessTokenTtl = 10.minutes,
          accessTokenType = AccessTokenType.Opaque,
        )
        _ <- repository.create(client)
      yield secret

    private def verifyOneSecret(
        secret: Secret,
        stored: Option[Secret],
    ): Task[Boolean] =
      stored match
        case Some(stored) =>
          val (mac, salt) = stored.splitAt(32)
          securityService.macBlake3(
            secret = secret,
            key = salt ++ clientSecretsConfig.pepper,
          )
            .map(_.sameElements(mac))

        case None =>
          ZIO.succeed(false)

    override def verifySecret(clientId: ClientId, secret: Option[Secret]): UIO[Option[OAuthClientRecord]] =
      findCached(clientId).some.foldZIO(
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

    private def generateMacWithSalt(secret: Secret): Task[Secret] =
      for
        salt <- secureRandom.nextBytes(16)
        mac <- securityService.macBlake3(secret, salt ++ clientSecretsConfig.pepper)
      yield Secret(mac ++ salt)

    override def rotateSecret(clientId: ClientId): Task[Secret] =
      for
        secret <- generateSecret
        macWithSalt <- generateMacWithSalt(secret)
        _ <- repository.rotateSecret(clientId, macWithSalt)
      yield secret

    override def deletePreviousSecret(clientId: ClientId): Task[Unit] =
      repository.deletePreviousSecret(clientId)

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    override def getAllScopes: Task[Map[ScopeToken, model.Scope]] =
      scopeRepository.getAll

    override def getAllScopesCached: UIO[Map[ScopeToken, model.Scope]] =
      scopeCache.get

    override def registerScopes(scopes: Vector[(ScopeToken, model.Scope)]): IO[Throwable, Unit] =
      val scopeRecords = scopes.map: (name, scope) =>
        ScopeRecord(name, scope.description, scope.claims)
      scopeRepository.createOrUpdate(scopeRecords)

    override def deleteScopes(names: Vector[ScopeToken]): IO[Throwable, Unit] =
      scopeRepository.delete(names)

    override def deleteClients(clientIds: Vector[ClientId]): IO[Throwable, Unit] =
      repository.delete(clientIds)
