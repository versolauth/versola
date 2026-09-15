package versola.oauth.userinfo.model

enum UserInfoError:
  case InvalidToken
  case InsufficientScope
  case Unauthorized
  /** RFC 9449 §7.1/§7.2: the request's `DPoP` scheme was used but the accompanying proof is
    * missing, malformed, or fails one of its bindings -- including a `DPoP`-scheme request for
    * a token that isn't key-bound, and a `Bearer`-scheme request for one that is (the downgrade
    * §7.2 exists to refuse). `reason` is logged only; the response is uniform across causes so
    * it can't be used to distinguish them.
    */
  case InvalidDpopProof(reason: String)
  /** RFC 9449 §9: carries a freshly issued nonce for the client to retry with. */
  case UseDpopNonce(nonce: String)

