package versola.oauth.client.model

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** OIDC Dynamic Client Registration §2 `application_type`, as central registered it.
  *
  * Read for one thing only: a `native` client authenticating with `tls_client_auth` is an app
  * fronted by edge (#420/#421), and the certificate it authenticates with is edge's, shared by
  * every installation -- so its tokens are bound to the device's DPoP key alone, never to that
  * certificate. See [[OAuthClientRecord.bindsAccessTokens]].
  */
enum ApplicationType derives JsonCodec, Schema, Equal, CanEqual:
  case web, native
