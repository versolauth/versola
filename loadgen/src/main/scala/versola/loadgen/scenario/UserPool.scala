package versola.loadgen.scenario

import versola.loadgen.config.ShardConfig
import versola.loadgen.model.{VirtualUser, VirtualUserState}
import versola.loadgen.store.VirtualUserRepository
import zio.{Ref, Task, UIO, ZIO}

/** Which virtual user the next arrival belongs to.
  *
  * The arrival process (§7.2) is open: it produces *when* something happens, never *to whom*, so
  * something has to choose, and the choice is deliberately not a random id. A driver's slice of
  * `vu_users` is one contiguous stretch of the `(shard, id)` index (§7.1), and walking it in
  * order with keyset pagination is a range scan; drawing ids uniformly is a random heap access
  * per arrival against a table too large to cache, which at 5,000 arrivals a second is the
  * emulator's own bottleneck rather than the SUT's.
  *
  * Walking in id order and wrapping around is therefore the model: over one pass every user in
  * the shard arrives once, and the activity classes of §5 are expressed by the population's
  * composition rather than by weighting the draw. A user that is busy when its turn comes is
  * skipped by the caller ([[BusyUsers]]), not queued.
  *
  * Users that are not [[VirtualUserState.Registered]] are filtered out here: a `planned` user has
  * no SUT identity to log in with, and `broken` is terminal by design -- a driver that retried
  * its way back into a broken user is how a real SUT failure hides behind a slowly shrinking
  * active population ([[VirtualUserRepository.markBroken]]).
  */
trait UserPool:
  /** `None` when the driver's slice holds no usable user at all, which is a campaign pointed at
    * an unseeded population rather than a transient condition.
    */
  def next: Task[Option[VirtualUser]]

object UserPool:
  /** One index page per refill. Large enough that the scan amortises across thousands of
    * arrivals, small enough that a driver holds kilobytes of its population rather than all of
    * it -- 10M users at one shard in eight is 1.25M rows, which does not belong in a driver's
    * heap.
    */
  val pageSize: Int = 1000

  def paged(users: VirtualUserRepository, shard: ShardConfig, pageSize: Int): UIO[UserPool] =
    Ref.Synchronized.make(Cursor(Vector.empty, 0, None, false)).map(PagedUserPool(users, shard, pageSize, _))

  /** @param page     the rows not yet handed out, as loaded
    * @param index    how far into `page` the pool has got
    * @param afterId  keyset bound for the next page, `None` at the start of a pass
    * @param wrapped  whether this pass has already restarted, so an empty second pass is
    *                 reported as an empty population instead of looping forever
    */
  private final case class Cursor(page: Vector[VirtualUser], index: Int, afterId: Option[Long], wrapped: Boolean)

  private final class PagedUserPool(
      users: VirtualUserRepository,
      shard: ShardConfig,
      pageSize: Int,
      cursor: Ref.Synchronized[Cursor],
  ) extends UserPool:

    override def next: Task[Option[VirtualUser]] =
      cursor.modifyZIO: current =>
        if current.index < current.page.length then
          ZIO.succeed((Some(current.page(current.index)), current.copy(index = current.index + 1)))
        else refill(current)

    /** Loads the next page, restarting the pass when the slice runs out. `wrapped` is cleared as
      * soon as a page comes back with rows, so the guard only ever catches a genuinely empty
      * slice rather than a population that happens to be shorter than two pages.
      */
    private def refill(current: Cursor): Task[(Option[VirtualUser], Cursor)] =
      users.loadShardSlice(shard.index, current.afterId, pageSize).flatMap: loaded =>
        val usable = loaded.filter(_.state == VirtualUserState.Registered)
        if usable.nonEmpty then
          ZIO.succeed((Some(usable.head), Cursor(usable.toVector, 1, Some(loaded.last.id), false)))
        else if loaded.nonEmpty then refill(current.copy(afterId = Some(loaded.last.id)))
        else if current.wrapped then ZIO.succeed((None, Cursor(Vector.empty, 0, None, true)))
        else refill(Cursor(Vector.empty, 0, None, true))
