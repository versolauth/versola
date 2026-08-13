package versola.oauth.authorize.model

import versola.oauth.client.model.ClientId
import zio.prelude.Equal

/** RFC 9126 §2.2: the authorization request payload pushed by `clientId`, referenced by a
  * `request_uri` until it is consumed at the authorization endpoint or expires.
  */
case class PushedAuthorizationRecord(
    clientId: ClientId,
    params: Map[String, List[String]],
) derives CanEqual, Equal
