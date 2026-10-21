package versola.edge.login

import versola.edge.model.{CodeVerifier, PresetId, State}

case class LoginRecord(
    codeVerifier: CodeVerifier,
    presetId: PresetId,
    state: State,
    /** Where to send the browser when the login completes, a path of the preset's origin
      * ([[ReturnTo]]); `None` for the preset's own post-login page. */
    returnTo: Option[String] = None,
)
