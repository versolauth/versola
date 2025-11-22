package versola.oauth.token.model

import versola.oauth.model.CodeVerifier

enum TokenEndpointRequest:
  case CodeExchange(
      code: String,
      redirectUri: String,
      clientId: String,
      codeVerifier: CodeVerifier,
  )
