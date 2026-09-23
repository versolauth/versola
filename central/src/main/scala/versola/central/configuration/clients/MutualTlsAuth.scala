package versola.central.configuration.clients

import zio.json.{JsonCodec, jsonDiscriminator, jsonHint}
import zio.prelude.Equal
import zio.schema.*
import zio.schema.annotation.{caseName, discriminatorName}

/** Which RFC 8705 §2.1.2 registered value a client's certificate is recognised by.
  *
  * A discriminator rather than five nullable fields because the RFC registers exactly one of
  * them per client: a certificate matching any registered value would authenticate the client,
  * so allowing several to be set at once only widens what a single stolen certificate reaches.
  */
enum MutualTlsSubjectType derives JsonCodec, Schema, Equal:
  case subject_dn, san_dns, san_uri, san_ip, san_email

/** Which of RFC 8705's two mutual-TLS authentication methods a client registered. The two are
  * alternatives rather than settings of one method: they disagree on what the credential is,
  * so what a client registers decides what a presented certificate is compared against.
  */
@jsonDiscriminator("type")
@discriminatorName("type")
enum MutualTlsAuth derives Schema, JsonCodec, CanEqual, Equal:
  /** RFC 8705 §2.1 `tls_client_auth`: the client authenticates with a certificate validated to
    * a trusted CA and carrying this exact subject value. Access tokens it receives are bound
    * to that certificate per §3 whether or not it registered the §3.4 flag — see
    * `OAuthClientRecord.bindsAccessTokens` for why the combination is not offered.
    *
    * @param subjectValue compared literally against the certificate. For `subject_dn` that is
    *                     the RFC 4514 string form of the subject, which is why registration
    *                     normalises it rather than trusting the operator's spacing.
    */
  @jsonHint("tls_client_auth") @caseName("tls_client_auth") case TlsClientAuth(subjectType: MutualTlsSubjectType, subjectValue: String)

  /** RFC 8705 §2.2 `self_signed_tls_client_auth`: the presented certificate's public key is
    * matched against the client's registered `jwks` — the same key set RFC 7523
    * `private_key_jwt` verifies client assertions against, which is why §2.2 registers no
    * subject value of its own. There is no PKI here: no trust anchor, no chain, no subject
    * comparison.
    *
    * §2.2 also declines to apply chain and expiry validation to the certificate, the key
    * being the credential rather than the certificate's validity. Nothing enforces that
    * because nothing in this codebase validates either one to begin with: the chain was
    * checked by the proxy that terminated mTLS, which is the only place the trust anchors
    * live.
    */
  @jsonHint("self_signed_tls_client_auth") @caseName("self_signed_tls_client_auth") case SelfSignedTlsClientAuth()
