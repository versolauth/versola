package versola.e2e.support

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWK, KeyUse, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.io.File
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.security.{KeyFactory, KeyPairGenerator}
import java.util.{Base64, Date, UUID}

/** The keys that tell central and an `auth` who is calling (versolauth/versola#1050): a cell has a key pair of its own,
  * and central has its service key. The staged ones are read from the `env.conf` files `scripts/gen-env.scala` wrote
  * (`central.key-id` and `central.private-key` in auth's, `service-key.key-id` and `service-key.private-key` in
  * central's, the encoding the generator uses: the standard base64 of the PKCS#8 form).
  */
object CellIdentity:

  /** A key pair as the services are given it: the private half and the kid it is published under. */
  final case class ServiceKey(keyId: String, privateKey: RSAPrivateCrtKey):
    /** What the configuration holds under `private-key`. */
    def encoded: String = Base64.getEncoder.encodeToString(privateKey.getEncoded)

    private def publicKey: RSAPublicKey =
      KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.getModulus, privateKey.getPublicExponent)).asInstanceOf[RSAPublicKey]

    /** The public JWK a cell is registered with (`publicKeys`) and central trusts, with this kid. */
    def publicJwk: Task[Json.Obj] =
      ZIO.attempt:
        val jwk = RSAKey.Builder(publicKey).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build()
        jwk.toPublicJWK.toJSONString.fromJson[Json.Obj].left.map(RuntimeException(_)).toTry.get

    /** RS256, as an `auth` signs its calls to central with: `iss` auth, `aud` central. `cellId` goes in the `cell_id` header. */
    def authToken(cellId: Option[String]): Task[String] =
      val base = JWSHeader.Builder(JWSAlgorithm.RS256).`type`(JOSEObjectType.JWT).keyID(keyId)
      sign(
        cellId.fold(base)(id => base.customParam("cell_id", id)).build(),
        claims(issuer = "auth", subject = cellId.getOrElse("auth"), audience = "central"),
      )

    /** RS256, as central signs its calls to an `auth` with: `iss` and `sub` central, `aud` the cell id of the auth called. */
    def centralToken(audience: String): Task[String] =
      sign(
        JWSHeader.Builder(JWSAlgorithm.RS256).`type`(JOSEObjectType.JWT).keyID(keyId).build(),
        claims(issuer = "central", subject = "central", audience = audience),
      )

    private def sign(header: JWSHeader, claims: JWTClaimsSet): Task[String] =
      ZIO.attemptBlocking:
        val jwt = SignedJWT(header, claims)
        jwt.sign(RSASSASigner(privateKey))
        jwt.serialize()

  private def claims(issuer: String, subject: String, audience: String): JWTClaimsSet =
    JWTClaimsSet.Builder()
      .jwtID(UUID.randomUUID().toString).issuer(issuer).subject(subject).audience(audience)
      .issueTime(Date())
      .expirationTime(Date.from(java.time.Instant.now.plusSeconds(600)))
      .build()

  /** A key of its own, 2048 bits like the ones the generator makes, under a kid no other key has. */
  def generate(keyId: String): Task[ServiceKey] =
    ZIO.attemptBlocking:
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      ServiceKey(keyId, generator.generateKeyPair().getPrivate.asInstanceOf[RSAPrivateCrtKey])

  /** The key `keyIdPath` and `privateKeyPath` name in `envConf`, as the generator wrote it. */
  private def read(envConf: String, keyIdPath: String, privateKeyPath: String): Task[ServiceKey] =
    ZIO.attemptBlocking:
      val conf = com.typesafe.config.ConfigFactory.parseFile(File(envConf)).resolve()
      val standard = conf.getString(privateKeyPath).filterNot(_.isWhitespace).replace('-', '+').replace('_', '/')
      val bytes = Base64.getDecoder.decode(standard + "=" * ((4 - standard.length % 4) % 4))
      val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)).asInstanceOf[RSAPrivateCrtKey]
      ServiceKey(conf.getString(keyIdPath), key)

  /** The staged `auth`'s own key (`central.private-key` of its `env.conf`): the one central trusts for the first cell. */
  def stagedAuth(config: E2EConfig): Task[ServiceKey] =
    read(config.authEnvConf, "central.key-id", "central.private-key")

  /** Central's service key (`service-key.private-key` of the staged central's `env.conf`, `CENTRAL_ENV_CONF`): what a spec
    * signs with to be central at an `auth`. */
  def stagedCentral(config: E2EConfig): Task[ServiceKey] =
    read(config.centralEnvConf, "service-key.key-id", "service-key.private-key")
