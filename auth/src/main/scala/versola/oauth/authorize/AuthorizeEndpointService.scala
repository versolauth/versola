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

  /** What to do when a known identity (session- or id_token_hint-derived) no longer resolves to a user. */
  private enum MissingUserBehavior:
    /** Step-up / re-verify of the current session: the session-bound identity must exist. */
    case Deny
    /** Fresh id_token_hint or account switch: the hint is advisory, drop it and prompt for credentials. */
    case Fallback
    /** No known identity is expected. */
    case Ignore

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
                      missingUser = MissingUserBehavior.Fallback,
                    )
              case None =>
                createConversation(
                  request,
                  flow,
                  uiLocales,
                  Map.empty,
                  knownUserId = Some(userId),
                  missingUser = MissingUserBehavior.Fallback,
                )

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
                  // Re-verifying the existing session identity must deny on a missing user; switching to a
                  // different (hint-derived) identity treats the hint as advisory and falls back to login.
                  val reauthMissingUser =
                    if targetUserId.contains(session.userId) then MissingUserBehavior.Deny
                    else MissingUserBehavior.Fallback
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
                            missingUser = reauthMissingUser,
                            priorSessionId = Some(id),
                          )
                    case _ =>
                      createConversation(
                        request,
                        flow,
                        uiLocales,
                        Map.empty,
                        knownUserId = targetUserId,
                        missingUser = reauthMissingUser,
                        priorSessionId = Some(id),
                      )
                else if !acrSatisfied then
                  // A deleted user has no auth factors registered, so resolveAchievableAcr would
                  // return None → UnmetAuthenticationRequirements instead of AccessDenied.
                  // Check existence first so the right error is returned.
                  userRepository.find(session.userId).flatMap:
                    case None => ZIO.fail(Error.AccessDenied(request.redirectUri, request.state))
                    case Some(_) =>
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
                            missingUser = MissingUserBehavior.Deny,
                            priorSessionId = Some(id),
                          )
                else if !factorsSatisfied then
                  createConversation(
                    request,
                    flow,
                    uiLocales,
                    session.amr,
                    applyHint = false,
                    knownUserId = Some(session.userId),
                    missingUser = MissingUserBehavior.Deny,
                    priorSessionId = Some(id),
                  )
                else
                  silentAuthorize(request, uiLocales, SessionInfo(id, session), satisfiedAcr)
            yield result
      yield response

    private def applyLoginHint(request: AuthorizeRequest, uiLocales: Option[List[String]], csrfToken: String)(response: AuthorizeResponse): Task[AuthorizeResponse] =
      response match
        case AuthorizeResponse.Initialize(authId) =>
          request.loginHint match
            case Some(Left(email)) => conversationRouter.submit(authId, EmailSubmission(email, csrfToken), uiLocales.flatMap(_.headOption), None).as(response)
            case Some(Right(phone)) => conversationRouter.submit(authId, PhoneSubmission(phone, csrfToken), uiLocales.flatMap(_.headOption), None).as(response)
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
        missingUser: MissingUserBehavior = MissingUserBehavior.Ignore,
        priorSessionId: Option[MAC.Of[SessionId]] = None,
    ): Task[AuthorizeResponse] =
      for
        authId <- AuthId.wrapAll(secureRandom.nextUUIDv7)
        userOpt <- knownUserId match
          case Some(uid) => userRepository.find(uid)
          case None => ZIO.none
        // Enforce that a known identity still resolves to a user:
        //   • Deny (step-up / re-verify): the session-bound identity must exist, otherwise fail with
        //     access_denied rather than admit a different identity.
        //   • Fallback (fresh id_token_hint or account switch): the hint is advisory, so drop the
        //     missing user and fall through to normal credential entry.
        effectiveUserId <- (knownUserId, userOpt) match
          case (Some(_), None) =>
            missingUser match
              case MissingUserBehavior.Deny => ZIO.fail(Error.AccessDenied(request.redirectUri, request.state))
              case _ => ZIO.none
          case _ => ZIO.succeed(knownUserId)
        // When a verified userId is available (from session or id_token_hint), always populate all
        // user fields so the credential step can be skipped and challenges are asked directly.
        // If no userId is known, userOpt is None and fields remain empty for normal credential entry.
        csrfToken <- secureRandom.nextAlphanumeric(16)
        credential = userOpt.flatMap(u => u.email.map(Left(_)).orElse(u.phone.map(Right(_))))
        conversation = ConversationRecord(
          clientId = request.clientId,
          redirectUri = request.redirectUri,
          scope = request.scope,
          codeChallenge = request.codeChallenge,
          codeChallengeMethod = request.codeChallengeMethod,
          state = request.state,
          userId = effectiveUserId,
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
          csrfToken = csrfToken,
          priorSessionId = priorSessionId,
        )
        authConversationTtl <- configurationService.getAuthConversationTtl(request.clientId)
        _ <- conversationRepository.create(authId, conversation, authConversationTtl)
        r = AuthorizeResponse.Initialize(authId)
        result <-
          // If userId is already established (from a session or id_token_hint), skip the credential
          // step entirely and advance straight to the required challenges.
          if effectiveUserId.isDefined then
            conversationRouter.advance(authId, conversation).as(r)
          else if applyHint then applyLoginHint(request, uiLocales, csrfToken)(r)
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
        _ <- sessionService.registerClient(sessionInfo.id, request.clientId)
        code <- authPropertyGenerator.nextAuthorizationCode
        accessToken <- authPropertyGenerator.nextAccessToken
        codeRecord = AuthorizationCodeRecord(
          sessionId = sessionInfo.id,
          publicSessionId = session.publicId,
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
          ("c_hash" -> Json.Str(cHash)) + ("sid" -> Json.Str(session.publicId))
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
