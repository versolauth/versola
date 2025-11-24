package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, AuthorizeResponse}
import versola.oauth.conversation.ConversationRepository
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, PrimaryCredential}
import versola.security.SecureRandom
import versola.util.CoreConfig
import zio.{Task, ZLayer}

trait AuthorizeEndpointService:

  def authorize(request: AuthorizeRequest): Task[AuthorizeResponse]

object AuthorizeEndpointService:
  def live =
    ZLayer.fromFunction(Impl(_, _, _))

  class Impl(
    conversationRepository: ConversationRepository,
    secureRandom: SecureRandom,
    config: CoreConfig,
  ) extends AuthorizeEndpointService:

    override def authorize(
        request: AuthorizeRequest,
    ): Task[AuthorizeResponse] =
      for
        authId <- AuthId.wrapAll(secureRandom.nextUUIDv7)
        conversation = ConversationRecord(
          clientId = request.clientId,
          redirectUri = request.redirectUri,
          scope = request.scope,
          codeChallenge = request.codeChallenge,
          codeChallengeMethod = request.codeChallengeMethod,
          userId = None,
          credential = None,
          step = ConversationStep.Empty(PrimaryCredential.Phone, passkey = false),
        )
        _ <- conversationRepository.create(authId, conversation, config.security.authConversation.ttl)
      yield AuthorizeResponse.Initialize(authId)
