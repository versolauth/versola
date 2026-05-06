package versola.edge.login

import versola.edge.model.{CodeVerifier, PresetId, State}

case class LoginRecord(
    codeVerifier: CodeVerifier,
    presetId: PresetId,
    state: State,
)
