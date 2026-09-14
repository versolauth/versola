package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** Which RFC 8705 §2.1.2 registered value a client's certificate is recognised by.
  *
  * A discriminator rather than five nullable fields because the RFC registers exactly one of
  * them per client: a certificate matching any registered value would authenticate the client,
  * so allowing several to be set at once only widens what a single stolen certificate reaches.
  */
enum MutualTlsSubjectType derives JsonCodec, Schema, Equal:
  case subject_dn, san_dns, san_uri, san_ip, san_email

/** RFC 8705 §2.1 `tls_client_auth`: the client authenticates with a certificate validated to a
  * trusted CA and carrying this exact subject value.
  *
  * §2.2 `self_signed_tls_client_auth` is deliberately absent. It matches the certificate
  * against the client's registered JWKS rather than a subject value, and per-client JWKS
  * storage — shared with RFC 7523 `private_key_jwt` — does not exist yet; adding it here first
  * would fix its shape before the method that shares it has a say.
  *
  * @param subjectValue compared literally against the certificate. For `subject_dn` that is
  *                     the RFC 4514 string form of the subject, which is why registration
  *                     normalises it rather than trusting the operator's spacing.
  */
case class MutualTlsAuth(
    subjectType: MutualTlsSubjectType,
    subjectValue: String,
) derives Schema, JsonCodec, Equal
