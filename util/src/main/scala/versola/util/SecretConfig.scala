package versola.util

import zio.Config

import java.security.PrivateKey

/** Decoders for secrets read from configuration; the `DeriveConfig` givens of every service are built on them.
  *
  * A config error ends up in the startup log, the container's stdout and CI output, so it must never carry any part of the secret. Every
  * failure here is therefore a fixed reason, plus the expected and the actual length in bytes where that applies, and nothing is taken from
  * the input or from an exception thrown on it: `java.util.Base64` quotes the offending character in its own message (`Illegal base64
  * character 2b`), and `PrivateKeyUtil` passes on the key factory's message.
  *
  * The name of the secret is not known here. zio-config prefixes the error with the config path of the field being decoded (for example
  * `security.refresh-tokens-secret`), so the rendered error reads `Invalid data at <path>: <reason>`.
  *
  * [[ByteArrayNewType.fromBase64Url]] is deliberately left as it was: request parsers use it and its text may reach callers.
  */
object SecretConfig:

  /** Base64url, as `java.util.Base64.getUrlDecoder` reads it: `=` padding is accepted, a missing one is too. */
  def base64Url(raw: String): Either[Config.Error.InvalidData, Array[Byte]] =
    try Right(java.util.Base64.getUrlDecoder.decode(raw))
    catch case _: IllegalArgumentException => Left(invalid("not valid base64url"))

  /** A secret of any length. */
  def secret(raw: String): Either[Config.Error.InvalidData, Secret] =
    base64Url(raw).map(Secret(_))

  /** A secret that must be exactly `newType.length` bytes once decoded. */
  def fixedLength(newType: ByteArrayNewType.FixedLength)(raw: String): Either[Config.Error.InvalidData, newType.Type] =
    base64Url(raw).flatMap: bytes =>
      if bytes.length == newType.length then Right(newType(bytes))
      else Left(invalid(s"expected ${newType.length} bytes after base64url decoding, got ${bytes.length}"))

  /** A base64-encoded (standard alphabet) PKCS#8 RSA private key. */
  def rsaPrivateKey(raw: String): Either[Config.Error.InvalidData, PrivateKey] =
    PrivateKeyUtil.parse(raw, "RSA").left.map(_ => invalid("not a base64-encoded PKCS#8 RSA private key"))

  /** A base64-encoded (standard alphabet) PKCS#8 private key that auth signs tokens with: RSA (a deployment
    * generated before ES256 became the default) or EC (what `gen-env` generates now). The key factory is
    * chosen by trying RSA, then EC, the way `JwksSyncClient` reads the keys central supplies. The curve is
    * not checked here; a key that is not P-256 never matches an `ES256` JWKS entry, so it is not used.
    */
  def signingPrivateKey(raw: String): Either[Config.Error.InvalidData, PrivateKey] =
    PrivateKeyUtil.parse(raw, "RSA")
      .orElse(PrivateKeyUtil.parse(raw, "EC"))
      .left.map(_ => invalid("not a base64-encoded PKCS#8 RSA or EC private key"))

  private def invalid(reason: String): Config.Error.InvalidData =
    Config.Error.InvalidData(message = reason)
