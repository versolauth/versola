package versola.central.configuration.edges

import versola.util.CacheSource
import versola.util.http.OverloadPolicy
import zio.Task
import zio.json.ast.Json

trait EdgeRepository extends CacheSource[Vector[EdgeRecord]]:
  def getAll: Task[Vector[EdgeRecord]]

  def find(id: EdgeId): Task[Option[EdgeRecord]]

  def createEdge(id: EdgeId, publicKeyJwk: Json.Obj, requireDpopNonce: Boolean, groupId: EdgeGroupId): Task[Unit]

  def rotateEdgeKey(id: EdgeId, newPublicKeyJwk: Json.Obj): Task[Unit]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit]

  /** `None` clears the policy: the edge goes back to the values of its own deployment. */
  def setOverloadPolicy(id: EdgeId, policy: Option[OverloadPolicy]): Task[Unit]

  def deleteEdge(id: EdgeId): Task[Unit]
