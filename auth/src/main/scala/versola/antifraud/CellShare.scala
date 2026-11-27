package versola.antifraud

import versola.oauth.handoff.{CellInfo, CellRegistry}
import versola.util.CoreConfig
import zio.{UIO, URLayer, ZIO, ZLayer}

/** The part of a limit by address that this cell applies (versolauth/versola#1052).
  *
  * Velocity counters are per cell, so a limit of L on an IP address passes about L in every cell. A limit that is meant
  * for the deployment is divided between the cells by the share of the users each takes: a cell compares a count
  * against `limit * share`, and a person who tries the address in every cell stays inside the limit of the deployment.
  *
  * The registry is in memory (it is copied from central on the configuration interval), so the share is read on the
  * request path without a call.
  */
trait CellShare:
  def current: UIO[CellShare.Share]

object CellShare:

  /** `numerator / denominator`, kept whole so that `limit * share` is rounded up exactly: a limit of 100 with a share of
    * 34/100 is 34, and not the 35 that the double 0.34 would give. Always `0 < numerator <= denominator`. */
  final case class Share(numerator: Long, denominator: Long):
    /** What a limit becomes in this cell: `max(1, ceil(limit * share))`. A limit of 0 is "no limit" and stays 0; a share
      * that would round a limit down to nothing gives 1, which is the least that still limits. */
    def of(limit: Int): Int =
      if limit <= 0 then limit
      else math.max(1L, Math.ceilDiv(limit.toLong * numerator, denominator)).toInt

    def value: Double = numerator.toDouble / denominator.toDouble

  /** This auth is no cell, no cell is registered or the registry has not been read: the limit as it is. */
  val Whole: Share = Share(1, 1)

  /** The cells that take users: the filter central places new users by. */
  private def takesUsers(cell: CellInfo): Boolean = cell.state == "open" && cell.weight > 0

  /** `weight / sum(weights)` over the cells that take users, when `own` is one of them; `1 / (number of registered
    * cells)` when it takes none (it still serves the users it holds, and the limit is shared out equally among the
    * cells, whatever they take); `Whole` when there is nothing to share. */
  def of(own: Option[String], cells: List[CellInfo]): Share =
    own match
      case None => Whole
      case Some(_) if cells.isEmpty => Whole
      case Some(id) =>
        val takers = cells.filter(takesUsers)
        takers.find(_.id == id) match
          case Some(cell) => Share(cell.weight.toLong, takers.map(_.weight.toLong).sum)
          case None => Share(1, cells.size.toLong)

  /** An auth that is no cell, or a test that has no registry. */
  val whole: CellShare = new CellShare:
    override def current: UIO[Share] = ZIO.succeed(Whole)

  val live: URLayer[CellRegistry & CoreConfig, CellShare] =
    ZLayer.fromFunction: (registry: CellRegistry, config: CoreConfig) =>
      new CellShare:
        override def current: UIO[Share] = registry.all.map(of(config.cellId.map(identity[String]), _))
