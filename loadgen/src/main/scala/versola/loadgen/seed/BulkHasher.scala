package versola.loadgen.seed

import versola.util.{MAC, Salt, SecureRandom, SecurityService, Secret}
import zio.{Chunk, Task, ZIO}

/** One seeded `user_passwords` row's cryptographic material. Salt and hash together, because
  * `PasswordService.check` re-hashes with the stored salt and compares -- a salt that does not
  * travel with its hash is a user nobody can log in as.
  */
case class HashedPassword(id: Long, salt: Salt, hash: MAC)

/** Bulk Argon2id for the password cohort (versola-loadgen-dev-spec.md §10 step 2) -- the seeder's
  * cost centre, and the reason the seeder exists at all: 20M × 35% × ~30 ms is ~58 CPU-hours of
  * hashing, which is roughly two hours on one machine's worth of cores and roughly never through
  * the registration flow.
  *
  * It hashes through the repository's own [[SecurityService]] rather than calling BouncyCastle
  * here. That is not code reuse for its own sake: the parameters (Argon2id, v1.3, t=2, m=19 MiB,
  * p=1, 32-byte output) and the *shape* of the input (the pepper enters as `additional` data, not
  * as part of the salt) are what `PasswordService.verifyPassword` will recompute, and a seeder
  * that re-implemented them would produce a population whose passwords are all individually
  * plausible and none of which verify. Nothing about that failure points at the seeder.
  *
  * @param parallelism
  *   concurrent hashes. Bounded, and the bound is configuration rather than "all cores": each
  *   in-flight Argon2id holds ~19 MiB of heap for its duration, so `parallelism × 19 MiB` is the
  *   seeder's worst-case hashing footprint and it has to fit the container. The same number is
  *   given to [[SecurityService.live]] as its `Argon2Config.maxConcurrent`, so the semaphore
  *   inside the service and the fiber budget out here agree -- see [[SeedServices]].
  */
final class BulkHasher(security: SecurityService, secureRandom: SecureRandom, pepper: Secret.Bytes16, parallelism: Int):

  /** A fresh 16-byte salt per user, from the shared secure random, matching
    * `PasswordService.setPassword`'s `secureRandom.nextBytes(16)`. Not derived from the id: a
    * per-user salt whose input is public is a rainbow table, and the campaign's Argon2 cost is
    * supposed to be representative of the SUT's.
    */
  def hashAll(passwords: Chunk[(Long, String)]): Task[Chunk[HashedPassword]] =
    ZIO
      .foreachPar(passwords): (id, plaintext) =>
        for
          salt <- secureRandom.nextBytes(BulkHasher.SaltBytes).map(Salt(_))
          hash <- security.hashPassword(Secret.fromString(plaintext), salt, pepper)
        yield HashedPassword(id, salt, hash)
      .withParallelism(parallelism)

object BulkHasher:
  /** `PasswordService.setPassword` and `AuthBootstrapService` both use 16, and the width is part
    * of what `verifyPassword` reproduces via the stored salt rather than a constant -- but a
    * seeded population that differed here would still verify, and would still be wrong: the
    * campaign's hashing cost would not be the SUT's.
    */
  val SaltBytes = 16
