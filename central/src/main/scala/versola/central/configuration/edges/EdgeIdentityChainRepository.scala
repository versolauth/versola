package versola.central.configuration.edges

import zio.Task

import java.time.Instant

/** When the chain of certificates of each edge began (see [[EdgeIdentityService]]). */
trait EdgeIdentityChainRepository:
  /** Begins the chain of `edgeId` at `at`, or moves its start there. */
  def start(edgeId: EdgeId, at: Instant): Task[Unit]

  def startedAt(edgeId: EdgeId): Task[Option[Instant]]

  def all: Task[Map[EdgeId, Instant]]
