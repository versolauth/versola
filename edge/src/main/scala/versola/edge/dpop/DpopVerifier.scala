package versola.edge.dpop

import versola.edge.EdgeConfig
import versola.util.{Base64, Dpop, DpopNonce}
import zio.http.{Method, Path, QueryParams, Request, URL}
import zio.{Clock, IO, ZIO, ZLayer}

import java.security.MessageDigest
import java.time.Instant

/** RFC 9449 §7.1: validates the DPoP proof accompanying a sender-constrained access token on a
  * proxied API call.
  *
  * Runs the checks [[Dpop.verify]] cannot do on its own -- the `ath` binding to the presented
  * token, the `cnf.jkt` binding to the key the token was issued against, nonce freshness and
  * replay -- and rebuilds the `htu` the proof is compared against from configuration rather
  * than from the request's own `Host`.
  */
trait DpopVerifier:
  /** @param proofHeader the raw `DPoP` request header value
    * @param accessToken the access token presented alongside it, hashed for the `ath` check
    * @param boundKeyThumbprint `cnf.jkt` from that token's claims
    * @param method the proxied request's method
    * @param path the proxied request's path, as the client addressed it
    */
  def verify(
      proofHeader: String,
      accessToken: String,
      boundKeyThumbprint: String,
      method: Method,
      path: Path,
  ): IO[DpopVerifier.Error, Dpop.Proof]

object DpopVerifier:

  /** RFC 9449: both the authentication scheme of a sender-constrained request and the name of
    * the header carrying its proof. */
  val Scheme = "DPoP"

  enum Error:
    /** §7.1: `error="invalid_dpop_proof"`. */
    case InvalidProof(reason: Dpop.Error)
    /** The `DPoP` scheme was used without a `DPoP` header to go with it. */
    case ProofMissing
    /** §4.3(1): "the request contains at most one DPoP header field value". */
    case MultipleProofs
    case AthMismatch
    case AthMissing
    case KeyMismatch
    case Replayed
    /** §9: `error="use_dpop_nonce"`, carrying one for the client to echo back. */
    case NonceRequired(nonce: String)
    /** No `dpop` block in the edge's configuration, so a proof cannot be checked at all. A
      * `DPoP`-scheme request is refused rather than waved through. */
    case NotConfigured

  /** RFC 9449 §4.3(1): a second `DPoP` header must refuse the request, not be dropped in
    * favor of the first. `Request.rawHeader` picks a single value off `Headers.get`, which
    * silently discards every other occurrence -- exactly what would let a proof smuggled in
    * behind a valid one go unseen. `rawHeaders` surfaces all of them, so cardinality can be
    * checked before any proof is read.
    */
  def proofHeader(request: Request): IO[Error, String] =
    request.rawHeaders(Scheme) match
      case values if values.isEmpty => ZIO.fail(Error.ProofMissing)
      case values if values.size > 1 => ZIO.fail(Error.MultipleProofs)
      case values => ZIO.succeed(values.head)

  def live: ZLayer[EdgeConfig & DpopReplayGuard, Nothing, DpopVerifier] =
    ZLayer.fromFunction(Impl(_, _))

  class Impl(config: EdgeConfig, replayGuard: DpopReplayGuard) extends DpopVerifier:

    override def verify(
        proofHeader: String,
        accessToken: String,
        boundKeyThumbprint: String,
        method: Method,
        path: Path,
    ): IO[Error, Dpop.Proof] =
      config.dpop match
        case None => ZIO.fail(Error.NotConfigured)
        case Some(dpop) =>
          for
            now <- Clock.instant
            proof <- Dpop.verify(
              token = proofHeader,
              allowedAlgorithms = dpop.allowedAlgorithms,
              expectedMethod = method,
              expectedUri = htu(dpop.publicUrl, path),
              now = now,
              iatLeeway = dpop.iatLeeway,
            ).mapError(Error.InvalidProof.apply)

            // §7: the proof must name the token it accompanies. Without this a proof captured
            // from one request could be paired with any other token held by the same client.
            ath <- ZIO.fromOption(proof.ath).orElseFail(Error.AthMissing)
            _ <- ZIO.fail(Error.AthMismatch).unless(constantTimeEquals(ath, athOf(accessToken)))

            // §6.1/§7.1: and it must be signed with the key the token was bound to at issuance.
            _ <- ZIO.fail(Error.KeyMismatch)
              .unless(constantTimeEquals(proof.jkt, boundKeyThumbprint))

            _ <- checkNonce(dpop, proof, now)

            fresh <- replayGuard.recordIfAbsent(proof.jkt, proof.jti, proof.iat)
            _ <- ZIO.fail(Error.Replayed).unless(fresh)
          yield proof

    /** §4.3 step 10: the nonce claim is consulted only where this deployment requires one.
      *
      * Where it does not, the claim is ignored and no nonce is ever issued. §11.3 forbids
      * accepting a nonce-less proof from a client that has been handed a nonce, and a
      * stateless resource server cannot tell which client that was -- so a challenge raised
      * over an unrequired nonce would be one a nonce-less retry then walks straight past,
      * which is the downgrade §11.3 names. Handing out no nonce at all is the only coherent
      * choice without per-client state, and matches what `require-nonce = false` says: this
      * edge does not use nonces.
      */
    private def checkNonce(
        dpop: EdgeConfig.Dpop,
        proof: Dpop.Proof,
        now: Instant,
    ): IO[Error, Unit] =
      if !dpop.requireNonce then ZIO.unit
      else
        proof.nonce match
          case Some(nonce) =>
            DpopNonce.verify(dpop.nonceSalt, nonce, now, dpop.nonceTtl) match
              case Right(_) => ZIO.unit
              case Left(_) => freshNonceRequired(dpop, now)
          // §11.3: never accepted once a nonce has been issued, and in this mode one always
          // has been -- every refusal here carries a fresh one.
          case None => freshNonceRequired(dpop, now)

    private def freshNonceRequired(dpop: EdgeConfig.Dpop, now: Instant): IO[Error, Nothing] =
      ZIO.fail(Error.NonceRequired(DpopNonce.issue(dpop.nonceSalt, now)))

  /** §4.2: `ath` is base64url(SHA-256(ASCII(access token))). */
  private def athOf(accessToken: String): String =
    Base64.urlEncode(
      MessageDigest.getInstance("SHA-256")
        .digest(accessToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
    )

  /** §4.3: the target URI with no query or fragment. Built from the configured public origin
    * and the request's own path, so a forwarded `Host` cannot change what is compared.
    */
  private def htu(publicUrl: URL, path: Path): String =
    publicUrl.copy(path = path, queryParams = QueryParams.empty, fragment = None).encode

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(
      a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      b.getBytes(java.nio.charset.StandardCharsets.UTF_8),
    )
