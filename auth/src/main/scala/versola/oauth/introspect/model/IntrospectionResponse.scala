package versola.oauth.introspect.model

import zio.json.*
import zio.schema.*

case class IntrospectionResponse(
    active: Boolean,
    @jsonField("client_id") clientId: Option[String],
    scope: Option[String],
    username: Option[String],
    @jsonField("token_type") tokenType: Option[String],
    exp: Option[Long],
    iat: Option[Long],
    nbf: Option[Long],
    sub: Option[String],
    aud: Option[String],
    iss: Option[String],
    jti: Option[String],
) derives Schema, JsonCodec

object IntrospectionResponse:
  val Inactive: IntrospectionResponse =
    IntrospectionResponse(
      active = false,
      clientId = None,
      scope = None,
      username = None,
      tokenType = None,
      exp = None,
      iat = None,
      nbf = None,
      sub = None,
      aud = None,
      iss = None,
      jti = None
    )
