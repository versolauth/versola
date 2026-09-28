package versola.edge.nativeapp

import versola.edge.dpop.{DpopPolicyService, DpopVerifier}
import versola.edge.model.{ClientCredential, ClientId}
import versola.edge.{EdgeConfig, OAuthClientService}
import versola.util.{Base64, Dpop, PrivateClientCertificate, SecureRandom, SecurityService}
import zio.http.*
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder, jsonField}
import zio.{Clock, Duration, IO, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

/** The four native endpoints of #420, fronting a mobile app under FAPI 2.0.
  *
  * The client role is split: edge authenticates as the client (`tls_client_auth` on auth's
  * mutual-TLS listener, see [[NativeAuthClient]]), the device sender-constrains the tokens with
  * a DPoP key that never leaves it. Edge holds no state for these clients -- no login record,
  * no replay cache, no refresh token: what `/native/start` would have stored travels as a sealed
  * [[NativeBlob]], and auth remains the only token store.
  *
  * Proofs a device addresses to auth (`/native/complete`, `/native/token`, `/native/revoke`) are
  * checked here for what edge can check on its own -- signature, `htm`, `htu`, `iat` and, for
  * `complete`, the key the flow started with -- and then forwarded byte for byte. Their `jti`
  * and `nonce` are auth's to judge: auth keeps the replay cache, and a `use_dpop_nonce` answer
  * travels back to the device untouched along with its `DPoP-Nonce`.
  */
trait NativeService:
  def start(clientId: String, request: Request): IO[NativeError | Throwable, Response]
  def complete(clientId: String, request: Request): IO[NativeError | Throwable, Response]
  def refresh(clientId: String, request: Request): IO[NativeError | Throwable, Response]
  def revoke(clientId: String, request: Request): IO[NativeError | Throwable, Response]

/** What edge refuses on its own, before or instead of calling auth. Rendered as RFC 6749 §5.2
  * error objects -- the same shape auth's own refusals come back in, so the SDK reads one
  * format whoever answered. */
enum NativeError:
  /** The path names no native client of a tenant served by this edge, or this edge is not
    * configured for native clients at all. */
  case UnknownClient
  case InvalidRequest(description: String)
  case UnsupportedGrantType
  case InvalidDpopProof(description: String)
  case InvalidGrant(description: String)

object NativeService:

  /** `/native/start`'s answer. `authorization_endpoint` plus `client_id` and `request_uri` is
    * the whole of what the system browser is opened on; `token_endpoint` and
    * `revocation_endpoint` are the `htu` values the device must sign its later proofs for --
    * auth's mutual-TLS aliases, since that is where edge forwards them. */
  final case class StartResponse(
      @jsonField("client_id") clientId: String,
      @jsonField("request_uri") requestUri: String,
      @jsonField("expires_in") expiresIn: Long,
      @jsonField("authorization_endpoint") authorizationEndpoint: String,
      state: String,
      blob: String,
      @jsonField("token_endpoint") tokenEndpoint: String,
      @jsonField("revocation_endpoint") revocationEndpoint: String,
  ) derives JsonCodec

  private final case class PushedAuthorizationResponse(
      @jsonField("request_uri") requestUri: String,
      @jsonField("expires_in") expiresIn: Long,
  ) derives JsonDecoder

  /** Authorization request parameters the app may set on `/native/start`, passed to `/par`
    * as given. Everything that decides the security of the flow -- `response_type`, PKCE,
    * `state`, `dpop_jkt`, `client_id` -- is edge's and cannot be supplied. */
  val PassThroughParameters: List[String] = List(
    "scope", "nonce", "acr_values", "prompt", "login_hint", "ui_locales", "max_age",
    "claims", "resource", "authorization_details",
  )

  /** Parameters that may legitimately repeat (RFC 8707 §2). */
  private val Repeatable: Set[String] = Set("resource")

  val live: URLayer[
    EdgeConfig & OAuthClientService & NativeAuthClient & DpopPolicyService & SecureRandom & SecurityService,
    NativeService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _))

  class Impl(
      config: EdgeConfig,
      clientService: OAuthClientService,
      authClient: NativeAuthClient,
      dpopPolicy: DpopPolicyService,
      secureRandom: SecureRandom,
      securityService: SecurityService,
  ) extends NativeService:

    private val defaultIatLeeway = Duration.fromSeconds(60)

    override def start(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        (native, certificate, client) <- nativeClient(clientId)
        proof <- verifyProof(request, htu = ownUri(request.path))
        form <- readForm(request)
        redirectUri <- chooseRedirectUri(form, client.redirectUris)
        passThrough <- ZIO.foreach(PassThroughParameters)(name => values(form, name).map(name -> _))

        codeVerifier <- secureRandom.nextBytes(32).map(Base64.urlEncode)
        codeChallenge = Base64.urlEncode(
          MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)),
        )
        state <- secureRandom.nextBytes(16).map(Base64.urlEncode)

        pushed = Form(
          (List(
            "response_type" -> "code",
            "redirect_uri" -> redirectUri,
            "code_challenge" -> codeChallenge,
            "code_challenge_method" -> "S256",
            "state" -> state,
            // RFC 9449 §10: the code auth issues is redeemable only with a proof by this key.
            Dpop.Jkt.Parameter -> proof.jkt,
          ) ++ passThrough.flatMap((name, vs) => vs.map(name -> _)))
            .map(FormField.simpleField(_, _))*,
        )
        relayed <- authClient.par(clientId, certificate, pushed)
        response <-
          if !relayed.status.isSuccess then ZIO.succeed(relayed.toResponse)
          else
            for
              par <- ZIO.fromEither(relayed.bodyAsString.fromJson[PushedAuthorizationResponse])
                .mapError(reason => RuntimeException(s"auth's /par answered an unreadable body: $reason"))
              now <- Clock.instant
              lifetime = Math.min(par.expiresIn, native.blobTtl.toSeconds)
              blob <- NativeBlob.seal(
                NativeBlob(
                  version = NativeBlob.CurrentVersion,
                  clientId = clientId,
                  codeVerifier = codeVerifier,
                  state = state,
                  jkt = proof.jkt,
                  redirectUri = redirectUri,
                  expiresAt = now.getEpochSecond + lifetime,
                ),
                blobKey(native),
                securityService,
              )
            yield Response
              .json(
                StartResponse(
                  clientId = clientId,
                  requestUri = par.requestUri,
                  expiresIn = lifetime,
                  authorizationEndpoint = (config.versolaUrl / "authorize").encode,
                  state = state,
                  blob = blob,
                  tokenEndpoint = authUri(native, "token"),
                  revocationEndpoint = authUri(native, "revoke"),
                ).toJson,
              )
              .addHeader(Header.CacheControl.NoStore)
      yield response

    override def complete(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        (native, certificate, _) <- nativeClient(clientId)
        form <- readForm(request)
        code <- required(form, "code")
        state <- required(form, "state")
        iss <- required(form, "iss")
        sealedBlob <- required(form, "blob")

        blob <- NativeBlob.open(sealedBlob, blobKey(native), securityService)
          .orElseFail(NativeError.InvalidGrant("blob is not one this edge issued"))
        now <- Clock.instant
        _ <- ZIO.fail(NativeError.InvalidGrant("blob was issued for another client"))
          .unless(constantTimeEquals(blob.clientId, clientId))
        _ <- ZIO.fail(NativeError.InvalidGrant("blob has expired"))
          .when(now.getEpochSecond >= blob.expiresAt)
        _ <- ZIO.fail(NativeError.InvalidGrant("state does not match the authorization request"))
          .unless(constantTimeEquals(blob.state, state))
        // RFC 9207: the response names the issuer that produced it, which has to be the one
        // edge pushed to -- otherwise the code may be a mix-up from another authorization server.
        _ <- ZIO.fail(NativeError.InvalidGrant("iss is not the authorization server the request was pushed to"))
          .unless(iss.stripSuffix("/") == config.versolaUrl.encode.stripSuffix("/"))

        proof <- verifyProof(request, htu = authUri(native, "token"))
        // RFC 9449 §10: auth refuses this too (the code is bound to `dpop_jkt`), but a proof by
        // another key is refused here without spending the call -- or the code.
        _ <- ZIO.fail(NativeError.InvalidGrant("DPoP proof is signed by a different key than the one the flow started with"))
          .unless(constantTimeEquals(proof.jkt, blob.jkt))

        relayed <- authClient.token(
          clientId,
          certificate,
          Form(
            FormField.simpleField("grant_type", "authorization_code"),
            FormField.simpleField("code", code),
            FormField.simpleField("redirect_uri", blob.redirectUri),
            FormField.simpleField("code_verifier", blob.codeVerifier),
          ),
          forwarded(request),
        )
      yield relayed.toResponse

    override def refresh(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        (native, certificate, _) <- nativeClient(clientId)
        form <- readForm(request)
        grantType <- optional(form, "grant_type")
        _ <- ZIO.fail(NativeError.UnsupportedGrantType).when(grantType.exists(_ != "refresh_token"))
        refreshToken <- required(form, "refresh_token")
        scope <- optional(form, "scope")
        resources <- values(form, "resource")
        _ <- verifyProof(request, htu = authUri(native, "token"))
        relayed <- authClient.token(
          clientId,
          certificate,
          Form(
            (List("grant_type" -> "refresh_token", "refresh_token" -> refreshToken) ++
              scope.map("scope" -> _).toList ++ resources.map("resource" -> _))
              .map(FormField.simpleField(_, _))*,
          ),
          forwarded(request),
        )
      yield relayed.toResponse

    override def revoke(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        (native, certificate, _) <- nativeClient(clientId)
        form <- readForm(request)
        token <- required(form, "token")
        hint <- optional(form, "token_type_hint")
        _ <- verifyProof(request, htu = authUri(native, "revoke"))
        relayed <- authClient.revoke(
          clientId,
          certificate,
          Form(
            (List("token" -> token) ++ hint.map("token_type_hint" -> _).toList)
              .map(FormField.simpleField(_, _))*,
          ),
          forwarded(request),
        )
      yield relayed.toResponse

    /** The client the path names, as long as it is one this edge fronts natively: a `native`
      * client of a tenant assigned to this edge (central syncs no other tenant's clients here)
      * for which edge holds the certificate. Anything else is indistinguishable from nothing. */
    private def nativeClient(clientId: String) =
      for
        native <- ZIO.fromOption(config.native).orElseFail(NativeError.UnknownClient)
        client <- clientService.findClient(ClientId(clientId))
          .someOrFail(NativeError.UnknownClient)
          .filterOrFail(_.isEdgeFrontedNative)(NativeError.UnknownClient)
        certificate <- client.credential match
          case ClientCredential.MutualTls(material) => ZIO.succeed(material)
          case _ => ZIO.fail(NativeError.UnknownClient)
      yield (native, certificate, client)

    private def verifyProof(request: Request, htu: String): IO[NativeError, Dpop.Proof] =
      for
        header <- DpopVerifier.proofHeader(request).mapError {
          case DpopVerifier.Error.MultipleProofs => NativeError.InvalidDpopProof("request must contain exactly one DPoP header")
          case _ => NativeError.InvalidDpopProof("request must carry a DPoP proof from the device key")
        }
        algorithms <- dpopPolicy.allowedAlgorithms
        now <- Clock.instant
        proof <- Dpop.verify(
          token = header,
          keyPolicy = Dpop.KeyPolicy(algorithms, Dpop.KeyPolicy.MinRsaKeySize),
          expectedMethod = Method.POST,
          expectedUri = htu,
          now = now,
          iatLeeway = config.dpop.fold(defaultIatLeeway)(_.iatLeeway),
        ).mapError(reason => NativeError.InvalidDpopProof(reason.toString))
      yield proof

    /** The redirect URI to push: the one the app named, which must be registered, or the
      * client's only one. Auth validates it again at `/par`; this only spares the call. */
    private def chooseRedirectUri(form: Form, registered: Set[String]): IO[NativeError, String] =
      optional(form, "redirect_uri").flatMap:
        case Some(uri) if registered.contains(uri) => ZIO.succeed(uri)
        case Some(_) => ZIO.fail(NativeError.InvalidRequest("redirect_uri is not registered for this client"))
        case None if registered.sizeIs == 1 => ZIO.succeed(registered.head)
        case None => ZIO.fail(NativeError.InvalidRequest("redirect_uri is required - the client registered several"))

    private def readForm(request: Request): IO[NativeError, Form] =
      request.body.asURLEncodedForm.orElseFail(
        NativeError.InvalidRequest("body must be application/x-www-form-urlencoded"),
      )

    private def values(form: Form, name: String): IO[NativeError, List[String]] =
      val all = form.formData.filter(_.name == name).toList.flatMap(_.stringValue)
      if all.sizeIs > 1 && !Repeatable.contains(name) then
        ZIO.fail(NativeError.InvalidRequest(s"$name must not be repeated"))
      else ZIO.succeed(all)

    private def optional(form: Form, name: String): IO[NativeError, Option[String]] =
      values(form, name).map(_.headOption.filter(_.nonEmpty))

    private def required(form: Form, name: String): IO[NativeError, String] =
      optional(form, name).someOrFail(NativeError.InvalidRequest(s"$name is required"))

    private def forwarded(request: Request): Headers =
      Headers(request.headers.toList.filter(h => NativeAuthClient.ForwardedHeaders.contains(h.headerName.toLowerCase)))

    private def blobKey(native: EdgeConfig.Native) = SecretKeySpec(native.blobKey, "AES")

    /** Auth's endpoint on its mutual-TLS listener, as auth names it -- the `htu` of a proof it
      * will verify there (`CoreConfig.endpointUri`). */
    private def authUri(native: EdgeConfig.Native, path: String): String =
      s"${native.externalUrl.encode.stripSuffix("/")}/$path"

    /** This edge's own URI for `path`, built as [[DpopVerifier]] builds it: the configured
      * public origin, never the inbound `Host`. */
    private def ownUri(path: Path): String =
      config.edgeUrl.copy(path = Path.empty, queryParams = QueryParams.empty, fragment = None).encode + path.encode

    private def constantTimeEquals(a: String, b: String): Boolean =
      MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))
