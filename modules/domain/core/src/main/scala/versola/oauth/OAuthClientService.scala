package versola.oauth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import versola.oauth.model.{ClientId, ClientSecret, OAuthClient, Scope, ScopeRecord, ScopeToken}
import versola.util.{Argon2Hash, Argon2Salt, ReloadingCache, SecureRandom}
import zio.*
import zio.prelude.NonEmptySet

trait OauthClientService:
  /** Returns all clients from in-memory cache **/
  def getAll: Task[Map[ClientId, OAuthClient]]

  /** Returns all clients from in-memory cache **/
  def getAllCached: UIO[Map[ClientId, OAuthClient]]

  /** Searches a client in in-memory cache */
  def find(id: ClientId): UIO[Option[OAuthClient]]

  /** Validate a client secret against both active and previous secrets */
  def verifySecret(id: ClientId, providedSecret: Option[ClientSecret]): Task[Boolean]

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

object OauthClientService:
  case class Impl(
      cache: ReloadingCache[Map[ClientId, OAuthClient]],
      repository: OAuthClientRepository,
      scopeCache: ReloadingCache[Map[ScopeToken, Scope]],
      scopeRepository: OAuthScopeRepository,
      secureRandom: SecureRandom,
  ) extends OauthClientService:

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
        (hash, salt) <- hashSecret(secret)
        client = OAuthClient(
          id = id,
          clientName = clientName,
          redirectUris = redirectUris,
          scope = scope,
          secretHash = Some(hash),
          secretSalt = Some(salt),
          previousSecretHash = None,
          previousSecretSalt = None,
        )
        _ <- repository.create(client)
      yield secret

    private def verifyOneSecret(
        secret: ClientSecret,
        hash: Option[Argon2Hash],
        salt: Option[Argon2Salt],
    ): Task[Boolean] =
      hash.zip(salt) match
        case Some((hash, salt)) =>
          ZIO.attemptBlocking(java.util.Arrays.equals(computeArgon2Hash(secret, salt), hash))
        case None =>
          ZIO.succeed(false)

    override def verifySecret(clientId: ClientId, secret: Option[ClientSecret]): Task[Boolean] =
      find(clientId).some.foldZIO(
        _ => ZIO.succeed(false),
        client =>
          secret match
            case Some(secret) if client.isConfidential =>
              val firstSecretValid = verifyOneSecret(secret, client.secretHash, client.secretSalt)
              ZIO.whenZIO(firstSecretValid)(ZIO.succeed(true))
                .someOrElseZIO(verifyOneSecret(secret, client.previousSecretHash, client.previousSecretSalt))
            case None if client.isPublic =>
              ZIO.succeed(true)
            case _ =>
              ZIO.succeed(false),
      )
    end verifySecret

    private def hashSecret(secret: ClientSecret): Task[(Argon2Hash, Argon2Salt)] =
      for
        salt <- secureRandom.nextBytes(16).map(Argon2Salt(_))
        hash <- ZIO.attempt(computeArgon2Hash(secret, salt))
      yield (hash, salt)

    override def rotateSecret(clientId: ClientId): Task[ClientSecret] =
      for
        secret <- generateSecret
        (hash, salt) <- hashSecret(secret)
        _ <- repository.rotateSecret(clientId, hash, salt)
      yield secret

    override def deletePreviousSecret(clientId: ClientId): Task[Unit] =
      repository.deletePreviousSecret(clientId)

    private def generateSecret: Task[ClientSecret] =
      secureRandom.nextHex(32).map(ClientSecret(_))

    private def createArgon2Parameters(salt: Array[Byte]): Argon2Parameters =
      new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(2)
        .withMemoryAsKB(19 * 1024) // 19 MB
        .withParallelism(1)
        .withSalt(salt)
        .build()

    private def computeArgon2Hash(secret: ClientSecret, salt: Array[Byte]): Argon2Hash =
      val secretBytes = secret.getBytes
      val params = createArgon2Parameters(salt)

      val generator = new Argon2BytesGenerator()
      generator.init(params)

      val hash = new Array[Byte](32)
      generator.generateBytes(secretBytes, hash)
      Argon2Hash(hash)

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
