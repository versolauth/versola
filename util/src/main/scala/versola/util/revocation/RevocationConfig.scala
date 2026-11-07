package versola.util.revocation

import zio.Duration

/** How a replica keeps its in-memory list of revoked access tokens (the `revocation` block of the
  * auth and edge configurations).
  *
  * @param reloadInterval how often the list is reconciled with the database. Revocations normally
  *                       arrive by notification, so this is the backstop for one that was lost.
  * @param purgeInterval how often entries whose tokens have expired are dropped. Separate from the
  *                      reload because it is the only thing that bounds what the replica holds and
  *                      it needs no database, so it must not be tied to the cadence of something
  *                      that can be slowed down or stopped by one.
  * @param overlap how far back before the last row read a reconcile starts again. `revoked_at` is
  *                set when a row is written but the row appears when its transaction commits, so
  *                one that committed late would sit behind the cursor and never be read.
  *                Re-reading the window costs nothing: applying a revocation twice is applying it
  *                once.
  * @param batchSize how many rows a single read returns. Bounds what one read holds, not what the
  *                  replica ends up holding.
  */
case class RevocationConfig(
    reloadInterval: Duration = Duration.fromSeconds(600),
    purgeInterval: Duration = Duration.fromSeconds(60),
    overlap: Duration = Duration.fromSeconds(30),
    batchSize: Int = 50000,
)
