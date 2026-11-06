package versola.edge.revocation

import zio.{Duration, Task}

/** Where this edge has read a revocation feed up to, and which pod is reading it (`revocation_cursors`).
  *
  * One row per source. The row doubles as the lease that keeps the pods of an edge from all calling the feed:
  * whoever holds it runs a cycle. Every time comparison is made by the database, with its clock, so pods whose
  * clocks differ cannot both think they hold it, and nothing here is held open across a call to auth.
  */
trait RevocationCursorRepository:
  /** Makes sure the source has a row, reading from the beginning of the list. An existing row is left alone: a
    * restarted pod must not send its edge back to the beginning.
    */
  def seed(source: String): Task[Unit]

  /** Takes the lease of the source for `lease`, if nobody holds it, and returns the cursor to read from (`""` is the
    * beginning). `None` when another pod holds it, or the source has no row.
    *
    * One statement, so two pods asking at once cannot both be given it.
    */
  def acquire(source: String, holder: String, lease: Duration): Task[Option[String]]

  /** Extends the lease for a cycle that is still running, so a long catch-up does not lose it half-way. `false` when
    * `holder` no longer holds it (it ran out and another pod took it): the cycle should stop.
    */
  def renew(source: String, holder: String, lease: Duration): Task[Boolean]

  /** Records that everything up to `cursor` has been stored, and extends the lease. Only the holder moves it: `false`
    * when `holder` does not hold the lease any more, and then nothing is written.
    */
  def advance(source: String, holder: String, cursor: String, lease: Duration): Task[Boolean]

  /** Gives the lease back, so the next poll of any pod may take it without waiting for it to run out. A no-op when
    * `holder` does not hold it.
    */
  def release(source: String, holder: String): Task[Unit]
