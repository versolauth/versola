package versola.util

import com.nimbusds.jose.crypto.{ECDSASigner, RSASSASigner}
import com.nimbusds.jose.jwk.{Curve, ECKey, JWKSet, RSAKey}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader, JWSObject, JWSSigner, Payload}
import zio.*
import zio.json.ast.Json
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import scala.jdk.CollectionConverters.*

object RequestObjectSpec extends ZIOSpecDefault:

  private val ecKeyPairGenerator = KeyPairGenerator.getInstance("EC")
  ecKeyPairGenerator.initialize(Curve.P_256.toECParameterSpec)
  private val ecKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val ecPrivateKey = ecKeyPair.getPrivate.asInstanceOf[ECPrivateKey]
  private val ecJwk = ECKey.Builder(Curve.P_256, ecKeyPair.getPublic.asInstanceOf[ECPublicKey]).keyID("ec-1").build()

  private val otherEcKeyPair = ecKeyPairGenerator.generateKeyPair()
  private val otherEcPrivateKey = otherEcKeyPair.getPrivate.asInstanceOf[ECPrivateKey]

  private val rsaKeyPairGenerator = KeyPairGenerator.getInstance("RSA")
  rsaKeyPairGenerator.initialize(2048)
  private val rsaKeyPair = rsaKeyPairGenerator.generateKeyPair()
  private val rsaPrivateKey = rsaKeyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  private val rsaJwk = RSAKey.Builder(rsaKeyPair.getPublic.asInstanceOf[RSAPublicKey]).keyID("rsa-1").build()

  private val ClientId = "client-1"
  private val Issuer = "https://auth.example.com"
  private val AuthorizeEndpoint = "https://auth.example.com/authorize"
  private val Audiences = Set(Issuer, AuthorizeEndpoint)

  private val now = Instant.parse("2024-01-01T00:00:00Z")
  private val maxLifetime = 5.minutes

  private def keys(jwks: com.nimbusds.jose.jwk.JWK*): JWT.PublicKeys =
    JWT.PublicKeys(JWKSet(jwks.toList.asJava))

  /** The claims a minimal but complete request object carries: the parameters a plain
    * authorization request would have sent, plus what RFC 9101 §4 adds around them. A named
    * claim replaces the default of that name rather than being appended beside it.
    */
  private def claims(extra: (String, Json)*): Json.Obj =
    val defaults = Chunk(
      "iss" -> Json.Str(ClientId),
      "aud" -> Json.Str(Issuer),
      "exp" -> Json.Num(now.plusSeconds(60).getEpochSecond),
      "client_id" -> Json.Str(ClientId),
      "response_type" -> Json.Str("code"),
      "redirect_uri" -> Json.Str("https://client.example.com/callback"),
      "scope" -> Json.Str("openid"),
    )
    val overridden = extra.map(_._1).toSet
    Json.Obj(defaults.filterNot((name, _) => overridden.contains(name)) ++ Chunk.fromIterable(extra)*)

  private def requestObject(
      payload: Json.Obj = claims(),
      alg: JWSAlgorithm = JWSAlgorithm.ES256,
      signer: JWSSigner = ECDSASigner(ecPrivateKey),
      kid: Option[String] = Some("ec-1"),
      typ: Option[String] = None,
  ): String =
    val headerBuilder = JWSHeader.Builder(alg)
    kid.foreach(headerBuilder.keyID)
    typ.foreach(value => headerBuilder.`type`(JOSEObjectType(value)))
    // Signed as a raw JSON payload rather than through `JWTClaimsSet`, which rounds a date
    // claim to whole seconds -- the claims a client sends are what this suite is about.
    val jws = JWSObject(headerBuilder.build(), Payload(payload.toString))
    jws.sign(signer)
    jws.serialize()

  private val AllAlgorithms = ClientAssertion.Algorithm.values.toSet

  private def verify(
      token: String,
      publicKeys: JWT.PublicKeys = keys(ecJwk),
      clientId: String = ClientId,
      allowedAlgorithms: Set[ClientAssertion.Algorithm] = AllAlgorithms,
  ) =
    RequestObject.verify(token, publicKeys, allowedAlgorithms, clientId, Audiences, now, maxLifetime)

  def spec = suite("RequestObject")(
    suite("verify")(
      test("returns the request parameters of a well-formed object") {
        for result <- verify(requestObject()).either
        yield assertTrue(
          result.map(_.get("response_type")) == Right(Some(Json.Str("code"))),
          result.map(_.get("scope")) == Right(Some(Json.Str("openid"))),
        )
      },
      test("accepts an RSA-signed object, as the registered key set may hold either type") {
        for result <- verify(
            requestObject(alg = JWSAlgorithm.PS256, signer = RSASSASigner(rsaPrivateKey), kid = Some("rsa-1")),
            keys(rsaJwk),
          ).either
        yield assertTrue(result.isRight)
      },
      test("accepts the authorization endpoint as the audience, not only the issuer identifier") {
        for result <- verify(requestObject(claims("aud" -> Json.Str(AuthorizeEndpoint)))).either
        yield assertTrue(result.isRight)
      },
      test("rejects an audience naming some other server") {
        for result <- verify(requestObject(claims("aud" -> Json.Str("https://elsewhere.example")))).either
        yield assertTrue(result == Left(RequestObject.Error.AudienceMismatch))
      },
      test("rejects an object signed by a key the client did not register") {
        for result <- verify(requestObject(signer = ECDSASigner(otherEcPrivateKey))).either
        yield assertTrue(result == Left(RequestObject.Error.InvalidSignature))
      },
      test("rejects a kid naming no registered key rather than trying the rest") {
        for result <- verify(requestObject(kid = Some("ec-unknown"))).either
        yield assertTrue(result == Left(RequestObject.Error.UnknownKey))
      },
      test("rejects an algorithm this deployment does not advertise") {
        for result <- verify(requestObject(), allowedAlgorithms = Set(ClientAssertion.Algorithm.PS256)).either
        yield assertTrue(result == Left(RequestObject.Error.UnsupportedAlgorithm))
      },
      test("rejects anything that is not a signed JWT") {
        for result <- verify("not-a-jwt").either
        yield assertTrue(result == Left(RequestObject.Error.NotJWT))
      },
      test("rejects a client_id claim that contradicts the client_id sent outside the object") {
        for result <- verify(requestObject(claims("client_id" -> Json.Str("other-client")))).either
        yield assertTrue(result == Left(RequestObject.Error.ClientIdMismatch))
      },
      test("rejects an issuer that is not the client the object is for") {
        for result <- verify(requestObject(claims("iss" -> Json.Str("https://third-party.example")))).either
        yield assertTrue(result == Left(RequestObject.Error.IssuerMismatch))
      },
      test("rejects an object carrying sub, which a client assertion is told apart by") {
        for result <- verify(requestObject(claims("sub" -> Json.Str(ClientId)))).either
        yield assertTrue(result == Left(RequestObject.Error.ImpersonatesClientAssertion))
      },
      test("rejects an object that refers to another request object") {
        for
          byValue <- verify(requestObject(claims("request" -> Json.Str("ey.another.object")))).either
          byReference <- verify(requestObject(claims("request_uri" -> Json.Str("urn:example:request")))).either
        yield assertTrue(
          byValue == Left(RequestObject.Error.NestedRequest),
          byReference == Left(RequestObject.Error.NestedRequest),
        )
      },
      test("rejects an expired object") {
        for result <- verify(requestObject(claims("exp" -> Json.Num(now.minusSeconds(1).getEpochSecond)))).either
        yield assertTrue(result == Left(RequestObject.Error.Expired))
      },
      test("rejects an object with no expiry at all") {
        val withoutExp = Json.Obj(claims().fields.filterNot(_._1 == "exp"))
        for result <- verify(requestObject(withoutExp)).either
        yield assertTrue(result == Left(RequestObject.Error.MissingClaim("exp")))
      },
      test("rejects an expiry further ahead than the tenant allows") {
        for result <- verify(requestObject(claims("exp" -> Json.Num(now.plusSeconds(3600).getEpochSecond)))).either
        yield assertTrue(result == Left(RequestObject.Error.LifetimeTooLong))
      },
      test("rejects an object that is not valid yet") {
        for result <- verify(requestObject(claims("nbf" -> Json.Num(now.plusSeconds(30).getEpochSecond)))).either
        yield assertTrue(result == Left(RequestObject.Error.NotYetValid))
      },
      // RFC 7519 §2 allows a fractional NumericDate. Truncating one to whole seconds would
      // start an object's validity up to a second before the client said it began, and end it
      // up to a second after -- so both edges are checked against a fraction.
      test("reads a fractional date as the instant it names rather than the second it sits in") {
        for
          notYetValid <- verify(requestObject(claims("nbf" -> Json.Num(BigDecimal(now.getEpochSecond) + 0.5)))).either
          expired <- verify(requestObject(claims("exp" -> Json.Num(BigDecimal(now.getEpochSecond) - 0.5)))).either
        yield assertTrue(
          notYetValid == Left(RequestObject.Error.NotYetValid),
          expired == Left(RequestObject.Error.Expired),
        )
      },
      test("accepts the explicitly registered type, and refuses a type naming something else") {
        for
          typed <- verify(requestObject(typ = Some(RequestObject.Type))).either
          plain <- verify(requestObject(typ = Some("JWT"))).either
          foreign <- verify(requestObject(typ = Some("dpop+jwt"))).either
        yield assertTrue(typed.isRight, plain.isRight, foreign == Left(RequestObject.Error.UnexpectedType))
      },
    ),
    suite("parameters")(
      test("drops the claims that carry the object rather than a request parameter") {
        val params = RequestObject.parameters(claims())
        assertTrue(
          params.keySet == Set("client_id", "response_type", "redirect_uri", "scope"),
        )
      },
      test("renders a numeric claim as the decimal string a query parameter would have held") {
        val params = RequestObject.parameters(claims("max_age" -> Json.Num(86400)))
        assertTrue(params.get("max_age") == Some(Chunk("86400")))
      },
      test("spreads an array of strings into the repeated parameter it stands for") {
        val params = RequestObject.parameters(claims(
          "resource" -> Json.Arr(Json.Str("https://api.example.com"), Json.Str("https://files.example.com")),
        ))
        assertTrue(params.get("resource") == Some(Chunk("https://api.example.com", "https://files.example.com")))
      },
      test("keeps a JSON-valued parameter as the JSON text a plain request would have sent") {
        val details = Json.Arr(Json.Obj("type" -> Json.Str("payment")))
        val params = RequestObject.parameters(claims("authorization_details" -> details))
        assertTrue(params.get("authorization_details") == Some(Chunk(details.toString)))
      },
      test("drops a null claim rather than turning it into an empty parameter") {
        val params = RequestObject.parameters(claims("prompt" -> Json.Null))
        assertTrue(!params.contains("prompt"))
      },
    ),
    suite("Algorithm.fromMetadata")(
      test("falls back to the default set where the document names none") {
        assertTrue(RequestObject.Algorithm.fromMetadata(Json.Obj()) == RequestObject.Algorithm.Default)
      },
      test("reads the set the document advertises, dropping what no verifier exists for") {
        val document = Json.Obj(
          RequestObject.Algorithm.MetadataField -> Json.Arr(Json.Str("RS256"), Json.Str("HS256")),
        )
        assertTrue(RequestObject.Algorithm.fromMetadata(document) == Set(ClientAssertion.Algorithm.RS256))
      },
      test("is read independently of the algorithms a client assertion may use") {
        val document = Json.Obj(
          RequestObject.Algorithm.MetadataField -> Json.Arr(Json.Str("RS256")),
          ClientAssertion.Algorithm.MetadataField -> Json.Arr(Json.Str("ES256")),
        )
        assertTrue(
          RequestObject.Algorithm.fromMetadata(document) == Set(ClientAssertion.Algorithm.RS256),
          ClientAssertion.Algorithm.fromMetadata(document) == Set(ClientAssertion.Algorithm.ES256),
        )
      },
    ),
  )
