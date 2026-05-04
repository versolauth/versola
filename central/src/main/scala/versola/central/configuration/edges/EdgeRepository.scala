package versola.central.configuration.edges

import zio.Task
import zio.json.ast.Json

trait EdgeRepository:
  def getAll: Task[Vector[EdgeRecord]]

  def find(id: EdgeId): Task[Option[EdgeRecord]]

  def createEdge(id: EdgeId, publicKeyJwk: Json.Obj): Task[Unit]

  def rotateEdgeKey(id: EdgeId, newPublicKeyJwk: Json.Obj): Task[Unit]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  def deleteEdge(id: EdgeId): Task[Unit]
