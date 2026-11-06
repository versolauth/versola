package versola.oauth.userinfo

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.clientauth.ClientAuthentication
import versola.oauth.dpop.DpopService
import versola.oauth.jwks.JwksService
import versola.oauth.model.AccessTokenPayload
import versola.oauth.mtls.EdgeCertificateTrust
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, Dpop, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import scala.jdk.CollectionConverters.*

/**
 * UserInfo endpoint controller
 * OpenID Connect Core 1.0 Section 5.3
 *
 * Endpoints:
 *   - GET /userinfo
 *   - POST /userinfo
 *
 * Authentication: Bearer token (JWT access token) in Authorization header
 * Response: JSON object with user claims
 */
object UserInfoController extends Controller:
  type Env = Tracing & UserInfoService & JwksService & CoreConfig & DpopService & EdgeCertificateTrust &
    OAuthConfigurationService &
    ClientAuthentication

  private val DpopHeader = "DPoP"

  /** RFC 9449 §4.3 compares a proof's `htu` against the endpoint's own URI, which -- this
    * endpoint being served at an RFC 8705 §5 alias too -- depends on the listener the request
    * arrived at. Matching `TokenEndpointController`'s `tokenEndpointUri`, whose doc comment
    * explains why a certificate on the request is what tells the two apart. */
  private def userInfoEndpointUri(config: CoreConfig, request: Request): String =
    config.endpointUri(request.remoteCertificate.isDefined, "/userinfo")

  /** Which RFC 9449 §7.1 / RFC 6750 scheme the caller presented its token under. The token
    * itself is the same either way; the scheme decides whether a proof is demanded. */
  private enum AuthScheme:
    case Bearer, Dpop

  def routes: Routes[Env, Throwable] = Routes(
    userInfoGetEndpoint,
    userInfoPostEndpoint,
  )

  val userInfoGetEndpoint = userInfoEndpoint(Method.GET)

  val userInfoPostEndpoint = userInfoEndpoint(Method.POST)

  private def userInfoEndpoint(method: Method) =
    method / "userinfo" -> handler { (request: Request) =>
      (for
        userInfoService <- ZIO.service[UserInfoService]
        config <- ZIO.service[CoreConfig]
        publicKeys <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys)
        (tokenString, scheme) <- extractToken(request)
        token <- JWT.deserialize[AccessTokenPayload](tokenString, publicKeys, JWT.Type.AccessToken)
          .orElseFail(UserInfoError.InvalidToken)
        _ <- Observability.setToken(token.id.encoded) *> Observability.setClientId(token.clientId)

        userId <- ZIO.fromOption(token.userId).orElseFail(UserInfoError.InvalidToken)
        _ <- Observability.setUserId(userId.toString)

        trustedEdge <- trustedEdgeOf(request, token.clientId)
        _ <- trustedEdge match
          // Edge already verified the holder at its own boundary (see `trustedEdgeOf`): the
          // two checks below are the holder's to pass, and this call is not the holder's.
          case Some(edgeId) =>
            Observability.setRouteLabel("trusted_edge", edgeId) *> refuseBoundToBoth(token)
          case None =>
            checkDpop(request, tokenString, token, scheme, config) *> checkCertificateBinding(request, token)

        _ <- ZIO.fail(UserInfoError.InsufficientScope)
          .unless(token.scope.contains(ScopeToken.OpenId))

        userInfo <- userInfoService.getUserInfo(
          userId = userId,
          clientId = token.clientId,
          scope = token.scope,
          requestedClaims = token.requestedClaims,
        )

        jwtNeeded = request.header(Header.Accept)
          .exists(_.mimeTypes.exists(_.mediaType == MediaType.application.jwt))
        _ <- Observability.setRouteLabel("format", if jwtNeeded then "jwt" else "json")

        response <-
          if !jwtNeeded then
            ZIO.succeed(
              Response(
                status = Status.Ok,
                headers = Headers(Header.ContentType(MediaType.application.json)),
                body = Body.fromString(userInfo.toJsonAST.toJson),
              ),
            )
          else
            for
              // Signed with the key the client's tenant selected, like every other token
              // this server issues.
              client <- ZIO.serviceWithZIO[OAuthConfigurationService](_.get(token.clientId))
              signingKey <- ZIO.serviceWithZIO[JwksService](_.signingKey(client.tenantId))
              signedJwt <- JWT.serialize(
                claims = JWT.Claims(
                  issuer = config.jwt.issuer,
                  subject = userId.toString,
                  audience = List(token.clientId),
                  custom = userInfo.toJsonAST,
                ),
                ttl = 5.minutes,
                signature = signingKey,
              )
            yield Response(
              status = Status.Ok,
              headers = Headers(Header.ContentType(MediaType.application.jwt)),
              body = Body.fromString(signedJwt),
            )
      yield response)
        .catchAll:
          case UserInfoError.InvalidToken =>
            Observability.setError("invalid_token").as:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_token"),
                    errorDescription = Some("The access token is invalid or expired"),
                  ),
                )

          case UserInfoError.InsufficientScope =>
            Observability.setError("insufficient_scope").as:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("insufficient_scope"),
                    errorDescription = Some("The access token does not have sufficient scope"),
                  ),
                )

          case UserInfoError.Unauthorized =>
            Observability.setError("invalid_request").as:
              Response
                .status(Status.Unauthorized)
                .addHeader(
                  Header.WWWAuthenticate.Bearer(
                    realm = "UserInfo",
                    error = Some("invalid_request"),
                    errorDescription = Some("The request is missing a required parameter or is otherwise malformed"),
                  ),
                )

          // RFC 9449 §7.1: `error="invalid_dpop_proof"` under the `DPoP` scheme -- covers a
          // missing/invalid proof, the downgrade of a bound token to `Bearer`, and a `DPoP`
          // request for a token that isn't bound at all.
          case UserInfoError.InvalidDpopProof(_) =>
            Observability.setError("invalid_dpop_proof").as:
              Response
                .status(Status.Unauthorized)
                .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="invalid_dpop_proof""""))

          // RFC 9449 §9: the nonce goes in its own header; the client is expected to retry
          // once with a proof made over it.
          case UserInfoError.UseDpopNonce(nonce) =>
            Observability.setError("dpop_nonce_required").as:
              Response
                .status(Status.Unauthorized)
                .addHeader(Header.Custom("WWW-Authenticate", """DPoP error="use_dpop_nonce""""))
                .addHeader(Header.Custom("DPoP-Nonce", nonce))

          case error: Throwable =>
            ZIO.fail(error)
    }

  private def extractToken(request: Request): IO[UserInfoError, (String, AuthScheme)] =
    ZIO.fromOption:
      request.header(Header.Authorization).collect:
        case Header.Authorization.Bearer(token) => (token.value.asString, AuthScheme.Bearer)
        // zio-http has no `DPoP` case, so the scheme arrives unparsed with the token as its
        // parameters. RFC 9110 §11.1 makes scheme matching case-insensitive.
        case Header.Authorization.Unparsed(scheme, token) if scheme.equalsIgnoreCase(DpopHeader) =>
          (token.stringValue, AuthScheme.Dpop)
    .orElseFail(UserInfoError.Unauthorized)

  /** The edge this call comes from, when it comes from one auth trusts for this token.
    *
    * Only the certificate of the TLS session of auth's own mutual-TLS listener counts -- never one
    * a proxy forwarded in a header, which is an assertion by whatever spoke HTTP to this process --
    * and only when it names an edge central still knows, signed by the issuer configured for edges
    * (`EdgeCertificateTrust`), **and** that edge is assigned to the tenant of the token's client: a
    * genuine edge may not vouch for another tenant's tokens. Anything else is not an edge for this
    * call, and the request is judged as it always was, as a client's.
    */
  private def trustedEdgeOf(
      request: Request,
      clientId: ClientId,
  ): ZIO[EdgeCertificateTrust & OAuthConfigurationService, Nothing, Option[String]] =
    request.remoteCertificate match
      case Some(certificate: X509Certificate) =>
        ZIO.serviceWithZIO[EdgeCertificateTrust](_.edgeOf(certificate)).flatMap:
          case None => ZIO.none
          case Some(edge) =>
            ZIO.serviceWithZIO[OAuthConfigurationService](_.find(clientId))
              .map(_.filter(client => edge.tenantIds.contains(client.tenantId)).map(_ => edge.id))
      case _ => ZIO.none

  /** A token bound to both a key and a certificate is the one an edge cannot vouch for: the key is
    * only half of what the client proved at issuance, and the certificate half is not something
    * an edge's own certificate answers (the same refusal `EdgeService` gives, #452). */
  private def refuseBoundToBoth(token: AccessTokenPayload): IO[UserInfoError, Unit] =
    ZIO.fail(UserInfoError.InvalidToken).when(
      token.confirmation.exists(confirmation => confirmation.jkt.isDefined && confirmation.x5tS256.isDefined),
    ).unit

  /** RFC 9449 §7.1: decides what the presented token and scheme oblige the caller to prove.
    *
    * The `cnf.jkt` claim, not the scheme, is what makes a proof mandatory. A caller choosing
    * `Bearer` for a key-bound token is exactly the downgrade §7.2 exists to refuse. Mirrors
    * `EdgeService.checkDpop`; an edge that has run these checks itself is answered before this
    * is reached (`trustedEdgeOf`).
    */
  private def checkDpop(
      request: Request,
      tokenString: String,
      token: AccessTokenPayload,
      scheme: AuthScheme,
      config: CoreConfig,
  ): ZIO[DpopService & OAuthConfigurationService, Throwable | UserInfoError, Unit] =
    (token.confirmation.flatMap(_.jkt), scheme) match
      case (Some(jkt), AuthScheme.Dpop) =>
        verifyDpopProof(request, tokenString, jkt, token.clientId, config)

      case (Some(_), AuthScheme.Bearer) =>
        ZIO.fail(UserInfoError.InvalidDpopProof("bound token presented with the Bearer scheme"))

      // A proof signed with some key says nothing about a token that was never bound to one:
      // anyone holding the token could have produced it. Treated as invalid rather than
      // falling back to bearer semantics.
      case (None, AuthScheme.Dpop) =>
        ZIO.fail(UserInfoError.InvalidDpopProof("token is not DPoP-bound"))

      case (None, AuthScheme.Bearer) =>
        ZIO.unit

  /** RFC 9449 §8: whether a nonce is compulsory here is the tenant setting of the client the
    * presented token was issued to -- the same answer the token endpoint gives that client, since
    * a requirement one endpoint waives is one a client can route around. */
  private def verifyDpopProof(
      request: Request,
      tokenString: String,
      boundKeyThumbprint: String,
      clientId: ClientId,
      config: CoreConfig,
  ): ZIO[DpopService & OAuthConfigurationService, Throwable | UserInfoError, Unit] =
    for
      proofHeader <- singleDpopHeader(request)
      requireNonce <- ZIO.serviceWithZIO[OAuthConfigurationService](_.requireDpopNonce(clientId))
      // The key policy here is the deployment's, not the presenting client's: the token was
      // bound at `/token` against the client's registration as it stood then, and a key too
      // weak for it never received a `cnf.jkt`. Re-applying the current registration would
      // make narrowing it revoke tokens already bound under the wider one -- which edge, the
      // other endpoint presented with an existing binding, deliberately does not do either.
      keyPolicy <- ZIO.serviceWithZIO[OAuthConfigurationService](_.getDpopSigningAlgorithms)
        .map(Dpop.KeyPolicy(_, Dpop.KeyPolicy.MinRsaKeySize))
      proof <- ZIO.serviceWithZIO[DpopService](
        _.verify(
          token = proofHeader,
          method = request.method,
          uri = userInfoEndpointUri(config, request),
          requireNonce = requireNonce,
          keyPolicy = keyPolicy,
        ),
      ).mapError {
        case DpopService.Error.InvalidProof(reason) => UserInfoError.InvalidDpopProof(reason.toString)
        case DpopService.Error.Replayed => UserInfoError.InvalidDpopProof("proof has already been used")
        case DpopService.Error.NonceRequired(nonce) => UserInfoError.UseDpopNonce(nonce)
        case error: Throwable => error
      }
      // §7: the proof must name the token it accompanies, or a proof captured from one
      // request could be paired with any other token held by the same client.
      ath <- ZIO.fromOption(proof.ath).orElseFail(UserInfoError.InvalidDpopProof("proof is missing ath"))
      _ <- ZIO.fail(UserInfoError.InvalidDpopProof("ath does not match the presented access token"))
        .unless(MessageDigest.isEqual(
          ath.getBytes(StandardCharsets.UTF_8),
          Dpop.ath(tokenString).getBytes(StandardCharsets.UTF_8),
        ))
      // §6.1/§7.1: and it must be signed with the key the token was bound to at issuance.
      _ <- ZIO.fail(UserInfoError.InvalidDpopProof("proof key does not match the token's cnf.jkt"))
        .unless(MessageDigest.isEqual(
          proof.jkt.getBytes(StandardCharsets.UTF_8),
          boundKeyThumbprint.getBytes(StandardCharsets.UTF_8),
        ))
    yield ()

  /** RFC 8705 §3: a certificate-bound token belongs to whoever presents the certificate it was
    * bound to, so a token carrying `cnf.x5t#S256` is only honoured over that certificate. Left
    * unchecked, a stolen one would be accepted here under plain `Bearer` with no certificate
    * at all -- the same downgrade `checkDpop` refuses for RFC 9449.
    *
    * Which header to read comes from the tenant of the client the token was issued to, not from
    * the caller's credentials: `/userinfo` is reached with an access token. The token saying it
    * is bound is the whole reason to look, so the header is read for that client whatever its
    * registration says today -- see `ClientAuthentication.certificateForClient`.
    *
    * Every way this fails is `invalid_token`: RFC 8705 registers no `WWW-Authenticate` error
    * code of its own for a broken binding the way RFC 9449 §7.1 does for DPoP, and a token
    * that cannot be shown to be this caller's is not usable -- which is what `invalid_token`
    * says. A mangled header is included: something arrived for a token whose validity depends
    * on it, and reading that as "no certificate" would accept the very presentation the
    * binding exists to refuse.
    */
  private def checkCertificateBinding(
      request: Request,
      token: AccessTokenPayload,
  ): ZIO[ClientAuthentication, UserInfoError, Unit] =
    token.confirmation.flatMap(_.x5tS256) match
      case None =>
        ZIO.unit

      case Some(boundThumbprint) =>
        ZIO.serviceWithZIO[ClientAuthentication](_.certificateForClient(request, token.clientId))
          .orElseFail(UserInfoError.InvalidToken)
          .flatMap: certificate =>
            ZIO.fail(UserInfoError.InvalidToken).unless(
              certificate.exists(presented =>
                MessageDigest.isEqual(
                  presented.thumbprint.getBytes(StandardCharsets.UTF_8),
                  boundThumbprint.getBytes(StandardCharsets.UTF_8),
                ),
              ),
            ).unit

  private def singleDpopHeader(request: Request): IO[UserInfoError, String] =
    request.headers.toList.filter(_.headerName.equalsIgnoreCase(DpopHeader)).map(_.renderedValue) match
      case Nil => ZIO.fail(UserInfoError.InvalidDpopProof("missing DPoP header"))
      case proof :: Nil => ZIO.succeed(proof)
      case _ => ZIO.fail(UserInfoError.InvalidDpopProof("request must contain exactly one DPoP header"))
