package versola.loadgen.scenario

import zio.{Ref, UIO, ZIO}

/** Dev spec §7.1's rule that one virtual user has at most one in-flight operation, "enforced by
  * an in-memory `Set[UserId]` of busy users, not by a lock".
  *
  * The distinction is the whole safety argument of the emulator. Ownership of a user is already
  * settled by `shard = id % count`, so exactly one process can ever reach a given refresh token
  * and there is nothing to arbitrate between drivers -- what remains is the two fibers *inside*
  * one driver that could otherwise pick the same user, and the answer to that is to not pick it:
  * a lock would make the second fiber wait and generate its arrival late, which is load being
  * reshaped by the emulator's own bookkeeping (§7.5's rule).
  *
  * A skipped arrival is therefore the intended outcome, not a failure. It is visible as the gap
  * between `loadgen_arrivals_total` and the flows that ran, and a driver skipping many of them is
  * one whose population is too small for its rate -- a planning error, not a runtime one.
  */
trait BusyUsers:
  /** `false` when the user already has an operation in flight, in which case the caller must drop
    * this arrival rather than wait for it.
    */
  def acquire(userId: Long): UIO[Boolean]

  def release(userId: Long): UIO[Unit]

  /** Source of `loadgen_busy_users` (§11). */
  def size: UIO[Int]

  /** Runs `effect` with the user held, releasing it however the effect ends -- including
    * interruption, which is what a drain or a driver shutdown does to an in-flight session.
    * `None` when the user was already busy.
    */
  final def withUser[R, E, A](userId: Long)(effect: ZIO[R, E, A]): ZIO[R, E, Option[A]] =
    ZIO.acquireReleaseWith(acquire(userId))(held => release(userId).when(held)): held =>
      if held then effect.map(Some(_)) else ZIO.none

object BusyUsers:
  val make: UIO[BusyUsers] = Ref.make(Set.empty[Long]).map(RefBusyUsers(_))

private final class RefBusyUsers(held: Ref[Set[Long]]) extends BusyUsers:
  override def acquire(userId: Long): UIO[Boolean] =
    held.modify(current => if current.contains(userId) then (false, current) else (true, current + userId))

  override def release(userId: Long): UIO[Unit] = held.update(_ - userId)

  override def size: UIO[Int] = held.get.map(_.size)
