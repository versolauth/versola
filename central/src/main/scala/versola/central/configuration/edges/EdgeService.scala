package versola.central.configuration.edges

import versola.util.{RsaKeyPair, SecurityService}
import zio.{Task, ZIO, ZLayer}

trait EdgeService:
  def getAllEdges: Task[Vector[EdgeRecord]]

  def registerEdge(id: EdgeId): Task[RsaKeyPair]

  def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  def deleteEdge(id: EdgeId): Task[Unit]

object EdgeService:
  def live: ZLayer[EdgeRepository & SecurityService, Nothing, EdgeService] =
    ZLayer.fromFunction(Impl(_, _))

  case class Impl(
      edgeRepository: EdgeRepository,
      securityService: SecurityService,
  ) extends EdgeService:

    override def getAllEdges: Task[Vector[EdgeRecord]] =
      edgeRepository.getAll

    override def registerEdge(id: EdgeId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.createEdge(id, keyPair.toPublicJwk)
      yield keyPair

    override def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.rotateEdgeKey(id, keyPair.toPublicJwk)
      yield keyPair

    override def deleteOldEdgeKey(id: EdgeId): Task[Unit] =
      edgeRepository.deleteOldEdgeKey(id)

    override def deleteEdge(id: EdgeId): Task[Unit] =
      edgeRepository.deleteEdge(id)
