package versola.util

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.{ECDSAVerifier, RSASSAVerifier}
import com.nimbusds.jose.jwk.{Curve, ECKey, JWK, KeyUse, RSAKey}
import com.nimbusds.jwt.SignedJWT
import zio.json.ast.Json
import zio.{Duration, IO, Task, ZIO, durationInt}

import java.security.PrivateKey
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** RFC 7523 §2.2 `private_key_jwt` client authentication: instead of presenting a secret the
  * server could itself have used to impersonate it, the client presents a JWT signed by a key
  * only it holds, which the server verifies against the public keys that client registered.
  *
  * This object performs only the assertion's self-contained checks: JWS structure, signature
  * against the registered key set, and the `iss`/`sub`/`aud`/`exp` claims. `jti` replay
  * detection needs persistence this module has no access to, so -- as with [[Dpop]] -- it is
  * left to the caller, and [[Assertion]] carries the two values that decision needs.
  */
object ClientAssertion:

  /** RFC 7523 §2.2: the only `client_assertion_type` this method is registered under. The
    * request carries it verbatim, so a client that sends anything else is asking for an
    * authentication method that does not exist rather than getting this one by default. */
  val Type = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"

  /** Signing algorithms a client assertion may use. Chosen by the client from the key it
    * registered, so this is independent of [[JWT.Algorithm]] (this server's own token signing).
    *
    * Asymmetric only, and deliberately: an HMAC algorithm here would verify the assertion
    * against a key the server also holds, which is `client_secret_jwt` -- a different
    * authentication method, with none of the non-repudiation this one is chosen for. Leaving
    * it out of the enum keeps it out by construction rather than by a check someone can move.
    */
  enum Algorithm(val jwsAlgorithm: JWSAlgorithm):
    case ES256 extends Algorithm(JWSAlgorithm.ES256)
    case PS256 extends Algorithm(JWSAlgorithm.PS256)
    case RS256 extends Algorithm(JWSAlgorithm.RS256)

    /** The same algorithm as [[JWT.Algorithm]] names it, for [[issue]] -- signing goes through
      * [[JWT.serialize]], which selects its signer from that enum. Total by construction:
      * every case here has a counterpart there. */
    def jwtAlgorithm: JWT.Algorithm = this match
      case ES256 => JWT.Algorithm.ES256
      case PS256 => JWT.Algorithm.PS256
      case RS256 => JWT.Algorithm.RS256

  /** RFC 7591 §2 / RFC 8414 §2: the value `token_endpoint_auth_methods_supported` and a
    * client's own `token_endpoint_auth_method` name this method by. */
  val MethodName = "private_key_jwt"

  object Algorithm:
    /** RFC 8414 §2: the authorization server metadata field naming the set an incoming
      * assertion's `alg` is checked against, exactly as [[Dpop.Algorithm.MetadataField]] does
      * for proofs -- the document is the only place the set is written down, so what clients
      * discover and what they are held to cannot disagree. */
    val MetadataField = "token_endpoint_auth_signing_alg_values_supported"

    /** The set assumed where the metadata document does not name it: `ES256` and `PS256`,
      * matching [[Dpop.Algorithm.Default]]. `RS256` is left out for the same reason -- FAPI
      * disallows it, so a deployment that wants it has to ask. */
    val Default: Set[Algorithm] = Set(ES256, PS256)

    def fromJws(alg: JWSAlgorithm): Option[Algorithm] = values.find(_.jwsAlgorithm == alg)

    def fromName(name: String): Option[Algorithm] = values.find(_.toString == name)

    /** The set an incoming assertion's `alg` is checked against, read off the authorization
      * server metadata document. An algorithm the document names but this object has no
      * verifier for is dropped, so an assertion can never be refused for an `alg` the
      * deployment advertised; a field naming nothing recognizable derives to an empty set and
      * `private_key_jwt` goes unusable rather than quietly falling back to [[Default]] and
      * accepting the very algorithms the operator took the trouble to exclude. Only an absent
      * or unreadable field falls back. See [[Dpop.Algorithm.fromMetadata]], which this
      * mirrors.
      */
    def fromMetadata(document: Json.Obj): Set[Algorithm] =
      document.get(MetadataField) match
        case None => Default
        case Some(field) => field.as[Set[String]].toOption.fold(Default)(_.flatMap(fromName))

  /** Mints an assertion authenticating `clientId` to `audience`.
    *
    * The counterpart of [[verify]], for a caller acting as the client rather than as the
    * server -- edge authenticating to auth for a client it fronts (`versola.edge.SSOClient`).
    * Kept beside [[verify]] so the two cannot drift: every claim required there is set here,
    * and `jti`/`iat`/`exp` come from [[JWT.serialize]], which mints a fresh `jti` per call.
    *
    * @param audience what the receiving server accepts as `aud`. RFC 7523 §3 names the token
    *   endpoint's URL; the issuer identifier is also accepted in the wild, and [[verify]]
    *   takes either -- so the caller states which one it is sending rather than this guessing.
    */
  def issue(
      clientId: String,
      audience: String,
      algorithm: Algorithm,
      keyId: String,
      privateKey: PrivateKey,
      ttl: Duration = Ttl,
  ): Task[String] =
    JWT.serialize(
      claims = JWT.Claims(
        issuer = clientId,
        subject = clientId,
        audience = List(audience),
        custom = Json.Obj(),
      ),
      ttl = ttl,
      signature = JWT.Signature.Asymmetric(
        algorithm = algorithm.jwtAlgorithm,
        keyId = keyId,
        privateKey = privateKey,
      ),
    )

  /** How long an issued assertion stays valid. Short because it is minted per request and
    * never cached: the only thing a longer life buys is a wider replay window for anything
    * that observed one. Well inside the `maxLifetime` [[verify]] is called with.
    */
  val Ttl: Duration = 1.minute

  /** The `sub` the assertion names, read without verifying anything.
    *
    * RFC 7523 §3 makes `sub` the client the assertion authenticates, and RFC 7521 §4.2 lets
    * the request omit `client_id` when the assertion carries it -- so the client whose keys
    * the signature is checked against has to be read off the assertion before there is any
    * key to check it with. Nothing is trusted on the strength of this: it selects a key set,
    * and [[verify]] then requires the same value to survive signature verification.
    */
  def subject(token: String): Option[String] =
    Try(SignedJWT.parse(token).getJWTClaimsSet.getSubject).toOption.flatMap(Option(_))

  /** The assertion's already-validated claims that the caller still needs.
    *
    * @param jti needs checking against server state by the caller (replay detection).
    * @param expiresAt how long that record has to be kept: past it the assertion is refused by
    *   `exp` alone, so remembering the `jti` any longer guards nothing. Taken from the signed
    *   claim rather than from the clock, so a captured assertion cannot be replayed into a
    *   record that outlives the original's.
    */
  case class Assertion(jti: String, expiresAt: Instant)

  enum Error:
    case NotJWT
    case UnsupportedAlgorithm
    case UnknownKey
    case InvalidSignature
    case MissingClaim(name: String)
    case MalformedClaim(name: String)
    case IssuerMismatch
    case AudienceMismatch
    case Expired
    case NotYetValid
    case LifetimeTooLong

  /** Verifies a client assertion's self-contained properties. Does not check `jti` replay --
    * see `versola.oauth.clientauth.ClientAssertionService`.
    *
    * @param token the raw `client_assertion` form parameter
    * @param keys the public keys the client registered; the assertion is verified against
    *   these alone, never against a key the assertion itself carries -- an assertion is
    *   authentication, so a key arriving with it would be the caller vouching for itself
    * @param allowedAlgorithms signing algorithms this deployment accepts
    *   ([[Algorithm.MetadataField]])
    * @param clientId the client being authenticated, which RFC 7523 §3 requires both `iss` and
    *   `sub` to name
    * @param acceptedAudiences values the `aud` claim may name: the issuer identifier and the
    *   endpoint URL the request reached. OpenID Connect Core §9 specifies the endpoint, the
    *   OAuth security BCP the issuer, and clients in the wild send either
    * @param now current time
    * @param maxLifetime furthest into the future `exp` may sit. Bounds how long a `jti` has to
    *   be remembered for, so an assertion minted to expire in a year cannot pin a replay
    *   record for one
    */
  def verify(
      token: String,
      keys: JWT.PublicKeys,
      allowedAlgorithms: Set[Algorithm],
      clientId: String,
      acceptedAudiences: Set[String],
      now: Instant,
      maxLifetime: Duration,
  ): IO[Error, Assertion] =
    for
      jwt <- ZIO.attempt(SignedJWT.parse(token)).orElseFail(Error.NotJWT)
      algorithm <- verifyAlgorithm(jwt, allowedAlgorithms)
      _ <- verifySignature(jwt, keys, algorithm)

      // Nimbus parses the payload lazily: a compact JWS whose payload is not a JSON object is
      // still a well-formed JWS and only fails here, so this needs the same typed guard the
      // parse above has.
      claims <- ZIO.attempt(jwt.getJWTClaimsSet).orElseFail(Error.NotJWT)
      iss <- requireClaim(claims.getIssuer, "iss")
      sub <- requireClaim(claims.getSubject, "sub")
      // Nimbus reads an absent `aud` as an empty list rather than as null, so the emptiness
      // check is what distinguishes "no audience" from "the wrong audience" here.
      audience <- requireClaim(claims.getAudience, "aud").map(_.asScala.toSet)
        .filterOrFail(_.nonEmpty)(Error.MissingClaim("aud"))
      jti <- requireClaim(claims.getJWTID, "jti")
      expiresAt <- requireClaim(claims.getExpirationTime, "exp").map(_.toInstant)
      notBefore <- optionalClaim(claims.getNotBeforeTime, "nbf").map(_.map(_.toInstant))

      // RFC 7523 §3: for client authentication both name the client, which is what makes the
      // assertion an authentication of that client rather than a token about it.
      _ <- ZIO.fail(Error.IssuerMismatch).unless(iss == clientId && sub == clientId)
      _ <- ZIO.fail(Error.AudienceMismatch).when(audience.intersect(acceptedAudiences).isEmpty)

      _ <- ZIO.fail(Error.Expired).unless(expiresAt.isAfter(now))
      _ <- ZIO.fail(Error.LifetimeTooLong).when(expiresAt.isAfter(now.plus(maxLifetime)))
      _ <- ZIO.fail(Error.NotYetValid).when(notBefore.exists(_.isAfter(now)))
    yield Assertion(jti = jti, expiresAt = expiresAt)

  private def requireClaim[A](value: => A, name: String): IO[Error, A] =
    ZIO.attempt(Option(value)).orElseFail(Error.MalformedClaim(name)).someOrFail(Error.MissingClaim(name))

  /** Like [[requireClaim]] but for a claim RFC 7523 allows to be absent -- a present but
    * wrongly typed one is malformed, not absent, so it fails rather than reading as `None`.
    */
  private def optionalClaim[A](value: => A, name: String): IO[Error, Option[A]] =
    ZIO.attempt(Option(value)).orElseFail(Error.MalformedClaim(name))

  private def verifyAlgorithm(jwt: SignedJWT, allowedAlgorithms: Set[Algorithm]): IO[Error, Algorithm] =
    ZIO.fromOption(Option(jwt.getHeader.getAlgorithm).flatMap(Algorithm.fromJws))
      .orElseFail(Error.UnsupportedAlgorithm)
      .filterOrFail(allowedAlgorithms.contains)(Error.UnsupportedAlgorithm)

  /** Verifies against the registered keys the assertion could plausibly have been signed with.
    *
    * A `kid` header narrows the set to the key it names, and a `kid` naming no registered key
    * fails rather than falling through to the rest: the client told us which key it used, and
    * trying the others would accept an assertion it did not claim to have signed with them.
    * RFC 7517 §4.5 leaves `kid` optional, though, and a client with a single registered key
    * has nothing to disambiguate -- so where it is absent every key of a type the algorithm
    * can use is tried.
    *
    * Visible to the module because [[RequestObject]] verifies against the same registered key
    * set under the same rules (RFC 9101 §6.2 states them in the same terms as RFC 7523 does);
    * two copies of this could drift into accepting different things from one key set.
    */
  private[util] def verifySignature(jwt: SignedJWT, keys: JWT.PublicKeys, algorithm: Algorithm): IO[Error, Unit] =
    for
      candidates <- ZIO.attempt(Option(jwt.getHeader.getKeyID) match
        case Some(kid) => Option(keys.keys.getKeyByKeyId(kid)).toList
        case None => keys.keys.getKeys.asScala.toList,
      ).orElseFail(Error.UnknownKey)

      usable = candidates.filter(usableWith(_, algorithm))
      _ <- ZIO.fail(Error.UnknownKey).when(usable.isEmpty)

      verified <- ZIO.attempt(usable.exists(verifyWith(jwt, _)))
        .orElseFail(Error.InvalidSignature)
      _ <- ZIO.fail(Error.InvalidSignature).unless(verified)
    yield ()

  /** True where [[verify]] could ever check a signature against this key, for any algorithm
    * this object implements. Registration validates against it (see
    * [[JsonWebKeySet.validateForAssertions]]) so a key that could only ever fail authentication is refused
    * at the point the operator can still fix it, rather than registering and then never
    * working.
    */
  def canVerifyWith(key: JWK): Boolean = Algorithm.values.exists(usableWith(key, _))

  /** RFC 7517 §4.4: a key that registered an `alg` may only be used with it, which is how a
    * client pins a key to one algorithm. A key that named none is usable with any algorithm
    * its type supports. §4.2: a key registered for encryption never verifies a signature --
    * registration already refuses one, so this only keeps the rule where it is enforced.
    *
    * `ES256` names its curve as well as its hash (RFC 7518 §3.4), so an EC key on any other
    * curve is not merely a worse choice than P-256 -- there is no algorithm here it can be
    * used with at all.
    */
  private def usableWith(key: JWK, algorithm: Algorithm): Boolean =
    val typeMatches = key match
      case _: RSAKey => algorithm == Algorithm.RS256 || algorithm == Algorithm.PS256
      case key: ECKey => algorithm == Algorithm.ES256 && key.getCurve == Curve.P_256
      case _ => false
    typeMatches &&
      Option(key.getAlgorithm).forall(_ == algorithm.jwsAlgorithm) &&
      !Option(key.getKeyUse).contains(KeyUse.ENCRYPTION)

  private def verifyWith(jwt: SignedJWT, key: JWK): Boolean =
    key match
      case key: RSAKey => jwt.verify(RSASSAVerifier(key))
      case key: ECKey => jwt.verify(ECDSAVerifier(key))
      case _ => false
