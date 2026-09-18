package versola.oauth.clientauth

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.OAuthClientRecord
import versola.util.ClientAssertion
import zio.{Clock, IO, ZIO, ZLayer}

/** Orchestrates RFC 7523 client assertion validation for a single request: delegates the
  * assertion's self-contained checks to [[ClientAssertion.verify]], then enforces replay
  * protection via [[ClientAssertionRepository]].
  *
  * The algorithms an assertion may be signed with come from the authorization server metadata
  * document, and the lifetime it may claim from the client's tenant -- neither is read from
  * configuration here, so what clients discover and what an assertion is held to stay one
  * value. Mirrors `versola.oauth.dpop.DpopService`, which does the same for DPoP proofs.
  */
trait ClientAssertionService:
  /** @param client the client the assertion claims to be, whose registered keys are the only
    *   ones it is verified against -- a client with no registered key set can never be
    *   authenticated this way and is refused before any verification happens
    * @param acceptedAudiences the issuer identifier and the URL of the endpoint the request
    *   reached; RFC 7523 §3 requires `aud` to name the server, and clients differ on which of
    *   the two they use
    */
  def verify(
      client: OAuthClientRecord,
      assertion: String,
      acceptedAudiences: Set[String],
  ): IO[Throwable | ClientAssertionService.Error, Unit]

object ClientAssertionService:
  enum Error:
    case NoRegisteredKeys
    case Invalid(reason: ClientAssertion.Error)
    case Replayed

  def live: ZLayer[ClientAssertionRepository & OAuthConfigurationService, Nothing, ClientAssertionService] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(
      repository: ClientAssertionRepository,
      configurationService: OAuthConfigurationService,
  ) extends ClientAssertionService:

    override def verify(
        client: OAuthClientRecord,
        assertion: String,
        acceptedAudiences: Set[String],
    ): IO[Throwable | Error, Unit] =
      for
        now <- Clock.instant

        keys <- ZIO.fromEither(
          client.jwks.toRight(Error.NoRegisteredKeys).flatMap(_.publicKeys.left.map(_ => Error.NoRegisteredKeys)),
        ).tapError {
          // A stored key set that will not parse is a registration that got past validation,
          // not a caller's mistake -- the client is refused either way, so the operator only
          // learns about it from here.
          case Error.NoRegisteredKeys if client.jwks.nonEmpty =>
            ZIO.logWarning(s"Couldn't parse the registered JWK Set of ${client.id}")
          case _ => ZIO.unit
        }

        allowedAlgorithms <- configurationService.getClientAssertionSigningAlgorithms
        maxLifetime <- configurationService.getClientAssertionMaxLifetime(client.id)

        verified <- ClientAssertion.verify(
          token = assertion,
          keys = keys,
          allowedAlgorithms = allowedAlgorithms,
          clientId = client.id,
          acceptedAudiences = acceptedAudiences,
          now = now,
          maxLifetime = maxLifetime,
        ).mapError(Error.Invalid.apply)

        fresh <- repository.recordIfAbsent(client.id, verified.jti, verified.expiresAt)
        _ <- ZIO.fail(Error.Replayed).unless(fresh)
      yield ()
