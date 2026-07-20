package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.*
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.oauth.conversation.{ConversationRepository, ConversationRouter, EmailSubmission, PhoneSubmission}
import versola.oauth.jwks.JwksService
import versola.oauth.model.{AuthorizationCode, AuthorizationCodeRecord}
import versola.oauth.session.SessionService
import versola.oauth.session.model.{SessionId, SessionInfo, SessionRecord}
import versola.oauth.token.AuthorizationCodeRepository
import versola.oauth.userinfo.UserInfoService
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.MAC
import versola.util.{AuthPropertyGenerator, Base64Url, CoreConfig, JWT, Secret, SecureRandom, SecurityService}
import zio.json.ast.Json
import zio.json.{JsonDecoder, ast}
import zio.prelude.{NonEmptyList, NonEmptySet}
import zio.{Chunk, Clock, Task, ZIO, ZLayer, durationInt}

import java.util.UUID

trait AuthorizeEndpointService:

  def authorize(request: AuthorizeRequest): Task[AuthorizeResponse]

object AuthorizeEndpointService:
  private case class HintClaims(sub: UserId, aud: Option[Json], iss: Option[String]) derives JsonDecoder

  def live =
    ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      configurationService: OAuthConfigurationService,
      secureRandom: SecureRandom,
      config: CoreConfig,
      securityService: SecurityService,
      sessionService: SessionService,
      authPropertyGenerator: AuthPropertyGenerator,
      authorizationCodeRepository: AuthorizationCodeRepository,
      userRepository: UserRepository,
      userInfoService: UserInfoService,
      jwksService: JwksService,
      conversationRouter: ConversationRouter,
      acrResolutionService: AcrResolutionService,
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

        _ <- ZIO.fail(Error.ConflictingHints(request.redirectUri, request.state))
          .when(request.loginHint.isDefined && request.idTokenHint.isDefined)

        idTokenUserId <- extractHintSub(request)
        uiLocales <- resolveUiLocales(request)

        sessionInfo <- request.sessionId match
          case None => ZIO.none
          case Some(rawId) => sessionService.find(rawId)

        now <- Clock.instant

        response <- (sessionInfo, idTokenUserId) match
          case (None, _) if request.promptNone =>
            ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))

          case (None, Some(userId)) =>
            request.acrValues match
              case Some(values) =>
                acrResolutionService.resolveAchievableAcr(userId, values, request.clientId, flow, Set.empty).flatMap:
                  case None =>
                    ZIO.fail(Error.UnmetAuthenticationRequirements(request.redirectUri, request.state))
                  case Some(targetAcr) =>
                    createConversation(
                      request,
                      flow,
                      uiLocales,
                      Map.empty,
                      applyHint = false,
                      knownUserId = Some(userId),
                      targetAcr = Some(targetAcr),
                    )
              case None =>
                createConversation(request, flow, uiLocales, Map.empty, knownUserId = Some(userId))

          case (None, None) =>
            // ACR is voluntary (OIDC Core §3.1.2.1): without a known user we cannot verify
            // achievability, so we proceed with normal auth and omit the acr claim.
            createConversation(request, flow, uiLocales, Map.empty)

          case (Some(SessionInfo(id, session)), _) =>
            for
              hintMismatch = idTokenUserId.exists(_ != session.userId)
              sessionTooOld = request.maxAge
                .exists(maxAge => maxAge >= 0 && session.createdAt.isBefore(now.minusSeconds(maxAge)))

              forceReauth = request.promptLogin || sessionTooOld || hintMismatch

              satisfiedAcr <- request.acrValues match
                case None => ZIO.none
                case Some(values) =>
                  acrResolutionService.checkAcrSatisfaction(request.clientId, values, session.amr.keySet, flow.equivalents)

              acrSatisfied = request.acrValues.isEmpty || satisfiedAcr.isDefined

              factorsSatisfied = request.acrValues match
                case Some(_) => true
                case None =>
                  flow.primary.factors.filter(_.required)
                    .flatMap(f => PassedAuthFactor.fromFactorType(f.`type`))
                    .forall(required => session.amr.keySet.exists(_.satisfies(required, flow.equivalents)))

              result <-
                if (forceReauth || !acrSatisfied || !factorsSatisfied) && request.promptNone then
                  ZIO.fail(Error.LoginRequired(request.redirectUri, request.state))
                else if forceReauth then
                  val targetUserId = if request.promptLogin then idTokenUserId else idTokenUserId.orElse(Some(session.userId))
                  request.acrValues match
                    case Some(values) if targetUserId.isDefined =>
                      acrResolutionService.resolveAchievableAcr(targetUserId.get, values, request.clientId, flow, Set.empty).flatMap:
                        case None =>
                          ZIO.fail(Error.UnmetAuthenticationRequirements(request.redirectUri, request.state))
                        case Some(targetAcr) =>
                          createConversation(
                            request,
                            flow,
                            uiLocales,
                            Map.empty,
                            applyHint = false,
                            knownUserId = targetUserId,
                            targetAcr = Some(targetAcr),
                          )
                    case _ =>
                      createConversation(request, flow, uiLocales, Map.empty, knownUserId = targetUserId)
                else if !acrSatisfied then
                  acrResolutionService.resolveAchievableAcr(
                    session.userId,
                    request.acrValues.get,
                    request.clientId,
                    flow,
                    session.amr.keySet,
                  ).flatMap:
                    case None =>
                      ZIO.fail(Error.UnmetAuthenticationRequirements(request.redirectUri, request.state))
                    case Some(targetAcr) =>
                      createConversation(
                        request,
                        flow,
                        uiLocales,
                        session.amr,
                        applyHint = false,
                        knownUserId = Some(session.userId),
                        targetAcr = Some(targetAcr),
                      )
                else if !factorsSatisfied then
                  createConversation(request, flow, uiLocales, session.amr, applyHint = false, knownUserId = Some(session.userId))
                else
                  silentAuthorize(request, uiLocales, SessionInfo(id, session), satisfiedAcr)
            yield result
      yield response

    private def applyLoginHint(request: AuthorizeRequest, uiLocales: Option[List[String]])(response: AuthorizeResponse): Task[AuthorizeResponse] =
      response match
        case AuthorizeResponse.Initialize(authId) =>
          request.loginHint match
            case Some(Left(email)) => conversationRouter.submit(authId, EmailSubmission(email), uiLocales.flatMap(_.headOption), None).as(response)
            case Some(Right(phone)) => conversationRouter.submit(authId, PhoneSubmission(phone), uiLocales.flatMap(_.headOption), None).as(response)
            case None => ZIO.succeed(response)
        case _ => ZIO.succeed(response)

    private def createConversation(
        request: AuthorizeRequest,
        flow: AuthFlow,
        uiLocales: Option[List[String]],
        amr: Map[PassedAuthFactor, PassedFactorRecord],
        applyHint: Boolean = true,
        knownUserId: Option[UserId] = None,
        targetAcr: Option[Acr] = None,
    ): Task[AuthorizeResponse] =
      for
        authId <- AuthId.wrapAll(secureRandom.nextUUIDv7)
        // When both userId and targetAcr are known (step-up flow), fetch user data up front so
        // we can populate credential and user fields without a separate router step.
        userOpt <- (knownUserId, targetAcr) match
          case (Some(uid), Some(_)) => userRepository.find(uid)
          case _ => ZIO.none
        credential = userOpt.flatMap(u => u.email.map(Left(_)).orElse(u.phone.map(Right(_))))
        conversation = ConversationRecord(
          clientId = request.clientId,
          redirectUri = request.redirectUri,
          scope = request.scope,
          codeChallenge = request.codeChallenge,
          codeChallengeMethod = request.codeChallengeMethod,
          state = request.state,
          userId = knownUserId,
          credential = credential,
          step = ConversationStep.Credential(
            primaryCredentials = flow.primary.credentials,
            inlinePassword = flow.primary.inlinePassword,
            passkey = flow.passkey.isDefined,
          ),
          requestedClaims = request.requestedClaims,
          uiLocales = uiLocales,
          nonce = request.nonce,
          responseType = request.responseType,
          userEmail = userOpt.flatMap(_.email),
          userPhone = userOpt.flatMap(_.phone),
          userLogin = userOpt.flatMap(_.login),
          userClaims = userOpt.map(_.claims),
          authFlow = flow,
          userAgent = request.userAgent.map(_.filter(c => c >= ' ' && c <= '~')),
          // Strip non-printable ASCII (0x20–0x7E); does not escape HTML — always escape at render time
          version = 0,
          amr = amr,
          needsPasswordChange = false,
          targetAcr = targetAcr,
        )
        authConversationTtl <- configurationService.getAuthConversationTtl(request.clientId)
        _ <- conversationRepository.create(authId, conversation, authConversationTtl)
        r = AuthorizeResponse.Initialize(authId)
        result <-
          if knownUserId.isDefined && targetAcr.isDefined then
            conversationRouter.advance(authId, conversation).as(r)
          else if applyHint then applyLoginHint(request, uiLocales)(r)
          else ZIO.succeed(r)
      yield result

    private def silentAuthorize(
        request: AuthorizeRequest,
        uiLocales: Option[List[String]],
        sessionInfo: SessionInfo,
        satisfiedAcr: Option[Acr],
    ): Task[AuthorizeResponse] =
      val session = sessionInfo.record
      val amr = AuthMethodRef.amrClaim(session.amr)
      val acr = satisfiedAcr
      val isHybrid =
        request.responseType.contains(ResponseTypeEntry.IdToken) &&
          request.scope.contains(ScopeToken.OpenId)
      for
        idleTtl <- ZIO.unless(request.scope.contains(ScopeToken.OfflineAccess))(
          configurationService.getSessionIdleTtl(request.clientId),
        )
        _ <- ZIO.foreachDiscard(idleTtl.flatten)(sessionService.prolongIdle(sessionInfo.id, _))
        code <- authPropertyGenerator.nextAuthorizationCode
        accessToken <- authPropertyGenerator.nextAccessToken
        codeRecord = AuthorizationCodeRecord(
          sessionId = sessionInfo.id,
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
          acr = acr,
        )
        codeMac <- securityService.mac(Secret(code), config.security.authCodesSecret)
        _ <- authorizationCodeRepository.create(codeMac, codeRecord, zio.Duration.fromSeconds(60))
        idToken <- if isHybrid then silentIdToken(request, session, code, amr, uiLocales, acr)
        else ZIO.none
      yield AuthorizeResponse.Authorized(code, idToken)

    private def silentIdToken(
        request: AuthorizeRequest,
        session: SessionRecord,
        code: AuthorizationCode,
        amr: Set[AuthMethodRef],
        uiLocales: Option[List[String]],
        acr: Option[Acr],
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
          AuthMethodRef.idTokenClaims(amr, Some(session.createdAt), acr) +
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
              .orElseFail(Error.IdTokenHintInvalid(request.redirectUri, request.state))
              .flatMap: claims =>
                val audList = claims.aud match
                  case None => List.empty
                  case Some(Json.Str(s)) => List(s)
                  case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s }.toList
                  case _ => List.empty
                val audValid = audList.contains(request.clientId)
                val issValid = claims.iss.contains(config.jwt.issuer)

                if audValid && issValid then
                  ZIO.some(claims.sub)
                else
                  ZIO.fail(Error.IdTokenHintInvalid(request.redirectUri, request.state))

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
