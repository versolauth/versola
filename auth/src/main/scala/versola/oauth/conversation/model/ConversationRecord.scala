package versola.oauth.conversation.model

import versola.oauth.authorize.model.ResponseTypeEntry
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.{Login, UserId}
import versola.util.{Email, Phone}
import zio.http.URL
import zio.json.ast.Json
import zio.prelude.NonEmptySet

case class ConversationRecord(
    clientId: ClientId,
    redirectUri: URL,
    scope: Set[ScopeToken],
    codeChallenge: CodeChallenge,
    codeChallengeMethod: CodeChallengeMethod,
    state: Option[State],
    userId: Option[UserId],
    credential: Option[Either[Email, Phone]],
    step: ConversationStep,
    requestedClaims: Option[RequestedClaims],
    uiLocales: Option[List[String]],
    nonce: Option[Nonce],
    responseType: NonEmptySet[ResponseTypeEntry],
    userEmail: Option[Email],
    userPhone: Option[Phone],
    userLogin: Option[Login],
    userClaims: Option[Json.Obj],
):
  def patch(patch: ConversationRecord.Patch): ConversationRecord =
    this.copy(
      userId = patch.userId.getOrElse(userId),
      credential = patch.credential.getOrElse(credential),
      step = patch.step.getOrElse(step),
    )

object ConversationRecord:

  case class Patch(
      userId: Option[Option[UserId]],
      credential: Option[Option[Either[Email, Phone]]],
      step: Option[ConversationStep],
  ):
    def isEmpty: Boolean = this == Patch.empty

  object Patch:
    val empty: Patch = Patch(
      userId = None,
      credential = None,
      step = None,
    )

  object Otp:
    def unapply(record: ConversationRecord): Option[(ConversationStep.Otp, Either[Email, Phone])] =
      (record.step, record.credential) match
        case (otp: ConversationStep.Otp, Some(credential)) => Some((otp, credential))
        case _ => None

  object Password:
    def unapply(record: ConversationRecord): Option[ConversationStep.Password] =
      record.step match
        case password: ConversationStep.Password => Some(password)
        case _ => None
