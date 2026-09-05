package versola.oauth.authorize.model

import zio.json.*
import zio.schema.*

/** RFC 9126 §2.2 successful pushed authorization request response. */
case class PushedAuthorizationResponse(
    @jsonField("request_uri") requestUri: String,
    @jsonField("expires_in") expiresIn: Long,
) derives Schema, JsonCodec
