package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, Prompt, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, AuthMethodRef, PassedAuthFactor, PassedFactorRecord, ScopeToken}
import versola.oauth.conversation.{ConversationRepository, ConversationRouter, EmailSubmission, PhoneSubmission}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.util.MAC
import versola.util.{AuthPropertyGenerator, Base64Url, CoreConfig, JWT, Secret, SecureRandom, SecurityService}
import zio.json.{JsonDecoder, ast}
import zio.json.ast.Json
import zio.{Chunk, Clock, Task, ZIO, ZLayer, durationInt}
import versola.user.model.UserId
import java.util.UUID
import zio.prelude.{NonEmptyList, NonEmptySet}

trait AuthorizeEndpointService:

  def authorize(request: AuthorizeRequest): Task[AuthorizeResponse]

object AuthorizeEndpointService:
  private case class HintClaims(sub: String, aud: Option[Json], iss: Option[String]) derives JsonDecoder

  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      configurationService: OAuthConfigurationService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      securityService: SecurityService,
      sessionRepository: SessionRepository,
      authPropertyGenerator: AuthPropertyGenerator,
      authorizationCodeRepository: AuthorizationCodeRepository,
      userRepository: UserRepository,
      userInfoService: UserInfoService,
      jwksService: JwksService,
      conversationRouter: ConversationRouter,
  ) extends AuthorizeEndpointService:

    override def authorize(
        request: AuthorizeRequest,
    ): Task[AuthorizeResponse] =
      for
        client <- configurationService.find(request.clientId)
        authFlow = client.flatMap(_.authFlow)
        flow <- ZIO
          .fromOption(authFlow)
          .orElseFail(Error.AuthFlowMissing(request.redirectUri, request.state))
        hintSub <- extractHintSub(request)
        uiLocales <- resolveUiLocales(request)
        response <- request.sessionId match
          case None if request.prompt.contains(Prompt.none) =>
            ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
          case None =>
            createConversation(request, flow, uiLocales, Map.empty, hintSub)
          case Some(rawId) =>
            for
              sessionMac <- securityService.mac(Secret(rawId), config.security.sessionsSecret)
              sessionOpt <- sessionRepository.find(sessionMac)
              result <- sessionOpt match
                case None if request.prompt.contains(Prompt.none) =>
                  ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                case None =>
                  createConversation(request, flow, uiLocales, Map.empty, hintSub)
                case Some(session) =>
                  for
                    now        <- Clock.instant
                    vocabulary <- configurationService.getAcrVocabulary(request.clientId)
                    forceReauth      = shouldForceReauth(request, session, now, hintSub)
                    acrSatisfied     = request.acrValues.forall { acrs =>
                                        acrs.headOption match
                                          case None      => true
                                          case Some(acr) => vocabulary.get(acr) match
                                            case None             => false
                                            case Some(reqFactors) => reqFactors.toList.forall(session.amr.contains)
                                      }
                    requiredFactors  = flow.primary.factors.filter(_.required).flatMap(f => PassedAuthFactor.fromFactorType(f.`type`)).toSet
                    factorsSatisfied = requiredFactors.forall(required =>
                      session.amr.keySet.exists(_.satisfies(required, flow.equivalents))
                    )
                    result <- if forceReauth then
                      if request.prompt.contains(Prompt.none) then
                        ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                      else
                        val expectedSub = if request.prompt.contains(Prompt.login) then hintSub
                                          else hintSub.orElse(Some(session.userId))
                        createConversation(request, flow, uiLocales, Map.empty, expectedSub)
                    else if !acrSatisfied then
                      if request.prompt.contains(Prompt.none) then
                        ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                      else
                        // ACR step-up: передаём уже пройденные факторы, login hint не применяем
                        createConversation(request, flow, uiLocales, session.amr, Some(session.userId), applyHint = false)
                    else if !factorsSatisfied then
                      if request.prompt.contains(Prompt.none) then
                        ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                      else
                        createConversation(request, flow, uiLocales, session.amr, Some(session.userId))
                    else
                      silentAuthorize(request, uiLocales, session, sessionMac)
                  yield result
            yield result
      yield response

    private def shouldForceReauth(
        request: AuthorizeRequest,
        session: SessionRecord,
        now: java.time.Instant,
        hintSub: Option[UserId],
    ): Boolean =
      val hintMismatch  = hintSub.exists(_ != session.userId)
      val sessionTooOld = request.maxAge.exists(maxAge =>
        maxAge >= 0 && session.createdAt.isBefore(now.minusSeconds(maxAge))
      )
      request.prompt.contains(Prompt.login) || sessionTooOld || hintMismatch

    private def applyLoginHint(request: AuthorizeRequest, uiLocales: Option[List[String]])(response: AuthorizeResponse): Task[AuthorizeResponse] =
      response match
        case AuthorizeResponse.Initialize(authId) =>
          request.loginHint match
            case Some(Left(email))  => conversationRouter.submit(authId, EmailSubmission(email), uiLocales.flatMap(_.headOption), None).as(response)
            case Some(Right(phone)) => conversationRouter.submit(authId, PhoneSubmission(phone), uiLocales.flatMap(_.headOption), None).as(response)
            case None               => ZIO.succeed(response)
        case _ => ZIO.succeed(response)

    private def createConversation(
        request: AuthorizeRequest,
        flow: AuthFlow,
        uiLocales: Option[List[String]],
        amr: Map[PassedAuthFactor, PassedFactorRecord],
        expectedSub: Option[UserId],
        applyHint: Boolean = true,
    ): Task[AuthorizeResponse] =
      AuthId.wrapAll(secureRandom.nextUUIDv7).flatMap: authId =>
        val conversation = ConversationRecord(
          clientId = request.clientId,
          redirectUri = request.redirectUri,
          scope = request.scope,
          codeChallenge = request.codeChallenge,
          codeChallengeMethod = request.codeChallengeMethod,
          state = request.state,
          userId = None,
          credential = None,
          step = ConversationStep.Credential(
            primaryCredentials = flow.primary.credentials,
            inlinePassword = flow.primary.inlinePassword,
            passkey = flow.passkey.isDefined,
          ),
          requestedClaims = request.requestedClaims,
          uiLocales = uiLocales,
          nonce = request.nonce,
          responseType = request.responseType,
          userEmail = None,
          userPhone = None,
          userLogin = None,
          userClaims = None,
          authFlow = flow,
          userAgent = request.userAgent.map(_.filter(c =>
            c >= ' ' && c <= '~',
          )), // Strip non-printable ASCII (0x20–0x7E); does not escape HTML — always escape at render time
          version = 0,
          amr = amr,
          needsPasswordChange = false,
          expectedUserId = expectedSub,
        )
        configurationService.getAuthConversationTtl(request.clientId).flatMap: authConversationTtl =>
          conversationRepository.create(authId, conversation, authConversationTtl)
            .as(AuthorizeResponse.Initialize(authId))
            .flatMap(r => if applyHint then applyLoginHint(request, uiLocales)(r) else ZIO.succeed(r))
            

    private def silentAuthorize(
        request: AuthorizeRequest,
        uiLocales: Option[List[String]],
        session: SessionRecord,
        sessionMac: MAC.Of[SessionId],
    ): Task[AuthorizeResponse] =
      val amr = AuthMethodRef.amrClaim(session.amr)
      val isHybrid =
        request.responseType.contains(ResponseTypeEntry.IdToken) &&
          request.scope.contains(ScopeToken.OpenId)
      for
        idleTtl <- ZIO.unless(request.scope.contains(ScopeToken.OfflineAccess))(
          configurationService.getSessionIdleTtl(request.clientId)
        )
        _ <- ZIO.foreachDiscard(idleTtl.flatten)(sessionRepository.prolongIdle(sessionMac, _))
        code <- authPropertyGenerator.nextAuthorizationCode
        accessToken <- authPropertyGenerator.nextAccessToken
        codeRecord = AuthorizationCodeRecord(
          sessionId = sessionMac,
          clientId = request.clientId,
          userId = session.userId,
          redirectUri = request.redirectUri,
          scope = request.scope,
          codeChallenge = request.codeChallenge,
          codeChallengeMethod = request.codeChallengeMethod,
          requestedClaims = request.requestedClaims,
          uiLocales = uiLocales,
          nonce = request.nonce,
          accessToken = accessToken,
          amr = amr,
          authTime = session.createdAt,
        )
        codeMac <- securityService.mac(Secret(code), config.security.authCodesSecret)
        _ <- authorizationCodeRepository.create(codeMac, codeRecord, zio.Duration.fromSeconds(60))
        idToken <- if isHybrid then silentIdToken(request, session, code, amr, uiLocales)
        else ZIO.none
      yield AuthorizeResponse.Authorized(code, idToken)

    private def silentIdToken(
        request: AuthorizeRequest,
        session: SessionRecord,
        code: AuthorizationCode,
        amr: Set[AuthMethodRef],
        uiLocales: Option[List[String]],
    ): Task[Option[String]] =
      for
        userOpt <- userRepository.find(session.userId)
        user <- ZIO
          .fromOption(userOpt)
          .orElseFail(Error.AccessDenied(request.redirectUri, request.state))
        userInfo <- userInfoService.getUserInfoForIdToken(
          user = user,
          scope = request.scope,
          requestedClaims = request.requestedClaims,
          uiLocales = uiLocales,
          nonce = request.nonce,
        )
        signingKey <- jwksService.getPublicKeys.map(_.active)
        cHash = JWT.leftHalfHash(Base64Url.encode(code), signingKey.algorithm)
        claims = userInfo.claims ++
          AuthMethodRef.idTokenClaims(amr, Some(session.createdAt), Acr.acrClaim(session.amr)) +
          ("c_hash" -> Json.Str(cHash))
        token <- JWT.serialize(
          typ = JWT.Type.JWT,
          claims = JWT.Claims(
            issuer = config.jwt.issuer,
            subject = session.userId.toString,
            audience = List(request.clientId),
            custom = Json.Obj(Chunk.fromIterable(claims)),
          ),
          ttl = 15.minutes,
          signature = JWT.Signature.Asymmetric(
            algorithm = signingKey.algorithm,
            keyId = signingKey.id,
            privateKey = config.jwt.privateKey,
          ),
        )
      yield Some(token)

    private def extractHintSub(request: AuthorizeRequest): Task[Option[UserId]] =
      request.idTokenHint match
        case None => ZIO.none
        case Some(token) =>
          jwksService.getPublicKeys.flatMap: keys =>
            JWT.deserialize[HintClaims](token, keys, JWT.Type.JWT, validateExpiry = false)
              .mapError(_ => Error.IdTokenHintInvalid(request.redirectUri, request.state))
              .flatMap: claims =>
                val audList = claims.aud match
                  case None               => List.empty
                  case Some(Json.Str(s))  => List(s)
                  case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s }.toList
                  case _                  => List.empty
                val audValid = audList.contains(request.clientId.toString)
                val issValid = claims.iss.contains(config.jwt.issuer)
                if audValid && issValid then
                  ZIO.attempt(UserId(UUID.fromString(claims.sub)))
                    .mapBoth(_ => Error.IdTokenHintInvalid(request.redirectUri, request.state), Some(_))
                else ZIO.fail(Error.IdTokenHintInvalid(request.redirectUri, request.state))

    /** Narrows the requested ui_locales to those configured in central, preserving the client's
     * preference order. Rejects the request when none of the requested locales are available.
     */
    private def resolveUiLocales(request: AuthorizeRequest): ZIO[Any, Error.UnsupportedUiLocales, Option[List[String]]] =
      request.uiLocales match
        case None => ZIO.none
        case Some(requested) =>
          configurationService.getLocales.flatMap: locales =>
            val available = locales.locales.map(_.code).toSet
            val intersection = requested.filter(available.contains)
            ZIO.cond(
              intersection.nonEmpty,
              Some(intersection),
              Error.UnsupportedUiLocales(request.redirectUri, request.state),
            )
