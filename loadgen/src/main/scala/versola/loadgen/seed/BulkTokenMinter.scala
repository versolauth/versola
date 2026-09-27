package versola.loadgen.seed

import versola.util.{Secret, SecureRandom, SecurityService}
import zio.{Chunk, Task, ZIO}

/** Mints [[RefreshTokenMaterial]] in bulk for the mobile cohort's warm sessions (§10 step 6).
  *
  * Shaped after [[BulkHasher]]: the same bounded `ZIO.foreachPar`, and the same reason for
  * calling into `SecurityService` rather than re-implementing it -- "a seeder that
  * re-implemented [auth's crypto] would produce a population whose [rows] are all individually
  * plausible and none of which verify" applies to a MAC exactly as it does to a password hash.
  * Unlike Argon2, a BLAKE3 MAC costs nothing worth bounding a semaphore over; `parallelism` is
  * accepted anyway so the seeder has one dial for "how parallel is this batch", not two.
  */
final class BulkTokenMinter(
    security: SecurityService,
    secureRandom: SecureRandom,
    refreshTokensSecret: Secret.Bytes32,
    sessionsSecret: Secret.Bytes32,
    parallelism: Int,
):
  def mintAll(ids: Chunk[Long]): Task[Chunk[(Long, RefreshTokenMaterial)]] =
    ZIO
      .foreachPar(ids): id =>
        for
          rawToken <- secureRandom.nextBytes(32)
          rawSessionId <- secureRandom.nextBytes(32)
          familyId <- secureRandom.nextBytes(16)
          publicSessionId <- secureRandom.nextBytes(16)
          tokenMac <- security.mac(Secret(rawToken), refreshTokensSecret)
          sessionMac <- security.mac(Secret(rawSessionId), sessionsSecret)
        yield id -> RefreshTokenMaterial(
          rawToken = rawToken,
          tokenMac = tokenMac,
          sessionMac = sessionMac,
          familyId = familyId,
          publicSessionId = publicSessionId,
        )
      .withParallelism(parallelism)
