package versola.edge.model

import zio.json.JsonCodec

/** OIDC Registration §2 `application_type`, as central registered the client. Edge reads it to
  * decide which clients its native endpoints serve (#420): only a `native` client that edge
  * holds a `tls_client_auth` certificate for. */
enum ApplicationType derives JsonCodec, CanEqual:
  case web, native
