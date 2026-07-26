package versola.oauth.authorize

import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFlow, ClientId, PassedAuthFactor}
import versola.user.UserRepository
import versola.user.model.UserId
import zio.prelude.NonEmptyList
import zio.{Ref, Task, ZIO, ZLayer}

trait AcrResolutionService:
  def resolveAchievableAcr(
      userId: UserId,
      acrValues: NonEmptyList[Acr],
      clientId: ClientId,
      flow: AuthFlow,
      sessionAmr: Set[PassedAuthFactor],
  ): Task[Option[Acr]]

  def checkAcrSatisfaction(
      clientId: ClientId,
      acrValues: NonEmptyList[Acr],
      amr: Set[PassedAuthFactor],
      equivalents: Map[PassedAuthFactor, Set[PassedAuthFactor]],
  ): Task[Option[Acr]]

object AcrResolutionService:
  def live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      configurationService: OAuthConfigurationService,
      userRepository: UserRepository,
      passkeyRepository: PasskeyRepository,
      passwordService: PasswordService,
  ) extends AcrResolutionService:

    override def resolveAchievableAcr(
        userId: UserId,
        acrValues: NonEmptyList[Acr],
        clientId: ClientId,
        flow: AuthFlow,
        sessionAmr: Set[PassedAuthFactor],
    ): Task[Option[Acr]] =
      def cached(ref: Ref[Option[Boolean]])(fetch: Task[Boolean]): Task[Boolean] =
        ref.get.flatMap:
          case Some(v) => ZIO.succeed(v)
          case None => fetch.tap(v => ref.set(Some(v)))

      configurationService.getAcrVocabulary(clientId).flatMap: vocabulary =>
        for
          hasOtpRef <- Ref.make[Option[Boolean]](None)
          hasPasswordRef <- Ref.make[Option[Boolean]](None)
          hasPasskeyRef <- Ref.make[Option[Boolean]](None)
          hasOtp = cached(hasOtpRef)(userRepository.find(userId).map(_.exists(u => u.email.isDefined || u.phone.isDefined)))
          hasPassword = cached(hasPasswordRef)(passwordService.hasPassword(userId))
          hasPasskey = cached(hasPasskeyRef)(passkeyRepository.listByUser(userId).map(_.nonEmpty))
          achievable <- acrValues.toList.foldLeft(ZIO.succeed(Option.empty[Acr])): (acc, acr) =>
            acc.flatMap:
              case found @ Some(_) => ZIO.succeed(found)
              case None =>
                vocabulary.get(acr) match
                  case None => ZIO.none
                  case Some(reqFactors) =>
                    reqFactors.toList
                      .foldLeft(ZIO.succeed(true)): (allMet, req) =>
                        allMet.flatMap:
                          case false => ZIO.succeed(false)
                          case true =>
                            if sessionAmr.exists(_.satisfies(req, flow.equivalents)) then ZIO.succeed(true)
                            else
                              req match
                                case PassedAuthFactor.otp => hasOtp
                                case PassedAuthFactor.password => hasPassword
                                case PassedAuthFactor.passkey => hasPasskey
                      .map(if _ then Some(acr) else None)
        yield achievable

    override def checkAcrSatisfaction(
        clientId: ClientId,
        acrValues: NonEmptyList[Acr],
        amr: Set[PassedAuthFactor],
        equivalents: Map[PassedAuthFactor, Set[PassedAuthFactor]],
    ): Task[Option[Acr]] =
      configurationService.getAcrVocabulary(clientId).map { vocabulary =>
        acrValues.find { acr =>
          vocabulary.get(acr) match
            case None => false
            case Some(reqFactors) =>
              reqFactors.forall(required =>
                amr.exists(_.satisfies(required, equivalents)),
              )
        }
      }
