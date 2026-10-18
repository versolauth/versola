package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.util.http.BadRequest
import versola.util.{ReloadingCache, RsaKeyPair, SecurityService}
import zio.{Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait EdgeService:
  def getAllEdges: UIO[Vector[EdgeRecord]]

  def find(id: EdgeId): UIO[Option[EdgeRecord]]

  def registerEdge(id: EdgeId): Task[RsaKeyPair]

  def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  /** Pins the client certificate the edge presents to central's mutual-TLS listener, keeping
    * the previous one valid until [[deleteOldCertificate]]. Fails with `BadRequest` if the
    * PEM is not one certificate. */
  def rotateCertificate(id: EdgeId, pemCertificate: String): Task[Unit]

  def deleteOldCertificate(id: EdgeId): Task[Unit]

  def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit]

  def deleteEdge(id: EdgeId): Task[Unit]

  def sync(): Task[Unit]

object EdgeService:
  def live: ZLayer[EdgeRepository & SecurityService & Scope & CentralConfig, Throwable, EdgeService] =
    (ZLayer.fromZIO:
      ZIO.serviceWithZIO[CentralConfig](config =>
        ReloadingCache.make[Vector[EdgeRecord]](config.configurationCacheRefreshInterval),
      )
    )
      >>> ZLayer.fromFunction(Impl(_, _, _))

  case class Impl(
      cache: ReloadingCache[Vector[EdgeRecord]],
      edgeRepository: EdgeRepository,
      securityService: SecurityService,
  ) extends EdgeService:

    override def getAllEdges: UIO[Vector[EdgeRecord]] =
      cache.get.map(_.sortBy(_.id))

    override def find(id: EdgeId): UIO[Option[EdgeRecord]] =
      cache.get.map(_.find(_.id == id))

    override def registerEdge(id: EdgeId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.createEdge(id, keyPair.toPublicJwk, EdgeRecord.DefaultRequireDpopNonce)
      yield keyPair

    override def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.rotateEdgeKey(id, keyPair.toPublicJwk)
      yield keyPair

    override def deleteOldEdgeKey(id: EdgeId): Task[Unit] =
      edgeRepository.deleteOldEdgeKey(id)

    override def rotateCertificate(id: EdgeId, pemCertificate: String): Task[Unit] =
      for
        thumbprint <- ZIO.fromEither(EdgeCertificate.thumbprintOfPem(pemCertificate)).mapError(BadRequest(_))
        _ <- edgeRepository.rotateCertificate(id, thumbprint)
      yield ()

    override def deleteOldCertificate(id: EdgeId): Task[Unit] =
      edgeRepository.deleteOldCertificate(id)

    override def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit] =
      edgeRepository.setRequireDpopNonce(id, requireDpopNonce)

    override def deleteEdge(id: EdgeId): Task[Unit] =
      edgeRepository.deleteEdge(id)

    override def sync(): Task[Unit] =
      for
        edges <- edgeRepository.getAll
        _ <- cache.set(edges)
      yield ()
