package versola.loadgen.seed

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.Task

/** The one read the seeder makes of the emulator's own store: where to resume.
  *
  * Not on [[versola.loadgen.store.VirtualUserRepository]], which is the drivers' and the
  * coordinator's interface -- `max(id)` is meaningless to both of them (a driver reads its own
  * shard's slice, the coordinator counts states) and adding it there would widen an interface two
  * other tracks implement against for one caller's benefit.
  */
final class StoreQueries(xa: TransactorZIO):

  /** `None` on an empty table, so the caller starts at id 1. An index-only scan of the `(shard,
    * id)` index's rightmost leaf, not a table scan, which is why this is affordable at 20M rows.
    */
  def maxVirtualUserId: Task[Option[Long]] =
    xa.connect:
      sql"SELECT max(id) FROM vu_users".query[Option[Long]].run().headOption.flatten
