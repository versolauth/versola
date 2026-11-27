package versola.central

import versola.central.configuration.edges.{EdgeGroup, EdgeGroupKind, EdgeId, EdgeRecord}
import versola.central.configuration.topology.{CellId, CellRecord, CellRegistry, CellState, RegionId}
import versola.util.{Base64Url, JWT, RsaKeyPair, Secret}
import zio.durationInt
import zio.http.{Header, URL}
import zio.json.ast.Json
import zio.{Duration, Task, UIO, ZIO}

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object TestCentralConfig:
  /** A fresh 2048-bit RSA key pair, the way an `auth`, an edge or central have their own. */
  def newKeyPair(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = keyId, publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  /** The key pair of the `auth` the test configuration names (`auth.public-keys`): what it signs its calls to central with. */
  val authKeyPair: RsaKeyPair = newKeyPair("auth-test")

  /** Central's own service key. */
  val serviceKeyPair: RsaKeyPair = newKeyPair("central-test")

  /** A key central is configured with for no `auth`: what a caller that is nobody's signs with. */
  val foreignKeyPair: RsaKeyPair = newKeyPair("foreign-test")

  val authConfig = CentralConfig.AuthConfig(
    url = URL.decode("http://localhost:9001").toOption.get,
    publicKeys = List(authKeyPair.toPublicJwk),
  )

  val userOutboxConfig = CentralConfig.UserOutboxConfig(
    pollInterval = Duration.fromSeconds(1),
    batchSize = 32,
    lease = Duration.fromSeconds(60),
    maxBackoff = Duration.fromSeconds(300),
    maxAttempts = 10,
  )

  val edgeSecret: Secret = Secret(Array.fill(32)(1.toByte))

  val basicAuthHeader: Header.Authorization =
    Header.Authorization.Basic("central", Base64Url.encode(edgeSecret))

  val config = CentralConfig(
    bootstrap = None,
    clientSecretsSecret = Secret(Array.fill(16)(5.toByte)),
    serviceKey = CentralConfig.ServiceKey(keyId = serviceKeyPair.keyId, privateKey = serviceKeyPair.privateKey),
    auth = authConfig,
    userOutbox = userOutboxConfig,
    configurationCacheRefreshInterval = 5.minutes,
  )

  /** What the `auth` the test configuration names signs its tokens with. */
  val authSignature: JWT.Signature =
    JWT.Signature.Asymmetric(JWT.Algorithm.RS256, authKeyPair.keyId, authKeyPair.privateKey)

  /** A signature by a key that nobody registered. */
  val foreignSignature: JWT.Signature =
    JWT.Signature.Asymmetric(JWT.Algorithm.RS256, foreignKeyPair.keyId, foreignKeyPair.privateKey)

  /** The claims of a token an `auth` sends central. */
  val authClaims: JWT.Claims = JWT.Claims("auth", "auth", List("central"), Json.Obj())

  /** The token the configured `auth` presents. */
  val authToken: Task[String] = JWT.serialize(authClaims, 1.minute, authSignature)

  /** The registry of an installation with no cell registered. */
  val noCells: CellRegistry = registryOf()

  /** A registry of exactly these cells, in memory. */
  def registryOf(cells: CellRecord*): CellRegistry =
    new CellRegistry:
      override def all: UIO[Vector[CellRecord]] = ZIO.succeed(cells.toVector)
      override def refreshNow: Task[Unit] = ZIO.unit

  /** A registered cell with these keys, in a region of its own. */
  def cellRecord(id: String, keyPairs: RsaKeyPair*): CellRecord =
    CellRecord(
      id = CellId(id),
      regionId = RegionId("kz"),
      internalUrl = "http://localhost:9002",
      mtlsUrl = None,
      trustedCertificates = Nil,
      state = CellState.Open,
      weight = 100,
      publicKeys = keyPairs.map(_.toPublicJwk).toList,
    )

  /** The token an `auth` presents, signed with `keyPair`, as the `auth` of cell `cellId` says it is when given. */
  def authTokenBy(keyPair: RsaKeyPair, cellId: Option[String] = None, claims: JWT.Claims = authClaims): Task[String] =
    JWT.serialize(
      claims = claims,
      ttl = 1.minute,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, keyPair.keyId, keyPair.privateKey),
      headers = cellId.fold(Map.empty[String, String])(id => Map("cell_id" -> id)),
    )

  /** A registered edge and the token it signs for central: RS256 with its key, `edge_id` in the header, `iss` `edge`, `aud` `central`
    * and an expiry, which is what an edge's `CentralSyncTokenService` makes. Which routes refuse it is what a spec using it checks. */
  val edgeKeyPair: RsaKeyPair = newKeyPair("edge-test")
  val edgeId: EdgeId = EdgeId("edge-test-1")

  val edgeRecord: EdgeRecord = EdgeRecord(
    edgeId,
    Some(edgeKeyPair.toPublicJwk),
    None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = EdgeGroup.DefaultId,
    groupKind = EdgeGroupKind.FirstParty,
    publicUrl = None,
  )

  val edgeToken: Task[String] =
    JWT.serialize(
      claims = JWT.Claims("edge", "edge", List("central"), Json.Obj()),
      ttl = 1.minute,
      signature = JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKeyPair.keyId, edgeKeyPair.privateKey),
      headers = Map("edge_id" -> edgeId.toString),
    )
