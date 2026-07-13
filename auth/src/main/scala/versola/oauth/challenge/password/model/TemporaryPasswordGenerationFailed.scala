package versola.oauth.challenge.password.model

final case class TemporaryPasswordGenerationFailed(attempts: Int)
    extends RuntimeException(
      s"Could not generate a temporary password satisfying the configured policy after $attempts attempts",
    )
