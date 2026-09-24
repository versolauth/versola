package versola.central.configuration.clients

import zio.json.JsonCodec
import zio.prelude.Equal
import zio.schema.*

/** How a client proves who it is at every endpoint that authenticates one, chosen once at
  * registration and stored rather than inferred.
  *
  * Inferring it from what the record happens to hold -- a secret means confidential, a key
  * set means assertions -- reads the same answer out of two fields that can disagree, and
  * leaves no way to register a client that authenticates with a key set and holds no secret
  * at all: a client with no secret was, by that reading, a public one, which `client_credentials`
  * refuses. Storing the method states it once and lets the secret column mean nothing beyond
  * whether there is a secret.
  *
  * The values are the RFC 7591 `token_endpoint_auth_method` names, minus the distinction
  * between `client_secret_basic` and `client_secret_post`: both carry the same credential,
  * and this server accepts either transport for a client that registered [[client_secret]].
  */
enum AuthMethod derives JsonCodec, Schema, Equal:
  /** RFC 6749 §2.3.1: a shared secret, issued once at registration and rotatable afterwards.
    * The only method that puts anything in the `secret` column. */
  case client_secret

  /** RFC 7523 §2.2: the client signs a client assertion with a key from its registered
    * `jwks`, which is the only thing that authenticates it. */
  case private_key_jwt

  /** RFC 8705 §2.1: the client presents a certificate carrying its registered subject
    * value. */
  case tls_client_auth

  /** RFC 8705 §2.2: the client presents a certificate whose public key is in its registered
    * `jwks`. */
  case self_signed_tls_client_auth

  /** RFC 6749 §2.1 public client: nothing authenticates it beyond the `client_id` it sends,
    * so it is confined to flows that do not rely on client authentication -- PKCE-protected
    * authorization code exchanges, and never `client_credentials`. */
  case none
