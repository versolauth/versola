package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, Prompt, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFlow, AuthMethodRef, PassedAuthFactor, PassedFactorRecord, ScopeToken}
import versola.oauth.conversation.{ConversationRepository, ConversationRouter, EmailSubmission, PhoneSubmission}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.{SessionId, SessionRecord}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.util.MAC
import versola.util.{AuthPropertyGenerator, Base64Url, CoreConfig, JWT, Secret, SecureRandom, SecurityService}
import zio.json.ast.Json
import zio.{Chunk, Task, ZIO, ZLayer, durationInt}

trait AuthorizeEndpointService:

  def authorize(request: AuthorizeRequest): Task[AuthorizeResponse]

object AuthorizeEndpointService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _))

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
        uiLocales <- resolveUiLocales(request)
        response <- request.sessionId match
          case None if request.prompt.contains(Prompt.none) =>
            ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
          case None =>
            createConversation(request, flow, uiLocales, Map.empty)
          case Some(rawId) =>
            for
              sessionMac <- securityService.mac(Secret(rawId), config.security.sessions.pepper)
              sessionOpt <- sessionRepository.find(sessionMac)
              result <- sessionOpt match
                case None if request.prompt.contains(Prompt.none) =>
                  ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                case None =>
                  createConversation(request, flow, uiLocales, Map.empty)
                case Some(session) if request.prompt.contains(Prompt.login) =>
                  createConversation(request, flow, uiLocales, Map.empty)
                case Some(session) =>
                  val requiredFactors = flow.primary.factors.flatMap(f => PassedAuthFactor.fromFactorType(f.`type`)).toSet
                  if requiredFactors.forall(required => session.amr.keySet.exists(_.satisfies(required, flow.equivalents))) then
                    silentAuthorize(request, uiLocales, session, sessionMac)
                  else if request.prompt.contains(Prompt.none) then
                    ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                  else
                    createConversation(request, flow, uiLocales, session.amr)
            yield result
      yield response

    private def createConversation(
        request: AuthorizeRequest,
        flow: AuthFlow,
        uiLocales: Option[List[String]],
        amr: Map[PassedAuthFactor, PassedFactorRecord],
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
        )
        for
          _ <- conversationRepository.create(authId, conversation, config.security.authConversation.ttl)
          response <- request.loginHint match
            case None => ZIO.succeed(AuthorizeResponse.Initialize(authId))
            case Some(hint) =>
              val submission = hint match
                case Left(email) => EmailSubmission(email)
                case Right(phone) => PhoneSubmission(phone)
              conversationRouter.submit(authId, submission, uiLocale = None)
                .map(render => AuthorizeResponse.InitializeWithHint(authId, render, conversation))
        yield response

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
        codeMac <- securityService.mac(Secret(code), config.security.authCodes.pepper)
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
        cHash = JWT.leftHalfHash(Base64Url.encode(code), config.jwt.publicKeys.active.algorithm)
        claims = userInfo.claims ++
          AuthMethodRef.idTokenClaims(amr, Some(session.createdAt)) +
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
            algorithm = config.jwt.publicKeys.active.algorithm,
            keyId = config.jwt.publicKeys.active.id,
            privateKey = config.jwt.privateKey,
          ),
        )
      yield Some(token)

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