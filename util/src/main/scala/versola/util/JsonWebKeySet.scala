package versola.util

import com.nimbusds.jose.jwk.{ECKey, JWKSet, KeyUse, RSAKey}
import zio.json.*
import zio.json.ast.Json
import zio.prelude.Equal
import zio.schema.Schema

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** An RFC 7517 §5 JWK Set a client registered, holding the public keys it authenticates with.
  *
  * Stored as the document the client registered rather than as parsed key material: a JWK
  * carries members this server has no opinion on, and re-serializing from a parsed form would
  * quietly drop the ones it does not model. Validation is therefore a check applied to the
  * document, not a narrowing of it -- see [[JsonWebKeySet.validate]].
  *
  * Shared by every method that authenticates a client against keys it registered: RFC 7523
  * `private_key_jwt` today, and RFC 8705 §2.2 `self_signed_tls_client_auth` when it lands,
  * which matches a certificate against this same set.
  */
case class JsonWebKeySet(document: Json.Obj):
  /** Parses the document into verification keys. Fails only where the stored document is not a
    * key set at all, which registration validation rules out -- so a failure here means the
    * column was written by something other than registration.
    */
  def publicKeys: Either[String, JWT.PublicKeys] =
    Try(JWT.PublicKeys(JWKSet.parse(document.toJson))).toEither.left
      .map(error => s"not a valid JWK Set: ${error.getMessage}")

object JsonWebKeySet:
  /** Bounds the signature verifications one unauthenticated request can cost. An assertion
    * without a `kid` is tried against every key of a usable type (RFC 7517 §4.5 leaves `kid`
    * optional), so an unbounded set would let a client register arbitrarily expensive work and
    * then trigger it with a garbage signature.
    */
  val MaxKeys = 10

  /** Accepts a document only if every key in it can actually authenticate the client.
    *
    * Private key material is refused rather than ignored: a client pasting its private key
    * into a field labelled "public keys" has published that key, and storing it -- even
    * unused -- would leave the operator believing otherwise. Symmetric keys are refused for
    * the same reason they are absent from [[ClientAssertion.Algorithm]]: a shared key is
    * `client_secret_jwt`, a different method whose security properties this one is chosen to
    * avoid.
    */
  def validate(document: Json.Obj): Either[String, JsonWebKeySet] =
    for
      parsed <- Try(JWKSet.parse(document.toJson)).toEither.left
        .map(error => s"must be a JWK Set: ${error.getMessage}")
      keys = parsed.getKeys.asScala.toList
      _ <- Either.cond(keys.nonEmpty, (), "must contain at least one key")
      _ <- Either.cond(keys.sizeIs <= MaxKeys, (), s"must not contain more than $MaxKeys keys")
      _ <- Either.cond(!keys.exists(_.isPrivate), (), "must contain public keys only")
      _ <- Either.cond(
        keys.forall(key => key.isInstanceOf[RSAKey] || key.isInstanceOf[ECKey]),
        (),
        "must contain only RSA or EC keys",
      )
      _ <- Either.cond(
        !keys.exists(key => Option(key.getKeyUse).contains(KeyUse.ENCRYPTION)),
        (),
        "must not contain keys marked for encryption",
      )
      // A key of the right type can still be one no assertion could be verified against --
      // an EC key on a curve no algorithm here names, or a key whose own `alg` pins it to an
      // algorithm its type cannot perform. Registering it would report a credential the
      // client can never authenticate with.
      _ <- Either.cond(
        keys.forall(ClientAssertion.canVerifyWith),
        (),
        "must contain only keys usable with " +
          ClientAssertion.Algorithm.values.map(_.toString).mkString(", ") +
          " (an EC key must be on P-256)",
      )
      // RFC 7517 §4.5: `kid` is optional for a single key, since there is nothing to
      // disambiguate, but a set that repeats one cannot be indexed by it at all.
      kids = keys.flatMap(key => Option(key.getKeyID))
      _ <- Either.cond(kids.distinct.sizeIs == kids.size, (), "must not repeat a key id")
    yield JsonWebKeySet(document)

  given Schema[JsonWebKeySet] = Schema.primitive[String].transformOrFail(
    string => string.fromJson[Json.Obj].map(JsonWebKeySet(_)),
    keySet => Right(keySet.document.toJson),
  )

  given JsonCodec[JsonWebKeySet] =
    JsonCodec(
      JsonEncoder[Json.Obj].contramap(_.document),
      JsonDecoder[Json.Obj].map(JsonWebKeySet(_)),
    )

  given Equal[JsonWebKeySet] = (a, b) => a == b

  given CanEqual[JsonWebKeySet, JsonWebKeySet] = CanEqual.derived
