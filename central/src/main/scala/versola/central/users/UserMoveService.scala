package versola.central.users

import versola.central.configuration.topology.{CellId, CellState, TopologyRepository}
import versola.util.SecureRandom
import zio.{Clock, IO, Task, ZIO, ZLayer}

/** Why a move was not started: a closed set of codes, the error codes of the answer. */
enum MoveRefusal(val code: String, val message: String):
  case NoSuchUser extends MoveRefusal("user_not_found", "there is no such user")
  case NoCell extends MoveRefusal("user_without_cell", "the user is in no cell")
  case SourceNotRegistered extends MoveRefusal("source_cell_not_registered", "the cell the user is in is not registered")
  case TargetNotRegistered extends MoveRefusal("target_cell_not_registered", "the target cell is not registered")
  case SameCell extends MoveRefusal("same_cell", "the target cell is the cell the user is in")
  case OtherPartition extends MoveRefusal("other_partition", "the target cell is in another partition")
  case TargetDraining extends MoveRefusal("target_cell_draining", "the target cell is being drained")
  case InProgress extends MoveRefusal("move_in_progress", "a move of the user is in progress")

/** Moving a user from the cell that holds it to another cell of the same partition, started by an administrator
  * (versolauth/versola#1051). The move itself is done by [[UserMoveProcessor]]; this only decides whether it may begin.
  */
trait UserMoveService:
  /** Records the move, which the processor takes up. */
  def start(userId: UserId, to: CellId): IO[MoveRefusal | Throwable, Unit]

  /** The moves, newest first, of `cell` (from it or to it) and of `phase`. */
  def list(cell: Option[CellId], phase: Option[MovePhase], limit: Int): Task[Vector[UserMove]]

object UserMoveService:
  val live: ZLayer[UserRepository & UserMoveRepository & TopologyRepository & SecureRandom, Nothing, UserMoveService] =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(users: UserRepository, moves: UserMoveRepository, topology: TopologyRepository, secureRandom: SecureRandom)
    extends UserMoveService:
    override def start(userId: UserId, to: CellId): IO[MoveRefusal | Throwable, Unit] =
      for
        user <- users.find(userId).someOrFail(MoveRefusal.NoSuchUser)
        from <- ZIO.fromOption(user.cellId).orElseFail(MoveRefusal.NoCell)
        cells <- topology.getCells
        regions <- topology.getRegions
        target <- ZIO.fromOption(cells.find(_.id == to)).orElseFail(MoveRefusal.TargetNotRegistered)
        _ <- ZIO.fail(MoveRefusal.SameCell).when(from == to)
        source <- ZIO.fromOption(cells.find(_.id == from)).orElseFail(MoveRefusal.SourceNotRegistered)
        partitionOf = regions.map(region => region.id -> region.partitionId).toMap
        // a cell whose region is not known has no partition, and no cell is in the same partition as that
        _ <- ZIO.fail(MoveRefusal.OtherPartition)
          .unless(partitionOf.get(source.regionId).exists(partitionOf.get(target.regionId).contains))
        _ <- ZIO.fail(MoveRefusal.TargetDraining).when(target.state == CellState.Draining)
        now <- Clock.instant
        // new at every start: it binds what the target is given to this move and to no earlier one
        moveId <- secureRandom.nextUUIDv7
        started <- moves.start(
          UserMove(
            userId = userId,
            fromCell = from,
            toCell = to,
            phase = MovePhase.Leaving,
            startedAt = now,
            updatedAt = now,
            attempts = 0,
            error = None,
            reason = MoveReason.Admin,
            notBefore = now,
            departedAt = None,
            moveId = moveId,
            digest = None,
          ),
        )
        _ <- ZIO.fail(MoveRefusal.InProgress).unless(started)
      yield ()

    override def list(cell: Option[CellId], phase: Option[MovePhase], limit: Int): Task[Vector[UserMove]] =
      moves.list(cell, phase, limit)
