package versola.oauth.client.model

import zio.json.JsonCodec
import zio.prelude.Equal

/** How a client proves who it is at every endpoint that authenticates one, as Central stored
  * it at registration.
  *
  * This is what decides which credential is in force -- not the presence of a `secret`, a
  * `jwks` or an `mtlsAuth`. Those columns say what the client registered; this says what is
  * read. Registration holds the two in agreement, so the difference only shows in the one
  * case the columns cannot express: a confidential client that keeps no secret at all.
  *
  * The values are the RFC 7591 `token_endpoint_auth_method` names, minus the distinction
  * between `client_secret_basic` and `client_secret_post`: both carry the same credential,
  * and either transport authenticates a client that registered [[client_secret]].
  */
enum AuthMethod derives JsonCodec, CanEqual, Equal:
  /** RFC 6749 §2.3.1: a shared secret, presented in the Basic header or the form body. */
  case client_secret

  /** RFC 7523 §2.2: a client assertion signed with a key from the client's `jwks`. */
  case private_key_jwt

  /** RFC 8705 §2.1: a certificate carrying the subject value the client registered. */
  case tls_client_auth

  /** RFC 8705 §2.2: a certificate whose public key is in the client's `jwks`. */
  case self_signed_tls_client_auth

  /** RFC 6749 §2.1 public client: the `client_id` alone, which authenticates nothing -- the
    * flows it is left with are the ones that do not depend on client authentication. */
  case none
