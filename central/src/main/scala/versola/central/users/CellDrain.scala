package versola.central.users

import versola.central.configuration.topology.{CellPlacement, CellRecord, CellState, RegionRecord, TopologyRepository}
import versola.util.SecureRandom
import zio.{Clock, Fiber, Random, Ref, Schedule, Scope, Task, UIO, URIO, ZIO, ZLayer}

/** Empties the cells in state `draining` (versolauth/versola#1051): background work that takes the users the directory places in
  * a draining cell and starts a move of each, with reason `drain`, to an open cell of the same partition. At most
  * [[UserMoveSettings.parallel]] such moves leave a cell at a time, across the instances of central
  * ([[UserMoveRepository.startDrain]]); the moves themselves are done by [[UserMoveProcessor]]. A move that failed is tried again
  * after [[UserMoveSettings.retryAfter]].
  *
  * Where there is no open cell of the partition to take a user nothing is started, and the topology answer says so
  * ([[CellDrain.blocked]]).
  */
trait CellDrain:
  def start(): URIO[Scope, Unit]
  def stop(): UIO[Unit]

object CellDrain:
  /** Said of a draining cell that no open cell of its partition could take its users. */
  val NoOpenCell = "no_open_cell"

  val live: ZLayer[UserMoveSettings & TopologyRepository & UserMoveRepository & SecureRandom & Scope, Nothing, CellDrain] =
    ZLayer:
      ZIO.acquireRelease(
        acquire =
          for
            settings <- ZIO.service[UserMoveSettings]
            topology <- ZIO.service[TopologyRepository]
            moves <- ZIO.service[UserMoveRepository]
            secureRandom <- ZIO.service[SecureRandom]
            fiberRef <- Ref.make(Option.empty[Fiber.Runtime[Nothing, Unit]])
            drain: CellDrain = Live(settings, topology, moves, secureRandom, fiberRef)
          yield drain,
      )(_.stop()).tap(_.start())

  /** The cells that may take a user of `cell`: those of its partition, but itself. [[CellPlacement.pick]] then keeps the open
    * ones that take users. A cell whose region is unknown is in no partition, and so has none. */
  def candidates(cell: CellRecord, cells: Vector[CellRecord], regions: Vector[RegionRecord]): Vector[CellRecord] =
    val partitionOf = regions.map(region => region.id -> region.partitionId).toMap
    partitionOf.get(cell.regionId) match
      case None => Vector.empty
      case Some(partition) => cells.filter(other => other.id != cell.id && partitionOf.get(other.regionId).contains(partition))

  /** Why a draining cell cannot be emptied now: [[NoOpenCell]]; `None` for a cell that is not draining or can. */
  def blocked(cell: CellRecord, cells: Vector[CellRecord], regions: Vector[RegionRecord]): Option[String] =
    Option.when(cell.state == CellState.Draining && CellPlacement.pick(candidates(cell, cells, regions), 0.0).isEmpty)(NoOpenCell)

  private[users] class Live(
      settings: UserMoveSettings,
      topology: TopologyRepository,
      moves: UserMoveRepository,
      secureRandom: SecureRandom,
      fiberRef: Ref[Option[Fiber.Runtime[Nothing, Unit]]],
  ) extends CellDrain:

    override def start(): URIO[Scope, Unit] =
      processOnce.repeat(Schedule.spaced(settings.pollInterval)).unit.forkScoped.flatMap(fiber => fiberRef.set(Some(fiber)))

    override def stop(): UIO[Unit] =
      fiberRef.get.flatMap(fiber => ZIO.foreachDiscard(fiber)(_.interrupt))

    /** One pass over the draining cells. @return how many moves were started */
    private[users] def processOnce: UIO[Int] =
      (for
        cells <- topology.getCells
        regions <- topology.getRegions
        started <- ZIO.foreach(cells.filter(_.state == CellState.Draining))(cell => drain(cell, candidates(cell, cells, regions)))
      yield started.sum).catchAllCause(cause => ZIO.logErrorCause("cell drain failed", cause).as(0))

    /** Starts moves out of `cell` until the pacing or the users run out. */
    private def drain(cell: CellRecord, targets: Vector[CellRecord]): Task[Int] =
      def next(started: Int): Task[Int] =
        if started >= settings.parallel then ZIO.succeed(started)
        else
          Random.nextDouble.flatMap: roll =>
            CellPlacement.pick(targets, roll) match
              case None => ZIO.succeed(started)
              case Some(target) =>
                (for
                  now <- Clock.instant
                  moveId <- secureRandom.nextUUIDv7
                  taken <- moves.startDrain(cell.id, target, moveId, settings.parallel, now, now.minus(settings.retryAfter))
                yield taken).flatMap:
                  case Some(_) => next(started + 1)
                  case None => ZIO.succeed(started)
      next(0)
