package versola.loadgen.protocol

import com.nimbusds.jwt.SignedJWT
import versola.util.Dpop
import zio.ZIO
import zio.http.Method
import zio.test.*

import java.time.Instant

object DpopKeysSpec extends ZIOSpecDefault:

  private val seed = "loadgen-spec"

  override def spec = suite("DpopKeys")(
    suite("the pool")(
      // The property the whole derivation exists for: a restarted driver, and every other driver
      // in the fleet, must resolve a user to the key its tokens were already bound to.
      test("derives the same keys from the same seed") {
        for
          first <- DpopKeyPool.derive(seed, 8)
          second <- DpopKeyPool.derive(seed, 8)
        yield assertTrue((0 until 8).forall(index => first.keyFor(index.toLong).jkt == second.keyFor(index.toLong).jkt))
      },
      test("derives different keys from different seeds") {
        for
          mine <- DpopKeyPool.derive(seed, 4)
          theirs <- DpopKeyPool.derive("another-campaign", 4)
        yield assertTrue(mine.keyFor(0L).jkt != theirs.keyFor(0L).jkt)
      },
      test("derives distinct keys within one pool") {
        DpopKeyPool
          .derive(seed, 16)
          .map(pool => assertTrue((0 until 16).map(index => pool.keyFor(index.toLong).jkt).distinct.size == 16))
      },
      // `keyFor` is what a re-shard relies on: the assignment must come from the user id alone,
      // not from which driver is asking or in what order.
      test("assigns a user to one key, by id alone") {
        DpopKeyPool
          .derive(seed, 10)
          .map: pool =>
            assertTrue(
              pool.keyFor(7L).jkt == pool.keyFor(7L).jkt,
              pool.keyFor(7L).jkt == pool.keyFor(107L).jkt,
              pool.keyFor(7L).jkt != pool.keyFor(8L).jkt,
            )
      },
      // Virtual user ids come from a sequence and are positive, but `floorMod` rather than `%` is
      // what keeps this total -- a negative id would index out of bounds with the latter.
      test("assigns a key for a negative id rather than failing") {
        DpopKeyPool.derive(seed, 10).map(pool => assertTrue(pool.keyFor(-3L).jkt == pool.keyFor(7L).jkt))
      },
      test("refuses an empty pool") {
        DpopKeyPool
          .derive(seed, 0)
          .either
          .map(result => assertTrue(result.left.exists(_.isInstanceOf[ProtocolError.Misconfigured])))
      },
      // Pins the derivation against the JDK's EC provider. A provider that consumed the random
      // stream differently would re-key the fleet silently on an image bump, which is exactly the
      // failure the seed exists to prevent -- so it is a build failure instead.
      test("derives a thumbprint the build has seen before") {
        DpopKeyPool.derive("versola-loadgen", 1).map(pool => assertTrue(pool.keyFor(0L).jkt == GoldenThumbprint))
      },
    ),
    // A deployment can restrict `dpop_signing_alg_values_supported` to exclude ES256 (a FAPI 2.0
    // profile requiring PS256, say) -- the whole reason [[versola.loadgen.config.DpopConfig]]
    // takes an algorithm rather than hard-coding one.
    suite("a PS256 pool")(
      // RSA key generation draws a variable number of bytes per candidate prime, so this is the
      // property [[DpopKeyPool.derive]]'s determinism claim actually depends on for this
      // algorithm: the same seed must still resolve a user to the same key across restarts.
      test("derives the same keys from the same seed") {
        for
          first <- DpopKeyPool.derive(seed, 4, Dpop.Algorithm.PS256)
          second <- DpopKeyPool.derive(seed, 4, Dpop.Algorithm.PS256)
        yield assertTrue((0 until 4).forall(index => first.keyFor(index.toLong).jkt == second.keyFor(index.toLong).jkt))
      },
      test("produces a proof the SUT's own verifier accepts under a PS256-only policy") {
        for
          pool <- DpopKeyPool.derive(seed, 1, Dpop.Algorithm.PS256)
          key = pool.keyFor(0L)
          serialized <- key.proof(Method.POST, Token)
          now <- zio.Clock.instant
          proof <- Dpop
            .verify(serialized, Dpop.KeyPolicy(Set(Dpop.Algorithm.PS256), Dpop.KeyPolicy.MinRsaKeySize), Method.POST, Token, now, Leeway)
            .mapError(error => RuntimeException(error.toString))
        yield assertTrue(proof.jkt == key.jkt)
      },
      // The failure this config exists to prevent: a PS256 proof against a policy that only
      // names ES256 is refused for the algorithm, not for anything about the proof itself.
      test("is refused by a policy naming only ES256") {
        for
          pool <- DpopKeyPool.derive(seed, 1, Dpop.Algorithm.PS256)
          serialized <- pool.keyFor(0L).proof(Method.POST, Token)
          now <- zio.Clock.instant
          result <- Dpop.verify(serialized, Dpop.KeyPolicy(Set(Dpop.Algorithm.ES256), Dpop.KeyPolicy.MinRsaKeySize), Method.POST, Token, now, Leeway).either
        yield assertTrue(result == Left(Dpop.Error.UnsupportedAlgorithm))
      },
      // RS256 parses as a name but is not a drivable choice: FAPI disallows it outright, so no
      // compliant deployment's metadata names it, and a driver defaulting to it would exercise an
      // algorithm nothing in production serves.
      test("refuses RS256, which no compliant deployment advertises") {
        DpopKeyPool
          .derive(seed, 1, Dpop.Algorithm.RS256)
          .either
          .map(result => assertTrue(result.left.exists(_.isInstanceOf[ProtocolError.Misconfigured])))
      },
    ),
    suite("a proof")(
      // Verified with the server's own code rather than by reading the claims back: the proof has
      // to satisfy `Dpop.verify`, and a spec that asserted on the fields it happened to set would
      // pass while the SUT refused every request.
      test("satisfies the verifier auth and edge both use") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          key = pool.keyFor(0L)
          serialized <- key.proof(Method.POST, "https://auth.example.test/token")
          now <- zio.Clock.instant
          proof <- Dpop
            .verify(serialized, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.POST, "https://auth.example.test/token", now, Leeway)
            .mapError(error => RuntimeException(error.toString))
        yield assertTrue(proof.jkt == key.jkt, proof.nonce.isEmpty, proof.ath.isEmpty)
      },
      test("carries the ath binding a resource server requires") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          key = pool.keyFor(0L)
          serialized <- key.proof(Method.GET, Resource, Some(AccessToken("at-1")))
          now <- zio.Clock.instant
          proof <- Dpop
            .verify(serialized, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.GET, Resource, now, Leeway)
            .mapError(error => RuntimeException(error.toString))
        yield assertTrue(proof.ath.contains(Dpop.ath("at-1")))
      },
      test("carries the nonce a §9 challenge asked for") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          serialized <- pool.keyFor(0L).proof(Method.POST, Token, None, Some("nonce-1"))
          now <- zio.Clock.instant
          proof <- Dpop
            .verify(serialized, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.POST, Token, now, Leeway)
            .mapError(error => RuntimeException(error.toString))
        yield assertTrue(proof.nonce.contains("nonce-1"))
      },
      // §11.1's premise. Two proofs from one key must not collide, or the second request of every
      // session would be refused as a replay by a correct server.
      test("is unique per call, from the same key") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          key = pool.keyFor(0L)
          proofs <- ZIO.foreach(1 to 32)(_ => key.proof(Method.POST, Token))
          ids = proofs.map(SignedJWT.parse(_).getJWTClaimsSet.getJWTID)
        yield assertTrue(ids.distinct.size == 32)
      },
      // A proof minted for the URL the driver dialled, against a SUT that derives `htu` from its
      // own configured issuer, fails here rather than anywhere a campaign would notice.
      test("is refused against a different endpoint") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          serialized <- pool.keyFor(0L).proof(Method.POST, Token)
          now <- zio.Clock.instant
          result <- Dpop.verify(serialized, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.POST, Resource, now, Leeway).either
        yield assertTrue(result == Left(Dpop.Error.UriMismatch))
      },
      test("is refused against a different method") {
        for
          pool <- DpopKeyPool.derive(seed, 1)
          serialized <- pool.keyFor(0L).proof(Method.POST, Token)
          now <- zio.Clock.instant
          result <- Dpop.verify(serialized, Dpop.KeyPolicy(Dpop.Algorithm.Default, Dpop.KeyPolicy.MinRsaKeySize), Method.GET, Token, now, Leeway).either
        yield assertTrue(result == Left(Dpop.Error.MethodMismatch))
      },
    ),
  )

  private val Leeway = zio.Duration.fromSeconds(30)
  private val Token = "https://auth.example.test/token"
  private val Resource = "https://edge.example.test/resources/core/accounts"

  /** Recorded from this build. See the test that reads it. */
  private val GoldenThumbprint = "DIszMU8_l2uQXm8qJtNNRQXizjlxqpLYdvRgKnwNNiM"
