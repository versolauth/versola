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
   * @param requireNonce RFC 9449 §9: when true, a request without a valid, fresh nonce
   *   fails with [[DpopService.Error.NonceRequired]] carrying a freshly issued one for the
   *   caller to return via the `DPoP-Nonce` response header. When false the nonce claim is
   *   not consulted at all -- see `checkNonce`.
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

    /** §4.3 step 10: the nonce claim is consulted only where the endpoint requires one.
      *
      * Where it does not, the claim is ignored and no nonce is ever issued. §11.3 forbids
      * accepting a nonce-less proof from a client that has been handed a nonce, and nothing
      * here is per-client state -- so a challenge raised over an unrequired nonce would be
      * one a nonce-less retry then walks straight past, which is the downgrade §11.3 names.
      * Issuing no nonce at all is the only coherent choice, and is what an endpoint not
      * requiring one says in the first place.
      */
    private def checkNonce(
        proof: Dpop.Proof,
        requireNonce: Boolean,
        now: Instant,
    ): IO[Throwable | Error, Unit] =
      if !requireNonce then ZIO.unit
      else
        proof.nonce match
          case Some(nonce) =>
            nonceService.verify(nonce, now).foldZIO(
              _ => freshNonceRequired,
              _ => ZIO.unit,
            )
          // §11.3: never accepted once a nonce has been issued, and in this mode one always
          // has been -- every refusal here carries a fresh one.
          case None =>
            freshNonceRequired

    private def freshNonceRequired: IO[Throwable | Error, Nothing] =
      nonceService.issue.flatMap(nonce => ZIO.fail(Error.NonceRequired(nonce)))
