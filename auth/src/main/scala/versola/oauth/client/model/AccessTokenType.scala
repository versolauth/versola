package versola.oauth.client.model

import zio.prelude.Equal

enum AccessTokenType derives Equal:
  case Jwt, Opaque
