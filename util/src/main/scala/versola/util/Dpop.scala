package versola.util

import com.nimbusds.jose.crypto.{ECDSAVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{ECKey, JWK, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jwt.SignedJWT
import zio.http.Method
import zio.json.*
import zio.json.ast.Json
import zio.prelude.Equal
import zio.schema.Schema
import zio.{Duration, IO, ZIO}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.util.Try

/** RFC 9449 DPoP proof JWTs.
  *
  * A DPoP proof is a JWS whose header carries the client's own public key (`jwk`) rather than a
  * `kid` pointing at a key this server issued -- the server has never seen the key before and
  * verifies the proof entirely against the key the proof itself carries. This object only
  * performs those self-contained checks: JWS structure, `typ`, signature against the embedded
  * key, and the `htm`/`htu`/`iat` binding to the current request. `jti` replay detection and
  * `nonce` validity need persistence/secrets this module doesn't have access to, so they live in
  * `versola.oauth.dpop.DpopService`.
  */
object Dpop:

  /** The `typ` header RFC 9449 \u00a74.2 requires on every DPoP proof, distinguishing it from an
    * ordinary JWT so one can't be replayed as the other.
    */
  val JwtType: JOSEObjectType = JOSEObjectType("dpop+jwt")

  /** Signing algorithms a DPoP proof may use. Chosen by the client based on the key it holds, so
    * this is independent of [[JWT.Algorithm]] (which governs this server's own token signing).
    * RFC 9449 \u00a75 requires `ES256` support; `PS256` is included for FAPI 2.0 deployments.
    * `RS256` is supported here too -- whether it's actually accepted is a deployment choice,
    * named by [[Algorithm.MetadataField]].
    */
  enum Algorithm(val jwsAlgorithm: JWSAlgorithm):
    case ES256 extends Algorithm(JWSAlgorithm.ES256)
    case PS256 extends Algorithm(JWSAlgorithm.PS256)
    case RS256 extends Algorithm(JWSAlgorithm.RS256)

  object Algorithm:
    /** RFC 8414 §2 / RFC 9449 §5.1: the authorization server metadata field naming the set an
      * incoming proof's `alg` is checked against. That document is the only place the set is
      * written down, so what clients are told and what they are held to cannot disagree. */
    val MetadataField = "dpop_signing_alg_values_supported"

    /** The set assumed where the metadata document does not name it: `ES256` because §5
      * mandates it, `PS256` for FAPI 2.0. `RS256` is left out -- FAPI disallows it outright, so
      * a deployment that wants it has to ask. */
    val Default: Set[Algorithm] = Set(ES256, PS256)

    def fromJws(alg: JWSAlgorithm): Option[Algorithm] = values.find(_.jwsAlgorithm == alg)

    def fromName(name: String): Option[Algorithm] = values.find(_.toString == name)

    /** By registered `alg` name rather than by ordinal: the value travels to central's
      * database and back through the client sync response, and a name survives reordering the
      * enum where a position does not. */
    given JsonCodec[Algorithm] =
      JsonCodec(
        JsonEncoder[String].contramap(_.toString),
        JsonDecoder[String].mapOrFail(fromName(_).toRight("unknown DPoP signing algorithm")),
      )

    given Schema[Algorithm] = Schema.primitive[String].transformOrFail(
      fromName(_).toRight("unknown DPoP signing algorithm"),
      algorithm => Right(algorithm.toString),
    )

    given Equal[Algorithm] = (a, b) => a == b

    given CanEqual[Algorithm, Algorithm] = CanEqual.derived

    /** The set an incoming proof's `alg` is checked against, read off the authorization server
      * metadata document -- [[MetadataField]] is the only place it is written down, so `auth`
      * (which serves the document) and `edge` (which syncs it) hold proofs to the same set
      * clients discover.
      *
      * An algorithm the document names but [[Dpop.verify]] has no verifier for is dropped, so a
      * proof can never be refused for an `alg` the deployment advertised. A field that names
      * nothing recognizable therefore derives to an empty set and DPoP goes unusable: the
      * operator asked for algorithms none of which exist here, and quietly substituting
      * [[Default]] would accept the very keys they took the trouble to exclude. Only a field
      * that is absent, or too malformed to read an intent off at all, falls back.
      */
    def fromMetadata(document: Json.Obj): Set[Algorithm] =
      document.get(MetadataField) match
        case None => Default
        case Some(field) => field.as[Set[String]].toOption.fold(Default)(_.flatMap(fromName))

  /** What a proof's key itself has to be, on top of the `alg` it was signed with.
    *
    * The signature verifying says nothing about whether the key is worth binding a token to:
    * Nimbus' `RSASSAVerifier` accepts a modulus of any length, so a proof signed with a 512-bit
    * RSA key validates and yields a `cnf.jkt` that constrains nothing an attacker could not
    * reproduce. `ECDSAVerifier` already pins the curve for `ES256`, so only the RSA side needs
    * a floor.
    *
    * @param algorithms the `alg` values a proof may use -- `dpop_signing_alg_values_supported`,
    *   narrowed to what the presenting client registered where it registered anything.
    * @param minRsaKeySize modulus length, in bits, an `RS256`/`PS256` proof's key must reach.
    */
  case class KeyPolicy(algorithms: Set[Algorithm], minRsaKeySize: Int)

  object KeyPolicy:
    /** RFC 7518 §3.3 requires at least 2048 bits of any key used with `RS`/`PS` algorithms, so
      * this is the floor rather than a deployment's opinion -- a client may register a higher
      * one, never a lower. */
    val MinRsaKeySize: Int = 2048

  /** RFC 9449 §10: the authorization request parameter by which a client commits, before a code
    * exists, to the key that code will be redeemed against. Its value is the same RFC 7638
    * thumbprint [[Proof.jkt]] carries, so the two are compared as-is at the token endpoint.
    */
  object Jkt:
    val Parameter = "dpop_jkt"

    /** A SHA-256 thumbprint base64url-encoded without padding: 43 characters of the URL-safe
      * alphabet. Checked rather than accepted verbatim so a value that could never equal a
      * proof's `jkt` is refused at `/authorize`, where the client can still be told why,
      * instead of at redemption, where the code is already spent.
      *
      * 43 base64 characters carry 258 bits, two more than the 256 a SHA-256 digest has, so the
      * last character's low 2 bits are unused. `computeThumbprint` always emits them as zero (the
      * only canonical encoding), which restricts that character to one of 16 symbols rather than
      * the full alphabet -- a value with anything else there decodes fine but can never equal a
      * canonical thumbprint's *string* form, so unlike this check the equality at redemption
      * (`OAuthTokenService`, byte-for-byte string comparison, not decode-and-compare) would never
      * pass, permanently stranding the code it was requested against.
      */
    private val Pattern = "[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]".r

    def parse(value: String): Option[String] = Option.when(Pattern.matches(value))(value)

  /** The proof's self-contained, already-validated claims.
    *
    * @param jkt RFC 7638 JWK thumbprint of the proof's embedded public key -- what an issued
    *   access token's `cnf.jkt` gets bound to.
    * @param jti still needs checking against server state by the caller (replay detection).
    * @param nonce still needs checking against server state by the caller (freshness).
    * @param ath base64url(SHA-256(access token)), present only on resource requests; the caller
    *   is responsible for requiring and checking it there.
    */
  case class Proof(
      jkt: String,
      jti: String,
      iat: Instant,
      nonce: Option[String],
      ath: Option[String],
  )

  enum Error:
    case NotJWT
    case InvalidType
    case UnsupportedAlgorithm
    case MissingJwk
    case WeakKey
    case InvalidSignature
    case MissingClaim(name: String)
    case MalformedClaim(name: String)
    case MethodMismatch
    case UriMismatch
    case IatOutOfWindow

  /**
   * Verifies a DPoP proof's self-contained properties against the current request: JWS
   * structure and `typ`, signature against its own embedded public key, and the `htm`/`htu`/
   * `iat` binding. Does not check `jti` replay or `nonce` validity -- see
   * `versola.oauth.dpop.DpopService`.
   *
   * @param token the raw `DPoP` request header value
   * @param keyPolicy what the proof may be signed with, and what its key must be
   * @param expectedMethod the current request's HTTP method (`htm`)
   * @param expectedUri the current request's URI with no query or fragment, exactly as
   *   advertised to clients (`htu`) -- RFC 9449 \u00a74.3
   * @param now current time
   * @param iatLeeway maximum allowed distance between `iat` and `now`, in either direction
   */
  def verify(
      token: String,
      keyPolicy: KeyPolicy,
      expectedMethod: Method,
      expectedUri: String,
      now: Instant,
      iatLeeway: Duration,
  ): IO[Error, Proof] =
    for
      jwt <- ZIO.attempt(SignedJWT.parse(token)).orElseFail(Error.NotJWT)
      _ <- verifyType(jwt)
      _ <- verifyAlgorithm(jwt, keyPolicy.algorithms)
      // Nimbus's own JWS header parsing already rejects a `jwk` carrying private/symmetric key
      // material at the `SignedJWT.parse` call above (surfacing as `Error.NotJWT`), so by the
      // time a header reaches here its embedded key is guaranteed public.
      jwk <- ZIO.fromOption(Option(jwt.getHeader.getJWK)).orElseFail(Error.MissingJwk)
      _ <- verifyKeyStrength(jwk, keyPolicy.minRsaKeySize)
      _ <- verifySignature(jwt, jwk)

      // Nimbus lazily parses the payload into a claims set; a compact JWS whose payload isn't a
      // JSON object (still a valid JWS otherwise, e.g. signed by the caller's own valid key)
      // throws here rather than at `parse` above, so this needs the same typed guard.
      claims <- ZIO.attempt(jwt.getJWTClaimsSet).orElseFail(Error.NotJWT)
      htm <- requireClaim(claims.getStringClaim("htm"), "htm")
      htu <- requireClaim(claims.getStringClaim("htu"), "htu")
      jti <- requireClaim(claims.getStringClaim("jti"), "jti")
      iatDate <- requireClaim(claims.getDateClaim("iat"), "iat")

      _ <- ZIO.fail(Error.MethodMismatch).unless(htm == expectedMethod.name)
      // RFC 9449 §4.3 step 9: compare against the request URI "ignoring any query and
      // fragment parts" -- normalization is this side's job, since a compliant client is free
      // to stamp `htu` with the full URL it actually called, query string and all.
      _ <- ZIO.fail(Error.UriMismatch).unless(normalizeHtu(htu).contains(expectedUri))

      iat = iatDate.toInstant
      _ <- ZIO.fail(Error.IatOutOfWindow)
        .unless(!iat.isBefore(now.minus(iatLeeway)) && !iat.isAfter(now.plus(iatLeeway)))

      jkt <- ZIO.attempt(jwk.computeThumbprint().toString).orElseFail(Error.MissingJwk)
      nonce <- optionalClaim(claims.getStringClaim("nonce"), "nonce")
      ath <- optionalClaim(claims.getStringClaim("ath"), "ath")
    yield Proof(
      jkt = jkt,
      jti = jti,
      iat = iat,
      nonce = nonce,
      ath = ath,
    )

  /** Strips the query and fragment from an `htu` claim before comparing it against
    * `expectedUri`, per RFC 9449 §4.3 step 9. `None` if `htu` isn't a valid URI at all, which
    * simply fails the comparison rather than the whole proof crashing.
    *
    * Built from the *raw* (still percent-encoded) authority and path, not `getAuthority`/
    * `getPath`, which decode reserved octets on the way out. `/` is reserved precisely because
    * it is the path separator (RFC 3986 section 2.2/3.3): decoding a `%2F` and handing it to a
    * `URI(scheme, authority, path, query, fragment)` constructor -- which re-encodes from
    * already-decoded components and treats a literal `/` in `path` as a separator, not
    * something to re-escape -- collapses `/a%2Fb` (one segment, "a/b") and `/a/b` (two
    * segments) into the same comparison string. A proof stamped for one would then validate a
    * request whose target is the other, exactly the confusion `htu` exists to rule out.
    */
  private def normalizeHtu(htu: String): Option[String] =
    Try(new URI(htu)).toOption.filter(_.getScheme != null).map: uri =>
      s"${uri.getScheme}://${Option(uri.getRawAuthority).getOrElse("")}${Option(uri.getRawPath).getOrElse("")}"

  /** RFC 9449 §4.2: `ath` is base64url(SHA-256(ASCII(access token))) -- what a resource
    * request's proof `ath` claim is checked against. Shared by every resource server that
    * enforces DPoP (edge, and auth's own `/userinfo`) so the computation has one definition.
    */
  def ath(accessToken: String): String =
    Base64.urlEncode(
      MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII)),
    )

  private def requireClaim[A](value: => A, name: String): IO[Error, A] =
    ZIO.attempt(Option(value)).orElseFail(Error.MalformedClaim(name)).someOrFail(Error.MissingClaim(name))

  /** Like [[requireClaim]] but for a claim RFC 9449 allows to be absent -- a present-but-wrongly-
    * typed claim (e.g. `nonce` as a JSON object) is malformed, not absent, so it fails rather
    * than silently reading as `None`.
    */
  private def optionalClaim[A](value: => A, name: String): IO[Error, Option[A]] =
    ZIO.attempt(Option(value)).orElseFail(Error.MalformedClaim(name))

  private def verifyType(jwt: SignedJWT): IO[Error, Unit] =
    ZIO.attempt(Option(jwt.getHeader.getType))
      .orElseFail(Error.InvalidType)
      .someOrFail(Error.InvalidType)
      .filterOrFail(_ == JwtType)(Error.InvalidType)
      .unit

  private def verifyAlgorithm(jwt: SignedJWT, allowedAlgorithms: Set[Algorithm]): IO[Error, Unit] =
    ZIO.fromOption(Option(jwt.getHeader.getAlgorithm).flatMap(Algorithm.fromJws))
      .orElseFail(Error.UnsupportedAlgorithm)
      .filterOrFail(allowedAlgorithms.contains)(Error.UnsupportedAlgorithm)
      .unit

  /** Checked before the signature rather than after: verifying against a key this deployment
    * will not accept anyway only spends CPU on an attacker's behalf. */
  private def verifyKeyStrength(jwk: JWK, minRsaKeySize: Int): IO[Error, Unit] =
    jwk match
      case key: RSAKey => ZIO.fail(Error.WeakKey).when(key.size() < minRsaKeySize).unit
      case _ => ZIO.unit

  private def verifySignature(jwt: SignedJWT, jwk: JWK): IO[Error, Unit] =
    ZIO.attempt(
      jwk match
        case key: RSAKey => jwt.verify(RSASSAVerifier(key))
        case key: ECKey => jwt.verify(ECDSAVerifier(key))
        case _ => false,
    )
      .orElseFail(Error.InvalidSignature)
      .filterOrFail(identity)(Error.InvalidSignature)
      .unit
