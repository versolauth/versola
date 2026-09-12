package versola.loadgen.protocol

import zio.IO

/** A business action through the edge's resource proxy (§8.6) -- the one part of the edge
  * surface the mobile flows of §8.1-8.3 need, and the only part that a bearer token can use at
  * all.
  *
  * Split out of [[EdgeClient]] so that a driver running mobile scenarios depends on this alone:
  * §8.4's `/login/{presetId}` -> `/complete` cookie path is track G's, and nothing in §8.1-8.3
  * or §8.5 can call it.
  */
trait ActionClient:
  /** [[EdgeCredential.Cookie]] drives the web path, [[EdgeCredential.Bearer]] the mobile one.
    * Edge rotates the cookie on refresh; callers must adopt [[ActionOutcome.rotatedSession]]
    * whenever it is set (§8.4).
    */
  def call(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome]
