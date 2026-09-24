package versola.edge.model

import versola.util.{PrivateJsonWebKey, Secret}

/** How this edge authenticates to auth as a client it fronts.
  *
  * Both arrive the same way — central holds the credential and hands it to the edge serving
  * the client's tenant, encrypted to that edge's registered RSA public key (see
  * `versola.edge.OAuthClientsSyncClient`). Which one a client gets is central's registration,
  * not a choice made here.
  *
  * Modelled as a choice rather than as two optional fields because a client authenticates one
  * way: holding both would leave every caller to decide which, and the decision would be made
  * differently in at least one of them.
  */
enum ClientCredential:
  /** RFC 6749 §2.3.1: the shared secret, presented as HTTP Basic. */
  case ClientSecret(secret: Secret)

  /** RFC 7523 §2.2 `private_key_jwt`: an assertion signed with a key only the client — and the
    * edge fronting it — holds. The same key signs this client's RFC 9101 request objects,
    * which auth verifies against the same registered key set. */
  case PrivateKeyJwt(key: PrivateJsonWebKey.Signing)

  /** Whether this credential can sign a request object. Only the key can: a request object is
    * verified against the client's registered public keys, and a secret is not one of them. */
  def signingKey: Option[PrivateJsonWebKey.Signing] = this match
    case ClientSecret(_) => None
    case PrivateKeyJwt(key) => Some(key)
