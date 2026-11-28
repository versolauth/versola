package versola.oauth.model

import versola.util.ByteArrayNewType

/** Random reference part of an RFC 9126 `request_uri`. */
type RequestUriReference = RequestUriReference.Type

object RequestUriReference extends ByteArrayNewType.Routed

object RequestUri:
  /** URN sub-namespace registered by RFC 9126 §9.3. */
  val Prefix = "urn:ietf:params:oauth:request_uri:"

  /** The routing key, when the reference carries one, sits inside the reference part: the
    * namespace stays the one RFC 9126 registers, so a client sees a `request_uri` of the
    * shape it is promised whichever cell issued it. */
  def apply(reference: RequestUriReference): String =
    Prefix + RequestUriReference.present(reference)

  def parse(value: String): Either[String, RequestUriReference] =
    if !value.startsWith(Prefix) then Left(s"request_uri must start with '$Prefix'")
    else RequestUriReference.fromPresented(value.drop(Prefix.length))
