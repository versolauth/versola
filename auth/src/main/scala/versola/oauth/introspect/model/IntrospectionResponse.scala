package versola.oauth.introspect.model

import versola.oauth.client.model.ClientId
import versola.user.model.UserId
import zio.json.*
import zio.schema.*

import java.util.UUID

case class IntrospectionResponse(
    active: Boolean,
    @jsonField("client_id") clientId: Option[ClientId],
    scope: Option[String],
    username: Option[String],
    @jsonField("token_type") tokenType: Option[String],
    exp: Option[Long],
    iat: Option[Long],
    nbf: Option[Long],
    sub: Option[String],
    aud: Option[Vector[ClientId]],
    iss: Option[String],
    jti: Option[String],
) derives JsonCodec, Schema

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
      jti = None,
    )
