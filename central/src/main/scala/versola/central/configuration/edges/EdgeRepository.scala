package versola.central.configuration.edges

import versola.central.configuration.partners.PartnerEdgeLimitReached
import versola.util.CacheSource
import zio.json.ast.Json
import zio.{IO, Task}

trait EdgeRepository extends CacheSource[Vector[EdgeRecord]]:
  def getAll: Task[Vector[EdgeRecord]]

  def find(id: EdgeId): Task[Option[EdgeRecord]]

  /** `publicKeyJwk` is `None` for a partner's edge, which brings its own key when it enrols ([[setPublicKey]]). */
  /** Stores the edge. When `groupId` is a partner's group, the partner's row is locked and the group's edges counted in the same
    * transaction, so that the partner's `maxEdges` holds however many are created at once: [[PartnerEdgeLimitReached]]. */
  def createEdge(
      id: EdgeId,
      publicKeyJwk: Option[Json.Obj],
      requireDpopNonce: Boolean,
      groupId: EdgeGroupId,
      publicUrl: Option[String],
  ): IO[PartnerEdgeLimitReached | Throwable, Unit]

  def rotateEdgeKey(id: EdgeId, newPublicKeyJwk: Json.Obj): Task[Unit]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  /** The first key of an edge registered without one, in one statement: `false` when the edge has a key already (or is not there),
    * so two enrolments cannot both register one. */
  def setPublicKey(id: EdgeId, publicKeyJwk: Json.Obj): Task[Boolean]

  /** The key of an edge, replaced; the key before it stays valid as the old key (as [[rotateEdgeKey]] leaves it) until the next
    * replacement, so that a request signed with it just before the edge switched is not refused: what an edge that renews its certificate
    * with a new key of its own is left with. `false`: no such edge. */
  def replacePublicKey(id: EdgeId, publicKeyJwk: Json.Obj): Task[Boolean]

  def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit]

  /** Changes the policy sections of one edge under the row's lock: `change` is given the record as it is now and answers the record to
    * store, or why not (nothing is written then). `None`: no such edge. Two patches of one edge are applied one after the other, each
    * to the result of the previous one, so a revision checked in `change` is the revision that is replaced.
    */
  def patchPolicies(
      id: EdgeId,
      change: EdgeRecord => Either[PolicyPatchError, EdgeRecord],
  ): Task[Option[Either[PolicyPatchError, EdgeRecord]]]

  def deleteEdge(id: EdgeId): Task[Unit]
