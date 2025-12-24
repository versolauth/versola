package versola.util

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey}
import java.util.Base64
import scala.util.Try

trait PrivateKeyUtil:
  def parse(key: String, algorithm: "RSA"): Either[Throwable, PrivateKey]

object PrivateKeyUtil extends PrivateKeyUtil:

  override def parse(key: String, algorithm: "RSA"): Either[Throwable, PrivateKey] =
    Try {
      KeyFactory
        .getInstance(algorithm)
        .generatePrivate(
          PKCS8EncodedKeySpec(
            Base64.getDecoder.decode(
              key
                .replaceAll(" ", "")
                .replaceAll("\n", ""),
            ),
          ),
        )
    }.toEither
