package versola.util

import com.nimbusds.jwt.SignedJWT
import zio.json.*
import zio.json.ast.Json
import zio.{Chunk, Duration, IO, ZIO}

import java.time.Instant
import scala.util.Try

/** RFC 9101 JWT-Secured Authorization Request (JAR): the authorization request parameters
  * arrive as the claims of a JWT the client signed, instead of as query parameters a user
  * agent could have tampered with on the way.
  *
  * The signature is checked against the same registered JWK Set `private_key_jwt` uses -- see
  * [[JsonWebKeySet]] -- so a client that can authenticate with a key can sign a request with
  * it, and [[ClientAssertion.Algorithm]] is the algorithm set for both. That reuse is also
  * what makes RFC 9101 §10.8's cross-JWT confusion a live concern here rather than a
  * theoretical one, which is why [[verify]] refuses an object shaped like an assertion.
  *
  * Only the by-value `request` parameter is implemented. `request_uri` in this server always
  * names a pushed authorization request (RFC 9126), never a document to be fetched over the
  * network: JAR §10.4 makes that fetch a request-forgery and denial-of-service surface, and
  * PAR obtains the same request object over an authenticated back channel instead.
  */
object RequestObject:

  /** RFC 9101 §5: the request parameter carrying the object by value. */
  val Parameter = "request"

  /** RFC 9101 §9.4.1: the media type, and so the `typ` header of an explicitly typed object.
    * Accepted but not required -- §10.8 notes that requiring it breaks clients that have been
    * sending untyped objects since OpenID Connect Core -- and a `typ` naming anything else is
    * refused rather than ignored.
    */
  val Type = "oauth-authz-req+jwt"

  /** Header and JWT claims that carry the object itself rather than an authorization request
    * parameter. RFC 9101 §9.1 registers each as a request parameter too, to reserve the name;
    * none of them is a parameter this server reads, and passing them on as one would let a
    * request object introduce a parameter no plain request could.
    */
  private val ControlClaims = Set("iss", "sub", "aud", "exp", "nbf", "iat", "jti")

  /** RFC 9396 `authorization_details` is a JSON array in the request object and a JSON string
    * in a plain request. It cannot follow the repeated-parameter rule the other arrays here
    * follow (`resource`), so it is re-serialized rather than spread.
    */
  private val JsonValuedParameters = Set("authorization_details", "claims")

  object Algorithm:
    /** RFC 8414 §2: the metadata field naming the algorithms a request object may be signed
      * with, read off the served document for the same reason the DPoP and client assertion
      * sets are -- advertising the set and enforcing it stay one decision.
      */
    val MetadataField = "request_object_signing_alg_values_supported"

    /** The set assumed where the document names none: the two FAPI permits, as with
      * [[ClientAssertion.Algorithm.Default]]. */
    val Default: Set[ClientAssertion.Algorithm] = ClientAssertion.Algorithm.Default

    def fromMetadata(document: Json.Obj): Set[ClientAssertion.Algorithm] =
      document.get(MetadataField) match
        case None => Default
        case Some(field) =>
          field.as[Set[String]].toOption.fold(Default)(_.flatMap(ClientAssertion.Algorithm.fromName))

  enum Error:
    case NotJWT
    case UnsupportedAlgorithm
    case UnknownKey
    case InvalidSignature
    case UnexpectedType
    case MissingClaim(name: String)
    case MalformedClaim(name: String)
    case IssuerMismatch
    case ClientIdMismatch
    case AudienceMismatch
    case Expired
    case NotYetValid
    case LifetimeTooLong
    case NestedRequest
    case ImpersonatesClientAssertion

  /** Verifies a request object and returns the authorization request parameters it carries.
    *
    * @param token the raw `request` parameter
    * @param keys the client's registered public keys, the only ones the signature is checked
    *   against -- a key travelling with the object would be the request vouching for itself
    * @param allowedAlgorithms the algorithms this deployment advertises ([[Algorithm.MetadataField]])
    * @param clientId the client named by the `client_id` parameter outside the object, which
    *   RFC 9101 §6.3 requires the object's own `client_id` claim to match
    * @param acceptedAudiences the issuer identifier and the authorization endpoint's URL; §4
    *   names the issuer, and clients in the wild send either
    * @param now current time
    * @param maxLifetime furthest into the future `exp` may sit, bounding how long an observed
    *   object stays replayable
    */
  def verify(
      token: String,
      keys: JWT.PublicKeys,
      allowedAlgorithms: Set[ClientAssertion.Algorithm],
      clientId: String,
      acceptedAudiences: Set[String],
      now: Instant,
      maxLifetime: Duration,
  ): IO[Error, Json.Obj] =
    for
      jwt <- ZIO.attempt(SignedJWT.parse(token)).orElseFail(Error.NotJWT)
      algorithm <- verifyAlgorithm(jwt, allowedAlgorithms)
      _ <- verifyType(jwt)
      _ <- ClientAssertion.verifySignature(jwt, keys, algorithm).mapError {
        case ClientAssertion.Error.UnknownKey => Error.UnknownKey
        case _ => Error.InvalidSignature
      }

      // Nimbus parses the payload lazily, so a well-formed JWS whose payload is not a JSON
      // object only fails here rather than at the parse above.
      claims <- ZIO.attempt(jwt.getPayload.toString).orElseFail(Error.NotJWT)
        .flatMap(payload => ZIO.fromEither(payload.fromJson[Json.Obj]).orElseFail(Error.NotJWT))

      // RFC 9101 §4: a request object states the request, so it cannot in turn refer to
      // another one -- §10.7 makes this the same concern as the client_id match below.
      _ <- ZIO.fail(Error.NestedRequest).when(claims.get(Parameter).isDefined || claims.get("request_uri").isDefined)

      // §6.3: the client_id outside and the claim inside name the same client, so a captured
      // object cannot be presented under another client's identity.
      objectClientId <- requireString(claims, "client_id")
      _ <- ZIO.fail(Error.ClientIdMismatch).unless(objectClientId == clientId)

      // §4: `iss` is the client for a client-signed object. A request object may also be
      // signed by a third party attesting to the request (§1(d)), which this server has no
      // key set for -- so the only issuer it can verify is the client itself.
      iss <- requireString(claims, "iss")
      _ <- ZIO.fail(Error.IssuerMismatch).unless(iss == clientId)

      // §10.8: an object carrying `sub` = the client is indistinguishable from an RFC 7523
      // client assertion, and both are verified against the same registered keys -- so one
      // observed in a URL could be replayed as client authentication at the token endpoint.
      _ <- ZIO.fail(Error.ImpersonatesClientAssertion).when(claims.get("sub").isDefined)

      audience <- requireAudience(claims)
      _ <- ZIO.fail(Error.AudienceMismatch).when(audience.intersect(acceptedAudiences).isEmpty)

      // RFC 9101 leaves `exp` optional. It is required here: an object with no expiry is a
      // signed instruction that stays valid for as long as the client's key does, and it
      // travels through a user agent's history and referrers.
      expiresAt <- requireInstant(claims, "exp")
      _ <- ZIO.fail(Error.Expired).unless(expiresAt.isAfter(now))
      _ <- ZIO.fail(Error.LifetimeTooLong).when(expiresAt.isAfter(now.plus(maxLifetime)))

      notBefore <- optionalInstant(claims, "nbf")
      _ <- ZIO.fail(Error.NotYetValid).when(notBefore.exists(_.isAfter(now)))
    yield claims

  /** The authorization request parameters a verified object's claims stand for, in the shape
    * a plain query or form request would have produced.
    */
  def parameters(claims: Json.Obj): Map[String, Chunk[String]] =
    claims.fields.iterator
      .filterNot((name, _) => ControlClaims.contains(name))
      .flatMap((name, value) => parameter(name, value).map(name -> _))
      .toMap

  /** A claim as the values the parameter it stands for would have had. A repeated parameter
    * (`resource`) is an array of strings; a parameter whose value is itself JSON keeps its
    * JSON text, which is what a plain request would have carried.
    */
  private def parameter(name: String, value: Json): Option[Chunk[String]] =
    value match
      case Json.Str(string) => Some(Chunk(string))
      case Json.Num(number) => Some(Chunk(BigDecimal(number).bigDecimal.stripTrailingZeros.toPlainString))
      case Json.Bool(bool) => Some(Chunk(bool.toString))
      case Json.Arr(elements) if !JsonValuedParameters.contains(name) && elements.forall(_.isInstanceOf[Json.Str]) =>
        Some(Chunk.fromIterable(elements.collect { case Json.Str(string) => string }))
      case Json.Null => None
      case json => Some(Chunk(json.toJson))

  private def verifyAlgorithm(jwt: SignedJWT, allowed: Set[ClientAssertion.Algorithm]): IO[Error, ClientAssertion.Algorithm] =
    ZIO.fromOption(Option(jwt.getHeader.getAlgorithm).flatMap(ClientAssertion.Algorithm.fromJws))
      .orElseFail(Error.UnsupportedAlgorithm)
      .filterOrFail(allowed.contains)(Error.UnsupportedAlgorithm)

  private def verifyType(jwt: SignedJWT): IO[Error, Unit] =
    ZIO.attempt(Option(jwt.getHeader.getType).map(_.getType))
      .orElseFail(Error.UnexpectedType)
      .filterOrFail(_.forall(typ => typ == Type || typ == "JWT" || typ == "jwt"))(Error.UnexpectedType)
      .unit

  private def requireString(claims: Json.Obj, name: String): IO[Error, String] =
    ZIO.fromOption(claims.get(name)).orElseFail(Error.MissingClaim(name))
      .flatMap(json => ZIO.fromEither(json.as[String]).orElseFail(Error.MalformedClaim(name)))

  /** RFC 7519 §4.1.3 allows `aud` to be one string or an array of them. */
  private def requireAudience(claims: Json.Obj): IO[Error, Set[String]] =
    ZIO.fromOption(claims.get("aud")).orElseFail(Error.MissingClaim("aud"))
      .flatMap:
        case Json.Str(single) => ZIO.succeed(Set(single))
        case json =>
          ZIO.fromEither(json.as[Set[String]]).orElseFail(Error.MalformedClaim("aud"))
      .filterOrFail(_.nonEmpty)(Error.MissingClaim("aud"))

  private def requireInstant(claims: Json.Obj, name: String): IO[Error, Instant] =
    ZIO.fromOption(claims.get(name)).orElseFail(Error.MissingClaim(name)).flatMap(instant(_, name))

  private def optionalInstant(claims: Json.Obj, name: String): IO[Error, Option[Instant]] =
    claims.get(name) match
      case None => ZIO.none
      case Some(json) => instant(json, name).asSome

  /** RFC 7519 §2: a `NumericDate` is seconds since the epoch, and may be fractional. */
  private def instant(json: Json, name: String): IO[Error, Instant] =
    ZIO.fromEither(json.as[Double]).orElseFail(Error.MalformedClaim(name))
      .flatMap(seconds => ZIO.fromOption(Try(Instant.ofEpochSecond(seconds.toLong)).toOption).orElseFail(Error.MalformedClaim(name)))
