package versola.central.audit

import versola.central.configuration.edges.{EdgeGroup, EdgeGroupId, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.edges.{EdgePoliciesPatch, PolicyPatchError}
import versola.util.RsaKeyPair
import versola.util.audit.{ActorParams, ActorSignature}
import zio.*
import zio.http.Request

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/** What a spec of a route behind the audit hook needs of an edge: a registered first-party edge with a real key, and the signature
  * edge adds to a call that states an actor, made with the code edge uses. */
object TestActor:
  val edgeId: EdgeId = EdgeId("edge-a")

  private def generate(keyId: String): RsaKeyPair =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    RsaKeyPair(keyId = keyId, publicKey = pair.getPublic.asInstanceOf[RSAPublicKey], privateKey = pair.getPrivate.asInstanceOf[RSAPrivateKey])

  val edgeKeys: RsaKeyPair = generate("edge-a-key")

  /** A key the registration does not hold. */
  val otherKeys: RsaKeyPair = generate("edge-a-key")

  def record(kind: EdgeGroupKind): EdgeRecord = EdgeRecord(
    id = edgeId,
    publicKey = edgeKeys.toPublicJwk,
    oldPublicKey = None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = EdgeGroup.DefaultId,
    groupKind = kind,
  )

  /** The registered edges, as central's cache answers for them. Nothing else of the service is used by the hook. */
  def edges(registered: Option[EdgeRecord]): EdgeService = new EdgeService:
    private def unused = ZIO.dieMessage("not used by the audit hook")
    override def getAllEdges: UIO[Vector[EdgeRecord]] = ZIO.succeed(registered.toVector)
    override def find(id: EdgeId): UIO[Option[EdgeRecord]] = ZIO.succeed(registered.filter(_.id == id))
    override def registerEdge(id: EdgeId, groupId: EdgeGroupId): Task[RsaKeyPair] = unused
    override def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair] = unused
    override def deleteOldEdgeKey(id: EdgeId): Task[Unit] = unused
    override def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit] = unused
    override def findFresh(id: EdgeId): Task[Option[EdgeRecord]] = unused
    override def patchPolicies(
        id: EdgeId,
        patch: EdgePoliciesPatch,
        dryRun: Boolean,
    ): Task[Option[Either[PolicyPatchError, EdgeRecord]]] = unused
    override def deleteEdge(id: EdgeId): Task[Unit] = unused
    override def sync(): Task[Unit] = unused

  val registered: EdgeService = edges(Some(record(EdgeGroupKind.FirstParty)))

  /** `request` as edge forwards it: the signature of the edge, made with `keys`, added to the call as it is. */
  def signed(request: Request, keys: RsaKeyPair = edgeKeys, edge: EdgeId = edgeId): UIO[Request] =
    ActorSignature.sign(
      edgeId = edge.toString,
      keyId = keys.keyId,
      privateKey = keys.privateKey,
      method = request.method.name,
      path = ActorSignature.pathOf(request.url.path.segments),
      query = request.url.queryParams,
    ).orDie.map(token => request.updateURL(_.addQueryParam(ActorParams.Signature, token)))
