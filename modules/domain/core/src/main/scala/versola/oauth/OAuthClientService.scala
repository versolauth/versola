package versola.oauth

import versola.oauth.model.{ClientId, ClientSecret, OAuthClient, Scope, ScopeRecord, ScopeToken}
import versola.security.{Secret, SecureRandom, SecurityService}
import versola.util.{CoreConfig, ReloadingCache}
import zio.*
import zio.prelude.NonEmptySet

trait OAuthClientService:
  /** Returns all clients from in-memory cache **/
  def getAll: Task[Map[ClientId, OAuthClient]]

  /** Returns all clients from in-memory cache **/
  def getAllCached: UIO[Map[ClientId, OAuthClient]]

  /** Searches a client in in-memory cache */
  def find(id: ClientId): UIO[Option[OAuthClient]]

  /** Validate a client secret against both active and previous secrets */
  def verifySecret(id: ClientId, providedSecret: Option[ClientSecret]): Task[Option[OAuthClient]]

  /** Create a private client with a generated secret and return both the client and the plain secret */
  def register(
      id: ClientId,
      clientName: String,
      redirectUris: NonEmptySet[String],
      allowedScopes: Set[String],
  ): Task[ClientSecret]

  /** Rotate a client secret: generate new secret, update client, return new secret */
  def rotateSecret(clientId: ClientId): Task[ClientSecret]

  /** Delete the previous secret for a client */
  def deletePreviousSecret(clientId: ClientId): Task[Unit]

  /** Delete multiple clients by ID */
  def deleteClients(clientIds: Vector[ClientId]): IO[Throwable, Unit]

  /** Get all available scopes */
  def getAllScopes: Task[Map[ScopeToken, Scope]]

  /** Get all available scopes from in-memory cache */
  def getAllScopesCached: UIO[Map[ScopeToken, Scope]]

  /** Register multiple scopes */
  def registerScopes(scopes: Vector[(ScopeToken, Scope)]): IO[Throwable, Unit]

  /** Delete multiple scopes */
  def deleteScopes(names: Vector[ScopeToken]): IO[Throwable, Unit]

object OAuthClientService:
  case class Impl(
      cache: ReloadingCache[Map[ClientId, OAuthClient]],
      repository: OAuthClientRepository,
      scopeCache: ReloadingCache[Map[ScopeToken, Scope]],
      scopeRepository: OAuthScopeRepository,
      secureRandom: SecureRandom,
      securityService: SecurityService,
      clientSecretsConfig: CoreConfig.Security.ClientSecrets,
  ) extends OAuthClientService:

    def getAll: Task[Map[ClientId, OAuthClient]] =
      repository.getAll

    override def getAllCached: UIO[Map[ClientId, OAuthClient]] =
      cache.get

    def find(id: ClientId): UIO[Option[OAuthClient]] =
      getAllCached.map(_.get(id))

    /** Create a private client with a generated secret and return both the client and the plain secret */
    override def register(
        id: ClientId,
        clientName: String,
        redirectUris: NonEmptySet[String],
        scope: Set[String],
    ): Task[ClientSecret] =
      for
        secret <- generateSecret
        macWithSalt <- generateMacWithSalt(secret)
        client = OAuthClient(
          id = id,
          clientName = clientName,
          redirectUris = redirectUris,
          scope = scope,
          secret = Some(macWithSalt),
          previousSecret = None,
        )
        _ <- repository.create(client)
      yield ClientSecret.fromBytes(secret)

    private def verifyOneSecret(
        secret: ClientSecret,
        stored: Option[Secret],
    ): Task[Boolean] =
      stored match
        case Some(stored) =>
          val (mac, salt) = stored.splitAt(32)
          securityService.macBlake3(
            secret = Secret.fromBase64Url(secret),
            key = salt ++ clientSecretsConfig.pepper,
          )
            .map(_.sameElements(mac))

        case None =>
          ZIO.succeed(false)

    override def verifySecret(clientId: ClientId, secret: Option[ClientSecret]): Task[Option[OAuthClient]] =
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

    override def rotateSecret(clientId: ClientId): Task[ClientSecret] =
      for
        secret <- generateSecret
        macWithSalt <- generateMacWithSalt(secret)
        _ <- repository.rotateSecret(clientId, macWithSalt)
      yield ClientSecret.fromBytes(secret)

    override def deletePreviousSecret(clientId: ClientId): Task[Unit] =
      repository.deletePreviousSecret(clientId)

    private def generateSecret: Task[Secret] =
      secureRandom.nextBytes(32).map(Secret(_))

    override def getAllScopes: Task[Map[ScopeToken, Scope]] =
      scopeRepository.getAll

    override def getAllScopesCached: UIO[Map[ScopeToken, Scope]] =
      scopeCache.get

    override def registerScopes(scopes: Vector[(ScopeToken, Scope)]): IO[Throwable, Unit] =
      val scopeRecords = scopes.map: (name, scope) =>
        ScopeRecord(name, scope.description, scope.claims)
      scopeRepository.createOrUpdate(scopeRecords)

    override def deleteScopes(names: Vector[ScopeToken]): IO[Throwable, Unit] =
      scopeRepository.delete(names)

    override def deleteClients(clientIds: Vector[ClientId]): IO[Throwable, Unit] =
      repository.delete(clientIds)
