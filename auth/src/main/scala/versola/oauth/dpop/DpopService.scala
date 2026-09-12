package versola.oauth.dpop

import versola.util.{CoreConfig, Dpop}
import zio.http.Method
import zio.{Clock, IO, ZIO, ZLayer}

import java.time.Instant

/** Orchestrates RFC 9449 DPoP proof validation for a single request: delegates the proof's
  * self-contained checks to [[Dpop.verify]], then enforces replay protection via
  * [[DpopProofRepository]] and, when the caller requires it, a fresh server nonce via
  * [[DpopNonceService]].
  */
trait DpopService:
  /**
   * @param token the raw `DPoP` request header value
   * @param method the current request's HTTP method
   * @param uri the current request's URI, scheme+host+path only (see [[Dpop.verify]])
   * @param requireNonce RFC 9449 \u00a79: when true, a request without a valid, fresh nonce
   *   fails with [[DpopService.Error.NonceRequired]] carrying a freshly issued one for the
   *   caller to return via the `DPoP-Nonce` response header.
   */
  def verify(
      token: String,
      method: Method,
      uri: String,
      requireNonce: Boolean,
  ): IO[Throwable | DpopService.Error, Dpop.Proof]

object DpopService:
  enum Error:
    case InvalidProof(reason: Dpop.Error)
    case Replayed
    case NonceRequired(nonce: String)

  def live: ZLayer[DpopProofRepository & DpopNonceService & CoreConfig, Nothing, DpopService] =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
      proofRepository: DpopProofRepository,
      nonceService: DpopNonceService,
      config: CoreConfig,
  ) extends DpopService:

    override def verify(
        token: String,
        method: Method,
        uri: String,
        requireNonce: Boolean,
    ): IO[Throwable | Error, Dpop.Proof] =
      val dpopConfig = config.dpopOrDefault
      for
        now <- Clock.instant

        proof <- Dpop.verify(
          token = token,
          allowedAlgorithms = dpopConfig.allowedAlgorithms,
          expectedMethod = method,
          expectedUri = uri,
          now = now,
          iatLeeway = dpopConfig.iatLeeway,
        ).mapError(Error.InvalidProof.apply)

        _ <- checkNonce(proof, requireNonce, now)

        fresh <- proofRepository.recordIfAbsent(proof.jkt, proof.jti, proof.iat)
        _ <- ZIO.fail(Error.Replayed).unless(fresh)
      yield proof

    private def checkNonce(
        proof: Dpop.Proof,
        requireNonce: Boolean,
        now: Instant,
    ): IO[Throwable | Error, Unit] =
      proof.nonce match
        case Some(nonce) =>
          nonceService.verify(nonce, now).foldZIO(
            _ => freshNonceRequired,
            _ => ZIO.unit,
          )
        case None if requireNonce =>
          freshNonceRequired
        case None =>
          ZIO.unit

    private def freshNonceRequired: IO[Throwable | Error, Nothing] =
      nonceService.issue.flatMap(nonce => ZIO.fail(Error.NonceRequired(nonce)))
