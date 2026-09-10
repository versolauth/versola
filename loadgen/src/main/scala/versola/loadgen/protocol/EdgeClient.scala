package versola.loadgen.protocol

import zio.IO

/** The web/cookie path through edge (§8.4) -- entirely new code, not covered by the e2e client
  * (which never exercises `/login/{presetId}` -> `/complete`). Implementation lands with track G.
  */
trait EdgeClient:
  def login(preset: PresetId, acrValues: Option[List[String]]): IO[ProtocolError, EdgeLoginStarted]

  def complete(state: String, code: AuthCode): IO[ProtocolError, EdgeSession]

  /** [[EdgeCredential.Cookie]] drives the web path, [[EdgeCredential.Bearer]] the mobile one.
    * Edge rotates the cookie on refresh; callers must adopt [[ActionOutcome.rotatedSession]]
    * whenever it is set (§8.4).
    */
  def call(credential: EdgeCredential, action: ActionCall): IO[ProtocolError, ActionOutcome]

  def logout(preset: PresetId, session: EdgeSession): IO[ProtocolError, Unit]
