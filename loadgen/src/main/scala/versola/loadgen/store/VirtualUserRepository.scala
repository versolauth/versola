package versola.loadgen.store

import versola.loadgen.model.{VirtualUser, VirtualUserState}
import versola.util.Secret
import zio.{Chunk, Task}

import java.time.Instant
import java.util.UUID

/** `vu_users` (migration V0001). Split by write class per dev spec §7.5: everything here except
  * [[touchAll]] is on the critical path and is awaited by its caller; `last_seen_at` is the
  * deferred column and reaches this trait only in batches, from the write-behind buffer.
  */
trait VirtualUserRepository:

  /** Bulk insert of a planned population. The seeder (§10) populates 10-20M rows with `COPY`
    * against this same table and does not go through here; this is the path for the smaller
    * incremental cohorts -- the registration campaign's arrivals, and test fixtures.
    */
  def insertAll(users: Chunk[VirtualUser]): Task[Unit]

  def find(id: Long): Task[Option[VirtualUser]]

  /** One page of this driver's slice, in `id` order, served by `vu_users_shard_idx`.
    *
    * Keyset pagination rather than `OFFSET`: a driver walks its whole slice once at startup and
    * then again per active window, and `OFFSET n` re-reads and discards n index entries per
    * page, which turns a linear walk into a quadratic one at 10M rows.
    *
    * @param afterId
    *   exclusive lower bound, `None` for the first page.
    */
  def loadShardSlice(shard: Int, afterId: Option[Long], limit: Int): Task[Vector[VirtualUser]]

  /** Records the SUT identity a registration or seed produced and moves the row to
    * [[VirtualUserState.Registered]]. Both in one statement: a user with a `sut_user_id` but
    * still `planned` would be re-registered by the controller and collide on `phone`.
    */
  def markRegistered(id: Long, sutUserId: UUID): Task[Unit]

  /** Terminal state for a user whose credentials no longer work. Deliberately not reversible
    * here -- a driver retrying its way out of `broken` is how a real SUT failure gets hidden
    * behind a slowly shrinking active population.
    */
  def markBroken(id: Long): Task[Unit]

  /** Critical write (§7.5): persisted before the credential is used, so a driver crash between
    * enrolment and the first assertion cannot orphan a passkey that only the SUT knows about.
    */
  def recordPasskey(id: Long, key: Secret, credentialId: String): Task[Unit]

  /** Deferred write path -- the write-behind buffer's only entry point into this table. */
  def touchAll(touches: Chunk[UserTouch]): Task[Unit]

  /** Population counts by state, for the coordinator's registration controller and `/status`
    * (§12). Returns every state the table holds; a state with no rows is absent from the map
    * rather than present as zero, since the caller renders what exists.
    */
  def countByState: Task[Map[VirtualUserState, Long]]
