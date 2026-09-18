package versola.loadgen.protocol

import com.nimbusds.jose.crypto.{ECDSASigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, RSAKey}
import com.nimbusds.jose.{JWSHeader, JWSSigner}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.util.Dpop
import zio.http.Method
import zio.{Clock, IO, ZIO}

import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.security.{KeyPairGenerator, MessageDigest, PrivateKey, PublicKey, SecureRandom}
import java.util.{Date, UUID}

/** One RFC 9449 client key and the proofs it signs.
  *
  * The header is built once and reused. `alg`, `typ` and the embedded `jwk` are the same on every
  * proof this key ever makes, and serializing the JWK costs a JSON object per proof if it is
  * rebuilt -- on a path that runs once per request for the whole campaign, against a driver CPU
  * ceiling of 40% (design doc §6.3). Only `jti`, `iat`, `htm`/`htu` and the two optional bindings
  * vary, so only they are built per call.
  *
  * `ES256` or `PS256`, per [[DpopConfig.algorithm]] -- [[Dpop.Algorithm.Default]]'s two members,
  * the set auth and edge fall back to when the metadata document names none. A deployment whose
  * served `dpop_signing_alg_values_supported` excludes `ES256` -- a FAPI 2.0 profile requiring
  * `PS256`, say -- has every proof this key makes refused as `invalid_dpop_proof` unless the
  * config names the algorithm that document actually advertises. `RS256` is not offered: FAPI
  * disallows it outright, so a deployment that wants it has to add it to its own metadata, and a
  * driver defaulting to it would exercise an algorithm no compliant deployment serves.
  */
final class DpopKey private (signer: JWSSigner, header: JWSHeader, val jkt: String):

  /** A proof for one request.
    *
    * `htu` must be the URI the SUT derives for itself, not the one this driver dialled -- see
    * [[DpopKeyPool]]'s note on that, which is where the two can diverge.
    *
    * `accessToken` adds the §4.2 `ath` binding, which edge and `/userinfo` both require on a
    * resource call and which `/token` has no token to compute. `nonce` answers a §9 challenge.
    *
    * A signing failure is [[ProtocolError.Misconfigured]] and not a transport or SUT error: "a
    * crypto provider that will not sign" is the case that constructor names, and a campaign whose
    * driver cannot sign is measuring nothing rather than measuring a failure.
    */
  def proof(
      method: Method,
      htu: String,
      accessToken: Option[AccessToken] = None,
      nonce: Option[String] = None,
  ): IO[ProtocolError, String] =
    Clock.instant.flatMap: now =>
      ZIO
        .attempt:
          val claims = JWTClaimsSet.Builder()
            .claim("htm", method.name)
            .claim("htu", htu)
            .jwtID(UUID.randomUUID().nn.toString)
            .issueTime(Date.from(now))
          accessToken.foreach(token => claims.claim("ath", Dpop.ath(token.value)))
          nonce.foreach(value => claims.claim("nonce", value))
          val jwt = SignedJWT(header, claims.build())
          jwt.sign(signer)
          jwt.serialize().nn
        .mapError(cause => ProtocolError.Misconfigured(s"could not sign a DPoP proof: ${cause.getMessage}"))

object DpopKey:
  /** Builds the key from whichever pair [[DpopKeyPool.derive]] generated for `algorithm` -- an EC
    * pair for `ES256`, an RSA pair for `PS256`. The match is exhaustive over what `derive` ever
    * produces; a mismatch here would be that method's bug, not a config it has to validate.
    */
  private[protocol] def of(algorithm: Dpop.Algorithm, privateKey: PrivateKey, publicKey: PublicKey): DpopKey =
    (publicKey, privateKey) match
      case (pub: ECPublicKey, priv: ECPrivateKey) =>
        val jwk = ECKey.Builder(Curve.P_256, pub).build().nn
        val header = JWSHeader.Builder(algorithm.jwsAlgorithm).`type`(Dpop.JwtType).jwk(jwk).build().nn
        DpopKey(ECDSASigner(priv), header, jwk.computeThumbprint().nn.toString)
      case (pub: RSAPublicKey, priv: RSAPrivateKey) =>
        val jwk = RSAKey.Builder(pub).build().nn
        val header = JWSHeader.Builder(algorithm.jwsAlgorithm).`type`(Dpop.JwtType).jwk(jwk).build().nn
        DpopKey(RSASSASigner(priv), header, jwk.computeThumbprint().nn.toString)
      case _ =>
        throw IllegalArgumentException(s"a $algorithm key pair must be EC or RSA, not ${publicKey.getClass}")

/** The fixed set of client keys a DPoP campaign is driven with, and the rule assigning one to a
  * virtual user.
  *
  * **Why the SUT cannot tell.** The replay store is keyed on `(slot, BLAKE3(jkt ‖ jti))` in both
  * auth and edge, and `jti` is fresh per proof, so the digest is uniformly distributed whatever
  * the key cardinality is -- 100 keys and a million produce the same index, the same write
  * pattern and the same verification cost, because every proof is verified against the key it
  * carries regardless of how many times that key has been seen. `dpop_jkt` is stored unindexed.
  * Nothing else in either service is keyed by `jkt`.
  *
  * **Why a pool and not a key per user.** Not primarily CPU, on `ES256`. Measured on this build,
  * a P-256 keygen costs about the same as signing one proof, and a session signs one per
  * request -- so a key per session is roughly a tenth more crypto, not a different order of
  * magnitude. `PS256`'s RSA-2048 keygen is a different story -- roughly three orders of
  * magnitude slower than P-256's, measured here at ~70ms per key against P-256's ~0.1ms -- so on
  * that algorithm the pool is the difference between a boot-time cost paid once for the whole
  * fleet and one paid per login for the life of the campaign.
  *
  * Either way the reason to prefer the pool is persistence, not CPU. A per-session key would
  * have to be *stored*, because the token it binds outlives the process that made it (see
  * below), which is a new column on `vu_sessions` and a write on the login path. A hundred keys
  * derived from a seed need neither and are reproduced anywhere in the fleet for the cost of the
  * derivation.
  *
  * Note what the pool does not save: a proof is still signed per request, since `jti`, `htu` and
  * `iat` all vary. Only keygen is amortized.
  *
  * **Why the keys are derived and not generated.** A user's key must outlive the driver process
  * that first used it. An access token is bound to the key at `/token` (`cnf.jkt`) and every
  * later refresh is checked against that binding, so a pool regenerated on restart -- or a second
  * pool on a second driver -- would fail every resumed session's refresh. Those arrive as
  * `invalid_grant`, which is `loadgen_refresh_rejected_total`, the one counter §7.4 requires to
  * stay at ~0 for the whole run: a driver restart or a re-shard would read as an SUT defect.
  * Deriving every key from one configured seed makes the pool identical in every driver of the
  * fleet and across every restart, so [[keyFor]] is a pure function of the user id and a session
  * survives both.
  *
  * **Why the user id and not the session id.** Both are stable, but the user id exists before the
  * session row does -- `/token` is called before the row is inserted -- and a user's several
  * device sessions (§5's heavy users have four) sharing one key is exactly as valid as a browser
  * and a phone sharing one: the binding is token-to-key, and a key may back any number of tokens.
  */
final class DpopKeyPool private (keys: IndexedSeq[DpopKey]):

  val size: Int = keys.size

  /** Total, and deterministic. Every driver in the fleet resolves a given user to the same key,
    * which is what makes a re-shard mid-campaign (`DriverRegistry`'s drain protocol) invisible to
    * the SUT's `cnf.jkt` check.
    */
  def keyFor(userId: Long): DpopKey = keys(Math.floorMod(userId, size.toLong).toInt)

object DpopKeyPool:

  private val RsaKeySize = 2048

  /** Derives `size` keys from `seed`, `ES256` (P-256) unless `algorithm` names `PS256` (RSA-2048).
    *
    * The derivation runs `KeyPairGenerator` over a `SecureRandom` whose stream is HKDF-style
    * expansion of the seed, rather than deriving the key material directly, which would need a
    * curve-arithmetic or big-integer library this module does not otherwise use. That makes the
    * result a property of the JDK's provider as well as of the seed, so `DpopKeysSpec` pins the
    * first key's thumbprint against a recorded value: a provider that changed how it consumes the
    * stream would silently re-key the fleet, which is the one failure this derivation exists to
    * prevent, and it fails the build instead.
    */
  def derive(seed: String, size: Int, algorithm: Dpop.Algorithm = Dpop.Algorithm.ES256): IO[ProtocolError, DpopKeyPool] =
    if size < 1 then ZIO.fail(ProtocolError.Misconfigured(s"a DPoP key pool needs at least one key, not $size"))
    else if algorithm == Dpop.Algorithm.RS256 then
      ZIO.fail(ProtocolError.Misconfigured("RS256 is not a supported DPoP driver algorithm; use ES256 or PS256"))
    else
      ZIO
        .attempt:
          val generator = algorithm match
            case Dpop.Algorithm.ES256 => KeyPairGenerator.getInstance("EC").nn
            case _ => KeyPairGenerator.getInstance("RSA").nn
          val keys = IndexedSeq.tabulate(size): index =>
            val random = DerivedRandom(seed, index)
            algorithm match
              case Dpop.Algorithm.ES256 => generator.initialize(Curve.P_256.toECParameterSpec, random)
              case _ => generator.initialize(RsaKeySize, random)
            val pair = generator.generateKeyPair().nn
            DpopKey.of(algorithm, pair.getPrivate.nn, pair.getPublic.nn)
          DpopKeyPool(keys)
        .mapError(cause => ProtocolError.Misconfigured(s"could not derive the DPoP key pool: ${cause.getMessage}"))

/** A `SecureRandom` whose output is `SHA-256(seed ‖ index ‖ counter)`, blockwise.
  *
  * Overriding `nextBytes` is the whole contract a `KeyPairGenerator` uses. Nothing here is
  * required to be unpredictable -- these keys authenticate a load emulator's virtual users
  * against a test deployment, and reproducibility is the property being bought.
  */
private final class DerivedRandom(seed: String, index: Int) extends SecureRandom:
  private val digest = MessageDigest.getInstance("SHA-256").nn
  private var counter = 0L

  override def nextBytes(bytes: Array[Byte]): Unit =
    var written = 0
    while written < bytes.length do
      digest.reset()
      digest.update(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      digest.update(java.nio.ByteBuffer.allocate(12).nn.putInt(index).nn.putLong(counter).nn.array().nn)
      val block = digest.digest().nn
      val take = Math.min(block.length, bytes.length - written)
      System.arraycopy(block, 0, bytes, written, take)
      written += take
      counter += 1
