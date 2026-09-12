package versola.loadgen.protocol

import zio.IO

/** The web/cookie path through edge (§8.4) -- entirely new code, not covered by the e2e client
  * (which never exercises `/login/{presetId}` -> `/complete`). Implementation lands with track G.
  *
  * The resource-proxy call itself is [[ActionClient]], which track B implements
  * ([[EdgeActionClient]]) because §8.6 needs it for the mobile flows too.
  */
trait EdgeClient extends ActionClient:
  def login(preset: PresetId, acrValues: Option[List[String]]): IO[ProtocolError, EdgeLoginStarted]

  def complete(state: String, code: AuthCode): IO[ProtocolError, EdgeSession]

  def logout(preset: PresetId, session: EdgeSession): IO[ProtocolError, Unit]
