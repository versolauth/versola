package versola.edge.nativeapp

import versola.edge.dpop.{DpopPolicyService, DpopReplayGuard, DpopVerifier}
import versola.edge.model.{ClientCredential, ClientId}
import versola.edge.{EdgeConfig, OAuthClientService}
import versola.util.{Base64, Dpop, DpopNonce, PrivateClientCertificate, SecureRandom, SecurityService}
import zio.http.*
import zio.json.{DecoderOps, EncoderOps, JsonCodec, JsonDecoder, jsonField}
import zio.{Clock, Duration, IO, URLayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/** The four native endpoints of #420, fronting a mobile app under FAPI 2.0.
  *
  * The client role is split: edge authenticates as the client (`tls_client_auth` on auth's
  * mutual-TLS listener, see [[NativeAuthClient]]), the device sender-constrains the tokens with
  * a DPoP key that never leaves it. Edge holds no login record and no token of its own for
  * these clients: what `/native/start` would have stored travels as a sealed [[NativeBlob]],
  * and auth remains the only token store.
  *
  * Proofs a device addresses to auth (`/native/complete`, `/native/token`, `/native/revoke`) are
  * checked here for what edge can check on its own -- signature, `htm`, `htu`, `iat` and, for
  * `complete`, the key the flow started with -- and then forwarded byte for byte. Their `jti`
  * and `nonce` are auth's to judge: auth keeps the replay cache for those, and a
  * `use_dpop_nonce` answer travels back to the device untouched along with its `DPoP-Nonce`.
  *
  * `/native/start`'s proof is the exception, and the one thing edge does remember. Its `htu` is
  * edge's own URI, so the proof is never forwarded and auth is never in a position to judge it;
  * without a guard here an observed one is replayable for the whole `iat` window, on an
  * endpoint that carries no client credential at all -- each replay minting a further pushed
  * request at auth and a further blob bound to a key the replayer does not hold. RFC 9449
  * §4.3 step 12 / §11.1 is applied to it with the same [[DpopReplayGuard]] the proxied path uses.
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
  /** RFC 9449 §9: this edge requires a nonce and the proof carried none it issued, or one
    * that has expired. Carries a fresh one for the retry, the same challenge shape as the
    * proxied path's `Outcome.UseDpopNonce`. */
  case NonceRequired(nonce: String)

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

  /** A client of the four endpoints, resolved once per request: the `native` block this edge
    * was configured with, the certificate it authenticates as the client with, and the only
    * thing the flow reads off the registration itself. */
  private final case class NativeClient(
      id: String,
      native: EdgeConfig.Native,
      certificate: PrivateClientCertificate.Material,
      redirectUris: Set[String],
  )

  /** The authorization request edge builds on the app's behalf: the form pushed to `/par`, and
    * separately the values only edge knows, which the sealed blob carries to
    * `/native/complete`. */
  private final case class AuthorizationRequest(
      redirectUri: String,
      codeVerifier: String,
      state: String,
      jkt: String,
      pushed: Form,
  )

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
    EdgeConfig & OAuthClientService & NativeAuthClient & DpopPolicyService & DpopReplayGuard &
      SecureRandom & SecurityService,
    NativeService,
  ] = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _))

  class Impl(
      config: EdgeConfig,
      clientService: OAuthClientService,
      authClient: NativeAuthClient,
      dpopPolicy: DpopPolicyService,
      replayGuard: DpopReplayGuard,
      secureRandom: SecureRandom,
      securityService: SecurityService,
  ) extends NativeService:

    private val defaultIatLeeway = Duration.fromSeconds(60)

    override def start(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        client <- nativeClient(clientId)
        proof <- verifyProof(request, htu = ownUri(request.path), checkOwnNonce = true)
        // §4.3 step 12: a proof addressed to edge goes no further, so edge is the only party
        // that can refuse its second use. Before the form is read, so a replay costs nothing
        // past the verification it has already paid for.
        fresh <- replayGuard.recordIfAbsent(proof.jkt, proof.jti, proof.iat)
        _ <- ZIO.fail(NativeError.InvalidDpopProof("proof has already been used")).unless(fresh)
        form <- readForm(request)
        authorization <- authorizationRequest(form, client, proof)
        relayed <- authClient.par(clientId, client.certificate, authorization.pushed)
        response <-
          if relayed.status.isSuccess then startResponse(client, authorization, relayed)
          else ZIO.succeed(relayed.toResponse)
      yield response

    override def complete(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        client <- nativeClient(clientId)
        form <- readForm(request)
        code <- required(form, "code")
        blob <- openBlob(form, client)
        proof <- verifyProof(request, htu = authUri(client.native, "token"), checkOwnNonce = false)
        // RFC 9449 §10: auth refuses this too (the code is bound to `dpop_jkt`), but a proof by
        // another key is refused here without spending the call -- or the code.
        _ <- ZIO.fail(NativeError.InvalidGrant("DPoP proof is signed by a different key than the one the flow started with"))
          .unless(constantTimeEquals(proof.jkt, blob.jkt))
        relayed <- authClient.token(
          clientId,
          client.certificate,
          formOf(List(
            "grant_type" -> "authorization_code",
            "code" -> code,
            "redirect_uri" -> blob.redirectUri,
            "code_verifier" -> blob.codeVerifier,
          )),
          forwarded(request),
        )
      yield relayed.toResponse

    override def refresh(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        client <- nativeClient(clientId)
        form <- readForm(request)
        grantType <- optional(form, "grant_type")
        _ <- ZIO.fail(NativeError.UnsupportedGrantType).when(grantType.exists(_ != "refresh_token"))
        refreshToken <- required(form, "refresh_token")
        scope <- optional(form, "scope")
        resources <- values(form, "resource")
        _ <- verifyProof(request, htu = authUri(client.native, "token"), checkOwnNonce = false)
        relayed <- authClient.token(
          clientId,
          client.certificate,
          formOf(
            List("grant_type" -> "refresh_token", "refresh_token" -> refreshToken) ++
              scope.map("scope" -> _).toList ++ resources.map("resource" -> _),
          ),
          forwarded(request),
        )
      yield relayed.toResponse

    override def revoke(clientId: String, request: Request): IO[NativeError | Throwable, Response] =
      for
        client <- nativeClient(clientId)
        form <- readForm(request)
        token <- required(form, "token")
        hint <- optional(form, "token_type_hint")
        _ <- verifyProof(request, htu = authUri(client.native, "revoke"), checkOwnNonce = false)
        relayed <- authClient.revoke(
          clientId,
          client.certificate,
          formOf(List("token" -> token) ++ hint.map("token_type_hint" -> _).toList),
          forwarded(request),
        )
      yield relayed.toResponse

    /** The client the path names, as long as it is one this edge fronts natively: a `native`
      * client of a tenant assigned to this edge (central syncs no other tenant's clients here)
      * for which edge holds the certificate. Anything else is indistinguishable from nothing. */
    private def nativeClient(clientId: String): IO[NativeError, NativeClient] =
      for
        native <- ZIO.fromOption(config.native).orElseFail(NativeError.UnknownClient)
        client <- clientService.findClient(ClientId(clientId))
          .someOrFail(NativeError.UnknownClient)
          .filterOrFail(_.isEdgeFrontedNative)(NativeError.UnknownClient)
        certificate <- client.credential match
          case ClientCredential.MutualTls(material) => ZIO.succeed(material)
          case _ => ZIO.fail(NativeError.UnknownClient)
      yield NativeClient(clientId, native, certificate, client.redirectUris)

    /** What `/native/start` decides before auth is called. The three values only edge knows are
      * kept beside the form they were pushed in, because the blob has to carry them back to
      * `/native/complete` -- where they are all edge has to judge the code by. */
    private def authorizationRequest(
        form: Form,
        client: NativeClient,
        proof: Dpop.Proof,
    ): IO[NativeError, AuthorizationRequest] =
      for
        redirectUri <- chooseRedirectUri(form, client.redirectUris)
        passThrough <- ZIO.foreach(PassThroughParameters)(name => values(form, name).map(name -> _))
        codeVerifier <- secureRandom.nextBytes(32).map(Base64.urlEncode)
        state <- secureRandom.nextBytes(16).map(Base64.urlEncode)
      yield AuthorizationRequest(
        redirectUri = redirectUri,
        codeVerifier = codeVerifier,
        state = state,
        jkt = proof.jkt,
        pushed = formOf(
          List(
            "response_type" -> "code",
            "redirect_uri" -> redirectUri,
            "code_challenge" -> codeChallenge(codeVerifier),
            "code_challenge_method" -> "S256",
            "state" -> state,
            // RFC 9449 §10: the code auth issues is redeemable only with a proof by this key.
            Dpop.Jkt.Parameter -> proof.jkt,
          ) ++ passThrough.flatMap((name, supplied) => supplied.map(name -> _)),
        ),
      )

    /** Auth accepted the pushed request: the app is handed what opens the system browser, and
      * the sealed blob it must present at `/native/complete`. Both expire together, at
      * whichever comes first of auth's lifetime for the `request_uri` and this edge's
      * `blob-ttl` -- a blob outliving the request it names would be good for nothing. */
    private def startResponse(
        client: NativeClient,
        authorization: AuthorizationRequest,
        relayed: NativeAuthClient.Relayed,
    ): IO[NativeError | Throwable, Response] =
      for
        par <- ZIO.fromEither(relayed.bodyAsString.fromJson[PushedAuthorizationResponse])
          .mapError(reason => RuntimeException(s"auth's /par answered an unreadable body: $reason"))
        now <- Clock.instant
        lifetime = Math.min(par.expiresIn, client.native.blobTtl.toSeconds)
        blob <- NativeBlob.seal(
          NativeBlob(
            version = NativeBlob.CurrentVersion,
            clientId = client.id,
            codeVerifier = authorization.codeVerifier,
            state = authorization.state,
            jkt = authorization.jkt,
            redirectUri = authorization.redirectUri,
            expiresAt = now.getEpochSecond + lifetime,
          ),
          blobKey(client.native),
          securityService,
        )
      yield Response
        .json(
          StartResponse(
            clientId = client.id,
            requestUri = par.requestUri,
            expiresIn = lifetime,
            authorizationEndpoint = (config.versolaUrl / "authorize").encode,
            state = authorization.state,
            blob = blob,
            tokenEndpoint = authUri(client.native, "token"),
            revocationEndpoint = authUri(client.native, "revoke"),
          ).toJson,
        )
        .addHeader(Header.CacheControl.NoStore)

    /** The blob `/native/start` sealed, reopened and held to everything it named: this client,
      * its own lifetime, the `state` the app came back with, and the authorization server edge
      * actually pushed to. Everything edge knows about the request is in here -- it kept no
      * record of its own -- so a blob that fails any of these is a code worth nothing. */
    private def openBlob(form: Form, client: NativeClient): IO[NativeError, NativeBlob] =
      for
        state <- required(form, "state")
        iss <- required(form, "iss")
        sealedBlob <- required(form, "blob")
        blob <- NativeBlob.open(sealedBlob, blobKey(client.native), securityService)
          .orElseFail(NativeError.InvalidGrant("blob is not one this edge issued"))
        now <- Clock.instant
        _ <- ZIO.fail(NativeError.InvalidGrant("blob was issued for another client"))
          .unless(constantTimeEquals(blob.clientId, client.id))
        _ <- ZIO.fail(NativeError.InvalidGrant("blob has expired"))
          .when(now.getEpochSecond >= blob.expiresAt)
        _ <- ZIO.fail(NativeError.InvalidGrant("state does not match the authorization request"))
          .unless(constantTimeEquals(blob.state, state))
        // RFC 9207: the response names the issuer that produced it, which has to be the one
        // edge pushed to -- otherwise the code may be a mix-up from another authorization server.
        // Compared against auth's own `jwt.issuer` (native.issuer), not edge's `versolaUrl`: the
        // two are separate settings, and assuming them equal is exactly the RFC 9207 mix-up this
        // check exists to catch, not a shortcut past it.
        _ <- ZIO.fail(NativeError.InvalidGrant("iss is not the authorization server the request was pushed to"))
          .unless(iss.stripSuffix("/") == client.native.issuer(config.versolaUrl).encode.stripSuffix("/"))
      yield blob

    /** @param checkOwnNonce whether this edge's own nonce policy applies to the proof. Only
      * `start`'s proof is addressed to edge (`htu` is edge's own URI): edge is the only party
      * that will ever see it, so it is the only party that can demand a nonce of it. A proof
      * addressed to auth (`complete`, `refresh`, `revoke`) is forwarded byte for byte, and its
      * `nonce` is auth's alone to judge -- one auth issued in an earlier `use_dpop_nonce` round
      * trip would never satisfy edge's own, differently-salted nonce store, so applying this
      * edge's policy to a forwarded proof would refuse every one of them with no way for the
      * device to ever produce a proof both stores would accept.
      */
    private def verifyProof(request: Request, htu: String, checkOwnNonce: Boolean): IO[NativeError, Dpop.Proof] =
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
        _ <- checkNonce(proof, now).when(checkOwnNonce)
      yield proof

    /** RFC 9449 §9 / §4.3 step 10, applied here the way [[DpopVerifier]] applies it to the
      * proxied path: where central has this edge requiring a nonce, every proof -- a native
      * one included -- must carry one this edge issued, or a caller already given a challenge
      * walks straight past it on a nonce-less retry (§11.3's downgrade).
      */
    private def checkNonce(proof: Dpop.Proof, now: Instant): IO[NativeError, Unit] =
      dpopPolicy.requireNonce.flatMap:
        case false => ZIO.unit
        case true =>
          val dpop = dpopSettings
          val issued = proof.nonce.exists(DpopNonce.verify(dpop.nonceSalt, _, now, dpop.nonceTtl).isRight)
          ZIO.fail(NativeError.NonceRequired(DpopNonce.issue(dpop.nonceSalt, now))).unless(issued).unit

    /** `native` cannot be configured without `dpop` (`EdgeConfig.validated` refuses it), so
      * this is total for any request that reached an endpoint at all. */
    private def dpopSettings: EdgeConfig.Dpop =
      config.dpop.getOrElse(
        throw IllegalStateException("native configured without dpop -- EdgeConfig.validated should have refused this"),
      )

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

    /** A form edge states itself, so every value is already a simple field -- unlike the
      * inbound one, which is read through [[values]]. */
    private def formOf(fields: List[(String, String)]): Form =
      Form(fields.map(FormField.simpleField(_, _))*)

    /** RFC 7636 §4.2, the `S256` transformation -- the only one FAPI 2.0 §5.3.2.2 admits. */
    private def codeChallenge(codeVerifier: String): String =
      Base64.urlEncode(
        MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)),
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
