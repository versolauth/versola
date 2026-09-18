package versola.central.configuration.jwks

import versola.central.CentralConfig
import versola.util.{Base64, CacheSource, JWT, ReloadingCache, Secret, SecurityService}
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.{Scope, Task, UIO, URLayer, ZIO, ZLayer}

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Central is the source of truth for the JWKS, stored in the database and
  * served from a periodically reloaded cache.
  *
  *   - [[getPublicKeys]] serves the parsed keys used to verify admin-console
  *     tokens.
  *   - [[getRaw]] backs the `/configuration/jwks/sync` endpoint used by
  *     auth/edge and the admin console view. It publishes public halves only --
  *     edge verifies and must never receive key material it cannot need.
  *   - [[getSigningKeys]] backs the separate internal endpoint auth alone reads,
  *     carrying the encrypted private halves it needs in order to sign.
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]
  def getRaw: UIO[Json.Obj]

  /** The keys central can sign with, as `kid -> base64url(AES-GCM(PKCS#8))`, encrypted for
    * transport under `secretKey`. Verify-only keys are absent rather than present-and-empty:
    * a caller cannot select what is not here.
    */
  def getSigningKeys: Task[Map[String, String]]

  /** Every stored key with the algorithm it is published under, for the admin console's key
    * list. Carries no private key material -- only whether one exists.
    */
  def listKeys: UIO[Vector[JwksService.KeySummary]]

  def sync(): Task[Unit]
  def createKey(kid: String, jwk: Json.Obj): Task[Unit]
  def updateKey(kid: String, jwk: Json.Obj): Task[Unit]
  def deleteKey(kid: String): Task[Unit]

  /** Generates a keypair for `algorithm`, publishes its public half and stores the private
    * half encrypted, returning the new `kid`. Publishing it does not put it into use -- a
    * tenant has to select it -- which is what makes a rotation safe: every verifier learns
    * the key before anything signs with it.
    */
  def generateKey(algorithm: JWT.Algorithm): Task[String]

object JwksService:
  /** What the console needs to render one row of the key list and decide what may be done
    * with it, without ever transporting the key material itself.
    */
  case class KeySummary(
      kid: String,
      algorithm: Option[String],
      keyType: Option[String],
      curve: Option[String],
      canSign: Boolean,
  ) derives JsonCodec

  case class Error(message: String) extends RuntimeException(message)

  def live: ZLayer[
    JwksRepository & SigningKeyReferences & SecurityService & Scope & CentralConfig,
    Throwable,
    JwksService,
  ] =
    decryptingCacheSource >>>
      (ZLayer.fromZIO:
        ZIO.serviceWithZIO[CentralConfig](config =>
          ReloadingCache.make[Vector[JwksRecord]](config.configurationCacheRefreshInterval),
        )
      ) >>> ZLayer.fromFunction(Impl(_, _, _, _, _))

  /** A [[CacheSource]] that reads the JWKS records from the repository and decrypts their
    * private halves, so the in-memory cache holds plaintext PKCS#8 and no decryption is
    * needed on cache reads.
    */
  private val decryptingCacheSource
      : URLayer[JwksRepository & SecurityService & CentralConfig, CacheSource[Vector[JwksRecord]]] =
    ZLayer.fromFunction: (repository: JwksRepository, securityService: SecurityService, config: CentralConfig) =>
      new CacheSource[Vector[JwksRecord]]:
        override def getAll: Task[Vector[JwksRecord]] =
          repository.getAll.flatMap(ZIO.foreach(_)(decryptPrivateKey(_, securityService, keyEncryptionKey(config))))

  private def keyEncryptionKey(config: CentralConfig): SecretKey =
    SecretKeySpec(config.clientSecretsSecret, "AES")

  /** Decrypts the at-rest encrypted private half of a JWKS record. */
  private def decryptPrivateKey(
      record: JwksRecord,
      securityService: SecurityService,
      key: SecretKey,
  ): Task[JwksRecord] =
    ZIO.foreach(record.privateKey)(stored => securityService.decryptAes256(stored, key).map(Secret(_)))
      .map(privateKey => record.copy(privateKey = privateKey))

  private def toJwks(records: Vector[JwksRecord]): Json.Obj =
    Json.Obj("keys" -> Json.Arr(records.map(_.jwk)*))

  case class Impl(
      cache: ReloadingCache[Vector[JwksRecord]],
      repository: JwksRepository,
      references: SigningKeyReferences,
      securityService: SecurityService,
      config: CentralConfig,
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] =
      cache.get.map(records => JWT.PublicKeys.fromJson(toJwks(records)))

    override def getRaw: UIO[Json.Obj] =
      cache.get.map(toJwks)

    /** Encrypted under the transport secret on the way out rather than shipped as the database
      * ciphertext, the same way client and resource secrets already reach auth over this
      * channel: the at-rest key stays inside central.
      */
    override def getSigningKeys: Task[Map[String, String]] =
      cache.get.flatMap { records =>
        val signable = records.collect {
          case record if record.canSign => record.kid -> record.privateKey.get
        }.toMap
        ZIO.foreach(signable) { (kid, pkcs8) =>
          securityService.encryptAes256(pkcs8, config.secretKey).map(kid -> Base64.urlEncode(_))
        }
      }

    override def listKeys: UIO[Vector[KeySummary]] =
      cache.get.map(_.map { record =>
        def field(name: String): Option[String] =
          record.jwk.fields.collectFirst { case (`name`, Json.Str(value)) => value }

        KeySummary(
          kid = record.kid,
          algorithm = record.algorithm.map(_.toString),
          keyType = field("kty"),
          curve = field("crv"),
          canSign = record.canSign,
        )
      })

    override def sync(): Task[Unit] =
      repository.getAll
        .flatMap(ZIO.foreach(_)(decryptPrivateKey(_, securityService, secretsKey)))
        .flatMap(cache.set)

    override def createKey(kid: String, jwk: Json.Obj): Task[Unit] =
      repository.create(kid, jwk, privateKey = None)

    override def updateKey(kid: String, jwk: Json.Obj): Task[Unit] =
      repository.update(kid, jwk)

    /** Refuses while any tenant still signs with the key. Retiring a key is the last step of
      * a rotation, after the tenants on it have moved to the new one; deleting it first would
      * leave them signing with a kid that is no longer published, so nothing could verify
      * what they issue.
      */
    override def deleteKey(kid: String): Task[Unit] =
      references.tenantsSigningWith(kid).flatMap { tenants =>
        if tenants.isEmpty then repository.delete(kid)
        else
          ZIO.fail(Error(
            s"Key '$kid' is the signing key of ${tenants.mkString(", ")}. " +
              "Select another key for them before deleting it.",
          ))
      }

    override def generateKey(algorithm: JWT.Algorithm): Task[String] =
      for
        generated <- JwksKeyGeneration.generate(securityService, algorithm, secretsKey)
        _ <- repository.create(generated.kid, generated.jwk, Some(generated.privateKey))
        // Published immediately, so every verifier has learned the key before any tenant is
        // moved onto it -- the first of the two steps a safe rotation needs.
        _ <- sync()
      yield generated.kid

    private val secretsKey: SecretKey = JwksService.keyEncryptionKey(config)

/** Who is currently signing with a given key. Lives behind its own interface so
  * [[JwksService]] can refuse to delete a key in use without depending on the whole of
  * challenge settings (which in turn validates kids against the JWKS).
  */
trait SigningKeyReferences:
  def tenantsSigningWith(kid: String): UIO[Vector[String]]
