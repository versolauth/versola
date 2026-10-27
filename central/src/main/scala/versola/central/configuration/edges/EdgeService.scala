package versola.central.configuration.edges

import versola.central.CentralConfig
import versola.util.{ReloadingCache, RsaKeyPair, SecurityService}
import zio.{Clock, Schedule, Scope, Task, UIO, ZIO, ZLayer}

trait EdgeService:
  def getAllEdges: UIO[Vector[EdgeRecord]]

  def find(id: EdgeId): UIO[Option[EdgeRecord]]

  /** Registers an edge in `groupId`, which it never leaves. The group has to exist ([[EdgeGroupService.find]]). */
  def registerEdge(id: EdgeId, groupId: EdgeGroupId): Task[RsaKeyPair]

  def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair]

  def deleteOldEdgeKey(id: EdgeId): Task[Unit]

  def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit]

  /** The edge as stored now, not as the cache last saw it: what a client compares revisions against. */
  def findFresh(id: EdgeId): Task[Option[EdgeRecord]]

  /** Applies `patch` to the policy sections of the edge ([[EdgePoliciesPatch]]). With `dryRun` it checks and answers the record the
    * patch would make, and writes nothing. `None`: no such edge.
    */
  def patchPolicies(id: EdgeId, patch: EdgePoliciesPatch, dryRun: Boolean): Task[Option[Either[PolicyPatchError, EdgeRecord]]]

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

    override def registerEdge(id: EdgeId, groupId: EdgeGroupId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.createEdge(id, keyPair.toPublicJwk, EdgeRecord.DefaultRequireDpopNonce, groupId)
      yield keyPair

    override def rotateEdgeKey(id: EdgeId): Task[RsaKeyPair] =
      for
        keyPair <- securityService.generateRsaKeyPair
        _ <- edgeRepository.rotateEdgeKey(id, keyPair.toPublicJwk)
      yield keyPair

    override def deleteOldEdgeKey(id: EdgeId): Task[Unit] =
      edgeRepository.deleteOldEdgeKey(id)

    override def setRequireDpopNonce(id: EdgeId, requireDpopNonce: Boolean): Task[Unit] =
      edgeRepository.setRequireDpopNonce(id, requireDpopNonce)

    override def findFresh(id: EdgeId): Task[Option[EdgeRecord]] =
      edgeRepository.find(id)

    override def patchPolicies(id: EdgeId, patch: EdgePoliciesPatch, dryRun: Boolean): Task[Option[Either[PolicyPatchError, EdgeRecord]]] =
      Clock.instant.flatMap: now =>
        if dryRun then edgeRepository.find(id).map(_.map(EdgePoliciesPatch(_, patch, now)))
        else
          edgeRepository.patchPolicies(id, EdgePoliciesPatch(_, patch, now))
            // the cache is read by the console's listing and by `find`; it is brought up to date now rather than at its next refresh
            .tap(result => sync().when(result.exists(_.isRight)))

    override def deleteEdge(id: EdgeId): Task[Unit] =
      edgeRepository.deleteEdge(id)

    override def sync(): Task[Unit] =
      for
        edges <- edgeRepository.getAll
        _ <- cache.set(edges)
      yield ()
