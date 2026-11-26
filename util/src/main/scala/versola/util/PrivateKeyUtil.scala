package versola.util

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey}
import java.util.Base64
import scala.util.Try

trait PrivateKeyUtil:
  def parse(key: String, algorithm: "RSA" | "EC"): Either[Throwable, PrivateKey]

object PrivateKeyUtil extends PrivateKeyUtil:

  override def parse(key: String, algorithm: "RSA" | "EC"): Either[Throwable, PrivateKey] =
    Try {
      KeyFactory
        .getInstance(algorithm)
        .generatePrivate(
          PKCS8EncodedKeySpec(
            // Both alphabets are read: central shows a key it generated as base64url (`-` and `_`) and configuration
            // has always held the standard one (`+` and `/`). The two share no character that means different things,
            // so mapping one onto the other cannot turn a valid key into another.
            Base64.getDecoder.decode(
              key
                .replaceAll(" ", "")
                .replaceAll("\n", "")
                .replace('-', '+')
                .replace('_', '/'),
            ),
          ),
        )
    }.toEither
