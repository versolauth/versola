package versola.util

import com.nimbusds.jose.jwk.{AsymmetricJWK, ECKey, JWK, KeyUse, RSAKey}
import zio.json.*
import zio.json.ast.Json
import zio.prelude.Equal
import zio.schema.Schema

import java.security.PrivateKey
import scala.util.Try

/** An RFC 7517 private JWK held on behalf of a client, for a party that signs *as* that client
  * rather than verifying it: `versola.edge.SSOClient` authenticating to auth with an RFC 7523
  * assertion, and signing RFC 9101 request objects.
  *
  * The mirror of [[JsonWebKeySet]], which holds the public half the server checks those
  * signatures against, and stored the same way -- as the document, not as parsed key material,
  * so members this server has no opinion on survive the round trip.
  *
  * A single key rather than a set: a signer picks one, and offering a choice would only move
  * the decision to whoever reads it back. `kid` and `alg` are required here for the same
  * reason they are optional there -- the verifier can try every key it holds, but the signer
  * has to name in the header which one it used and how.
  */
case class PrivateJsonWebKey(document: Json.Obj):

  /** What [[ClientAssertion.issue]] and [[RequestObject.sign]] need to sign with this key.
    *
    * Fails only where the stored document is not one registration would have accepted, which
    * means it was written by something else -- the same standing assumption
    * [[JsonWebKeySet.publicKeys]] makes.
    */
  def signing: Either[String, PrivateJsonWebKey.Signing] =
    for
      key <- PrivateJsonWebKey.parse(document)
      _ <- PrivateJsonWebKey.structure(key)
      keyId <- Option(key.getKeyID).toRight("has no key id")
      algorithm <- PrivateJsonWebKey.algorithmOf(key)
      privateKey <- key match
        case key: AsymmetricJWK =>
          // Nimbus answers a public-only key with `null` rather than by failing, so the
          // absence has to be read off the result: a `Signing` carrying it would sign nothing
          // and only fail at the first use, far from the document that caused it.
          Try(Option(key.toPrivateKey)).toEither.left
            .map(error => s"has no usable private key: ${error.getMessage}")
            .flatMap(_.toRight("carries no private key"))
        case _ => Left("is not an asymmetric key")
    yield PrivateJsonWebKey.Signing(keyId, algorithm, privateKey)

  /** RFC 7517 §4.5: the `kid` a signature made with this key names, read without requiring the
    * rest of the document to be usable -- for a caller reporting which key is configured
    * rather than one about to sign with it. */
  def keyId: Option[String] =
    PrivateJsonWebKey.parse(document).toOption.flatMap(key => Option(key.getKeyID))

object PrivateJsonWebKey:

  /** The key material and the two header parameters a signature has to carry for the verifier
    * to find its way back to the matching public key. */
  case class Signing(keyId: String, algorithm: ClientAssertion.Algorithm, privateKey: PrivateKey)

  /** Accepts a document this server could actually sign with, and that the holder of the
    * matching public key could verify.
    *
    * Public-only material is refused rather than stored unused: a caller registering it has
    * configured a signer that cannot sign, and finding that out at the first authorization
    * request rather than here would make it look like an outage.
    */
  def validate(document: Json.Obj): Either[String, PrivateJsonWebKey] =
    for
      key <- parse(document)
      _ <- structure(key)
      _ <- Option(key.getKeyID).toRight("must carry a key id")
      _ <- algorithmOf(key)
      _ <- Either.cond(key.isPrivate, (), "must be a private key")
    yield PrivateJsonWebKey(document)

  /** Whether `keySet` publishes the public half of this key, matched by `kid`.
    *
    * Not merely a courtesy check: the assertions and request objects signed with this key are
    * verified against that set alone, so a key with no counterpart there produces signatures
    * the server can only refuse. Matched on `kid` and then on the key material, because a set
    * that carries the right `kid` over a different key is the failure this is most likely to
    * be catching.
    *
    * Compared by RFC 7638 thumbprint rather than by the documents: a thumbprint covers the
    * key material and nothing else, so the two halves may legitimately differ in `alg`, `use`
    * or any member neither side models -- only the key itself has to be the same one.
    */
  def publishedIn(key: PrivateJsonWebKey, keySet: JsonWebKeySet): Either[String, Unit] =
    for
      signing <- key.signing
      parsed <- parse(key.document)
      keys <- keySet.publicKeys
      published <- Option(keys.keys.getKeyByKeyId(signing.keyId))
        .toRight(s"names key id '${signing.keyId}', which the client's jwks does not publish")
      matches <- Try(published.computeThumbprint() == parsed.computeThumbprint()).toEither.left
        .map(error => s"could not be compared with the client's jwks: ${error.getMessage}")
      _ <- Either.cond(
        matches,
        (),
        s"does not match the key the client's jwks publishes under key id '${signing.keyId}'",
      )
    yield ()

  private def parse(document: Json.Obj): Either[String, JWK] =
    Try(JWK.parse(document.toJson)).toEither.left.map(error => s"must be a JWK: ${error.getMessage}")

  /** The constraints that hold whether the document is being registered or read back, so the
    * two cannot come to disagree about what is usable. Mirrors
    * [[JsonWebKeySet.structure]]'s type and use rules from the signing side.
    */
  private def structure(key: JWK): Either[String, Unit] =
    for
      _ <- Either.cond(
        key.isInstanceOf[RSAKey] || key.isInstanceOf[ECKey],
        (),
        "must be an RSA or EC key",
      )
      _ <- Either.cond(
        !Option(key.getKeyUse).contains(KeyUse.ENCRYPTION),
        (),
        "must not be marked for encryption",
      )
    yield ()

  /** RFC 7517 §4.4: `alg` is what the signer puts in the header and the verifier holds the key
    * to. Required rather than inferred from the key type, which does not determine it -- an
    * RSA key signs `RS256` or `PS256`, and guessing would pin a FAPI deployment to the one it
    * excluded.
    */
  private def algorithmOf(key: JWK): Either[String, ClientAssertion.Algorithm] =
    for
      declared <- Option(key.getAlgorithm).toRight("must declare an 'alg'")
      algorithm <- ClientAssertion.Algorithm.fromJws(
        com.nimbusds.jose.JWSAlgorithm.parse(declared.getName),
      ).toRight(
        s"declares an 'alg' of '${declared.getName}', which is not one of " +
          ClientAssertion.Algorithm.values.map(_.toString).mkString(", "),
      )
      _ <- Either.cond(
        ClientAssertion.canVerifyWith(key.toPublicJWK),
        (),
        s"cannot be used with ${algorithm} (an EC key must be on P-256)",
      )
    yield algorithm

  given Schema[PrivateJsonWebKey] = Schema.primitive[String].transformOrFail(
    string => string.fromJson[Json.Obj].map(PrivateJsonWebKey(_)),
    key => Right(key.document.toJson),
  )

  given JsonCodec[PrivateJsonWebKey] =
    JsonCodec(
      JsonEncoder[Json.Obj].contramap(_.document),
      JsonDecoder[Json.Obj].map(PrivateJsonWebKey(_)),
    )

  given Equal[PrivateJsonWebKey] = (a, b) => a == b

  given CanEqual[PrivateJsonWebKey, PrivateJsonWebKey] = CanEqual.derived
