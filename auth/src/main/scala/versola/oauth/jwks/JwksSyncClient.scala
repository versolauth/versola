package versola.oauth.jwks

import versola.oauth.client.CentralSyncTokenService
import versola.util.{Base64, CacheSource, CoreConfig, JWT, SecurityService}
import zio.http.Request
import zio.json.{DecoderOps, JsonCodec}
import zio.json.ast.Json
import zio.{Task, URLayer, ZIO, ZLayer}

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey}

/** Pulls the JWKS from central (`/configuration/jwks/sync`), together with the private halves
  * of the keys central can sign with (`/configuration/jwks/signing-keys/sync`). Central is
  * the single source of truth; auth caches both for signing and verification.
  *
  * Two endpoints rather than one payload because edge reads the first and has no use for a
  * private key. The private halves arrive encrypted under the shared transport secret
  * `central.secretKey`, the same channel client secrets already travel on.
  */
trait JwksSyncClient extends CacheSource[JwksSyncClient.Keys]

object JwksSyncClient:
  /** `privateKeys` is keyed by `kid`, and holds only the keys central could supply a private
    * half for -- a kid absent here is one this instance cannot sign with.
    */
  case class Keys(publicKeys: JWT.PublicKeys, privateKeys: Map[String, PrivateKey])

  private case class SigningKeysResponse(privateKeys: Map[String, String]) derives JsonCodec

  val live: URLayer[CoreConfig & CentralSyncTokenService & SecurityService, JwksSyncClient] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      config: CoreConfig,
      centralSyncTokenService: CentralSyncTokenService,
      securityService: SecurityService,
  ) extends JwksSyncClient:
    private val JwksURL = config.central.url / "configuration" / "jwks" / "sync"
    private val SigningKeysURL = config.central.url / "configuration" / "jwks" / "signing-keys" / "sync"

    override def getAll: Task[Keys] =
      for
        publicKeys <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(JwksURL))
            .flatMap(_.body.asJsonFromCodec[JWT.PublicKeys])
        encrypted <- ZIO.scoped:
          centralSyncTokenService.syncRequest(Request.get(SigningKeysURL))
            .flatMap(_.body.asJsonFromCodec[SigningKeysResponse])
            .map(_.privateKeys)
        privateKeys <- ZIO.foreach(encrypted)((kid, value) => decrypt(kid, value).map(kid -> _))
      yield Keys(publicKeys, privateKeys)

    /** The key type is taken from the JWK central published for the kid, via the PKCS#8
      * encoding's own algorithm identifier, so a key does not have to be told apart by the
      * algorithm someone claims for it.
      */
    private def decrypt(kid: String, encrypted: String): Task[PrivateKey] =
      (for
        bytes <- ZIO.attempt(Base64.urlDecode(encrypted))
        pkcs8 <- securityService.decryptAes256(bytes, config.central.secretKey)
        key <- ZIO.attempt {
          val spec = PKCS8EncodedKeySpec(pkcs8)
          // PKCS#8 carries the algorithm, but JCA still wants the factory named; both
          // candidates are tried rather than parsing the DER ourselves.
          try KeyFactory.getInstance("RSA").generatePrivate(spec)
          catch case _: Throwable => KeyFactory.getInstance("EC").generatePrivate(spec)
        }
      yield key)
        // The kid is named on every failure, decryption included: a mismatched sync secret
        // and a corrupt DER look the same to an operator who cannot tell which key it was.
        .mapError(cause =>
          RuntimeException(s"Could not read the private key central published for kid '$kid'", cause),
        )
