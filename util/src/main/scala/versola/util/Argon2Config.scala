package versola.util

/** Admission control for Argon2id password hashing, which runs on ZIO's unbounded blocking
  * pool. Without a cap, the number of concurrent hashes is bounded only by inbound request
  * volume.
  *
  * @param maxConcurrent
  *   concurrent Argon2id hashes. Each holds ~19 MiB of heap for its duration, so this is the
  *   knob that bounds worst-case hashing heap usage (roughly `maxConcurrent * 19 MiB`). Size it
  *   against the container's heap budget.
  */
case class Argon2Config(maxConcurrent: Int)

object Argon2Config:
  /** ~228 MiB of Argon2 headroom, which fits the 384 MiB heap the auth container gets from its
    * 512 MiB `mem_limit` at `-XX:MaxRAMPercentage=75` (see deploy.md).
    */
  val default: Argon2Config = Argon2Config(maxConcurrent = 12)
