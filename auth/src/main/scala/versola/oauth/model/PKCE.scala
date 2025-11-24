package versola.oauth.model

import versola.util.StringNewType

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum CodeChallengeMethod:
  case S256, Plain

type CodeVerifier = CodeVerifier.Type

object CodeVerifier extends StringNewType {

  // https://datatracker.ietf.org/doc/html/rfc7636#section-4.1
  private val regex = "[a-zA-Z0-9~.\\-_]{43,128}".r

  def from(s: String): Either[String, CodeVerifier] =
    Either.cond(regex.matches(s), apply(s), "invalid code verifier")

  val example: CodeVerifier = CodeVerifier(
    "CAf4vLXr-lVEB8Qwr6a9njjggfqhuAo2I-WoZTKthKYE5qIfMN_HlNvCtyw320hdt7OS1GmfyqpoBPx_EhWubLzUWnb9mq_5CYncvBpE9I6wBb3xIsuLSdxbbUUS7roJ",
  )
}

type CodeChallenge = CodeChallenge.Type
object CodeChallenge:
  opaque type Type <: String = String
  inline def apply(s: String): CodeChallenge = s

  private val regex = "^[A-Za-z0-9_-]{43}".r

  def from(s: String): Either[String, CodeChallenge] =
    Either.cond(regex.matches(s), apply(s), "not a code challenge")
