package versola.oauth.userinfo

import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import versola.oauth.client.model.ScopeToken
import versola.oauth.dpop.{DpopService, EdgeAssertionService}
import versola.oauth.jwks.JwksService
import versola.oauth.model.AccessTokenPayload
import versola.oauth.userinfo.model.{UserInfoError, UserInfoResponse}
import versola.util.http.{Controller, Observability}
import versola.util.{CoreConfig, Dpop, EdgeAssertion, JWT}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.telemetry.opentelemetry.tracing.Tracing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
  type Env = Tracing & UserInfoService & JwksService & CoreConfig & DpopService & EdgeAssertionService

  private val DpopHeader = "DPoP"

  /** RFC 9449 §4.3 compares a proof's `htu` against the endpoint's own URI. Derived from the
    * configured issuer rather than the inbound request, matching `TokenEndpointController`'s
    * `tokenEndpointUri`. */
  private def userInfoEndpointUri(config: CoreConfig): String =
    s"${config.jwt.issuer.stripSuffix("/")}/userinfo"

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

        _ <- checkDpop(request, tokenString, token, scheme, config)

        _ <- ZIO.fail(UserInfoError.InsufficientScope)
          .unless(token.scope.contains(ScopeToken.OpenId))

        userInfo <- userInfoService.getUserInfo(
          userId = userId,
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
              signingKey <- ZIO.serviceWithZIO[JwksService](_.signingKey)
              signedJwt <- JWT.serialize(
                claims = JWT.Claims(
                  issuer = config.jwt.issuer,
                  subject = userId.toString,
                  audience = List(token.clientId),
                  custom = userInfo.toJsonAST,
                ),
                ttl = 5.minutes,
                signature = JWT.Signature.Asymmetric(
                  algorithm = signingKey.algorithm,
                  keyId = signingKey.id,
                  privateKey = config.jwt.privateKey,
                ),
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

  /** RFC 9449 §7.1: decides what the presented token and scheme oblige the caller to prove.
    *
    * The `cnf.jkt` claim, not the scheme, is what makes a proof mandatory. A caller choosing
    * `Bearer` for a key-bound token is exactly the downgrade §7.2 exists to refuse, so that
    * refusal stands unless an edge signs for it. Mirrors `EdgeService.checkDpop`.
    */
  private def checkDpop(
      request: Request,
      tokenString: String,
      token: AccessTokenPayload,
      scheme: AuthScheme,
      config: CoreConfig,
  ): ZIO[DpopService & EdgeAssertionService, Throwable | UserInfoError, Unit] =
    (token.confirmation.map(_.jkt), scheme) match
      case (Some(jkt), AuthScheme.Dpop) =>
        verifyDpopProof(request, tokenString, jkt, config)

      // The one refusal an edge can answer: it has already run these same checks at its
      // own boundary, and can neither forward nor re-mint the proof that satisfied them
      // (see [[EdgeAssertion]]). Nothing else about the request is treated differently --
      // an assertion that fails to verify leaves this refusal exactly as it was.
      case (Some(_), AuthScheme.Bearer) =>
        edgeAssertion(request) match
          case None => ZIO.fail(downgradeRefused)
          case Some(assertion) =>
            ZIO.serviceWithZIO[EdgeAssertionService](_.verify(assertion, tokenString)).flatMap:
              case Some(edgeId) => Observability.setRouteLabel("dpop_edge_assertion", edgeId)
              case None => ZIO.fail(downgradeRefused)

      // A proof signed with some key says nothing about a token that was never bound to one:
      // anyone holding the token could have produced it. Treated as invalid rather than
      // falling back to bearer semantics.
      case (None, AuthScheme.Dpop) =>
        ZIO.fail(UserInfoError.InvalidDpopProof("token is not DPoP-bound"))

      case (None, AuthScheme.Bearer) =>
        ZIO.unit

  /** The refusal RFC 9449 §7.2 requires of a bound token presented without a proof. Held in
    * one place because it is now reached from two: no assertion at all, and one that did not
    * verify -- which must be indistinguishable from the outside. */
  private val downgradeRefused = UserInfoError.InvalidDpopProof("bound token presented with the Bearer scheme")

  /** RFC 9449 §4.3(1) refuses a duplicated `DPoP` header, and the same reasoning holds here:
    * a second assertion is a second identity claim, and picking either one silently is how
    * the one that was not checked gets in. */
  private def edgeAssertion(request: Request): Option[String] =
    request.headers.toList
      .filter(_.headerName.equalsIgnoreCase(EdgeAssertion.HeaderName))
      .map(_.renderedValue) match
      case assertion :: Nil => Some(assertion)
      case _ => None

  private def verifyDpopProof(
      request: Request,
      tokenString: String,
      boundKeyThumbprint: String,
      config: CoreConfig,
  ): ZIO[DpopService, Throwable | UserInfoError, Unit] =
    for
      proofHeader <- singleDpopHeader(request)
      proof <- ZIO.serviceWithZIO[DpopService](
        _.verify(
          token = proofHeader,
          method = request.method,
          uri = userInfoEndpointUri(config),
          requireNonce = false,
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

  private def singleDpopHeader(request: Request): IO[UserInfoError, String] =
    request.headers.toList.filter(_.headerName.equalsIgnoreCase(DpopHeader)).map(_.renderedValue) match
      case Nil => ZIO.fail(UserInfoError.InvalidDpopProof("missing DPoP header"))
      case proof :: Nil => ZIO.succeed(proof)
      case _ => ZIO.fail(UserInfoError.InvalidDpopProof("request must contain exactly one DPoP header"))
