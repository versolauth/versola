package versola.loadgen.protocol

import java.nio.charset.StandardCharsets

/** A PKCE pair (RFC 7636, S256): the verifier the `/token` exchange sends and the challenge
  * `/authorize` was started with.
  */
case class Pkce(verifier: CodeVerifier, challenge: String)

/** Ported from `e2e/.../support/PkceHelper.scala` with the fix versola-loadgen-dev-spec.md §3.1
  * asks for: the single shared `SecureRandom`/`MessageDigest` are thread-locals in [[Entropy]],
  * since one `/authorize` per login at 30k rps makes both a contention point.
  */
object Pkce:
  def generate(): Pkce =
    val verifier = Entropy.base64Url(Entropy.secureBytes(32))
    Pkce(CodeVerifier(verifier), Entropy.base64Url(Entropy.sha256(verifier.getBytes(StandardCharsets.US_ASCII))))
