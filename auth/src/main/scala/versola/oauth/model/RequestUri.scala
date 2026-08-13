package versola.oauth.model

import versola.util.{Base64, ByteArrayNewType}

/** Random reference part of an RFC 9126 `request_uri`. */
type RequestUriReference = RequestUriReference.Type

object RequestUriReference extends ByteArrayNewType

object RequestUri:
  /** URN sub-namespace registered by RFC 9126 §9.3. */
  val Prefix = "urn:ietf:params:oauth:request_uri:"

  def apply(reference: RequestUriReference): String =
    Prefix + Base64.urlEncode(reference)

  def parse(value: String): Either[String, RequestUriReference] =
    if !value.startsWith(Prefix) then Left(s"request_uri must start with '$Prefix'")
    else RequestUriReference.fromBase64Url(value.drop(Prefix.length))
