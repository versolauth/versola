package versola.oauth.introspect.model

import zio.json.*
import zio.schema.*

/**
 * OAuth 2.0 Token Introspection Error Response
 * RFC 7662: https://datatracker.ietf.org/doc/html/rfc7662
 */
case class IntrospectionErrorResponse(
    error: String,
    @jsonField("error_description") errorDescription: Option[String] = None,
) derives Schema, JsonCodec

object IntrospectionErrorResponse:
  def from(error: IntrospectionError): IntrospectionErrorResponse =
    IntrospectionErrorResponse(
      error = error.error,
      errorDescription = error.errorDescription,
    )

