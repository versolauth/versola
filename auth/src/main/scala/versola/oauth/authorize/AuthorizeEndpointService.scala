package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse, Error}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.conversation.ConversationRepository
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep}
import versola.util.{CoreConfig, SecureRandom}
import zio.{IO, ZIO, ZLayer}

trait AuthorizeEndpointService:

  def authorize(request: AuthorizeRequest): IO[Error.AuthFlowMissing, AuthorizeResponse]

object AuthorizeEndpointService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
    conversationRepository: ConversationRepository,
    configurationService: OAuthConfigurationService,
    secureRandom: SecureRandom,
    config: CoreConfig,
  ) extends AuthorizeEndpointService:

    override def authorize(
        request: AuthorizeRequest,
    ): IO[Error.AuthFlowMissing, AuthorizeResponse] =
      for
        authId <- AuthId.wrapAll(secureRandom.nextUUIDv7)
        client <- configurationService.find(request.clientId)
        authFlow = client.flatMap(_.authFlow)
        flow <- ZIO
          .fromOption(authFlow)
          .orElseFail(Error.AuthFlowMissing(request.redirectUri, request.state))
        conversation = ConversationRecord(
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
          uiLocales = request.uiLocales,
          nonce = request.nonce,
          responseType = request.responseType,
          userEmail = None,
          userPhone = None,
          userLogin = None,
          userClaims = None,
          authFlow = flow,
          userAgent = request.userAgent,
        )
        _ <- conversationRepository.create(authId, conversation, config.security.authConversation.ttl).orDie
      yield AuthorizeResponse.Initialize(authId)
