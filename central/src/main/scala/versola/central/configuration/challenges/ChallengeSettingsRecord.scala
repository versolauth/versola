package versola.central.configuration.challenges

import versola.central.configuration.tenants.TenantId
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

case class PasskeySettings(
    rpId: String,
    rpName: String,
    origins: List[String],
    userVerification: String,
) derives Schema, JsonCodec

/** How the reverse proxy in front of this tenant delivers a client certificate it terminated
  * mTLS for. There is no standard header or encoding for this -- every proxy invents its own
  * (nginx's `$ssl_client_escaped_cert`, Traefik's `passTLSClientCert`, cloud load balancers'
  * own headers again) -- so `auth` has to be told which shape to expect instead of guessing.
  */
enum MtlsCertificateEncoding derives Schema, JsonCodec:
  /** The full PEM (delimiters included), with `\n` and other reserved characters
    * percent-encoded. nginx's `$ssl_client_escaped_cert`; ingress-nginx forwards it unchanged
    * as `ssl-client-cert` when `auth-tls-pass-certificate-to-upstream` is set.
    */
  case urlEncodedPem
  /** The certificate's DER bytes, base64-encoded, with the PEM delimiters and newlines
    * stripped. Traefik's `passTLSClientCert` middleware with `pem: true`.
    */
  case base64Der

case class ChallengeSettingsRecord(
    tenantId: TenantId,
    allowedPrefixes: List[String],
    submissionLimits: SubmissionLimits,
    otpLength: Int,
    otpResendAfter: Int,
    passkeySettings: PasskeySettings,
    authConversationTtlSeconds: Int,
    sessionTtlSeconds: Int,
    sessionIdleTtlSeconds: Option[Int],
    userAgentTtlSeconds: Int,
    ipHeader: String,
    acrVocabulary: Option[Map[String, List[String]]],
    postLogoutRedirectUris: List[String],
    /** RFC 9449 §8: whether a proof reaching auth's token or userinfo endpoint from one of
      * this tenant's clients must carry a server-issued nonce. */
    requireDpopNonce: Boolean,
    /** The header the proxy sets with the client certificate it terminated mTLS for. `None`
      * means this tenant's proxy does not terminate mTLS, so `auth` never looks for one --
      * matching a client's `mtlsAuth` is then impossible and registering it should be refused.
      */
    mtlsCertificateHeader: Option[String],
    /** How the certificate in `mtlsCertificateHeader` is encoded. Always present together
      * with `mtlsCertificateHeader` -- a header with no known encoding can't be parsed.
      */
    mtlsCertificateEncoding: Option[MtlsCertificateEncoding],
    /** The `kid` of the published JWKS key this tenant's tokens are signed with. The
      * algorithm follows from the key, so the two cannot disagree -- and during a rotation,
      * when two keys share one `alg`, a kid is the only thing that names a key at all.
      *
      * `None` falls back to auth matching its configured `jwt.private-key` against the synced
      * JWKS, which is the only thing a deployment whose keys are all verify-only can do.
      */
    signingKeyId: Option[String],
    /** RFC 7523 §3: how far into the future a `private_key_jwt` client assertion's `exp` may
      * sit. A `jti` has to be remembered for as long as the assertion bearing it is still
      * acceptable, so this is also the retention the replay guard is sized on -- raising it
      * costs storage there, and lowering it refuses assertions from clients that mint
      * long-lived ones.
      */
    clientAssertionMaxLifetimeSeconds: Int,
) derives Schema, JsonCodec

object ChallengeSettingsRecord:
  /** Five minutes: what client libraries mint by default, and short enough that the replay
    * guard's retention is measured in minutes rather than hours. */
  val DefaultClientAssertionMaxLifetimeSeconds = 300

  /** The window the replay guard in `auth` is sized to cover. A tenant cannot ask for a
    * longer one, because a `jti` it could no longer remember for the assertion's whole life
    * is a replay it could no longer detect. */
  val MaxClientAssertionMaxLifetimeSeconds = 900

  /** Below this, ordinary clock skew between a client and this server starts refusing
    * assertions that were honestly minted. */
  val MinClientAssertionMaxLifetimeSeconds = 30
