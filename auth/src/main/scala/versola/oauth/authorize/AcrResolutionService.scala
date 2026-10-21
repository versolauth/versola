package versola.oauth.authorize

import versola.oauth.challenge.passkey.PasskeyRepository
import versola.oauth.challenge.password.PasswordService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Acr, AuthFactorType, AuthFlow, ClientId, PassedAuthFactor}
import versola.user.UserRepository
import versola.user.model.UserId
import zio.prelude.NonEmptyList
import zio.{Ref, Task, UIO, ZIO, ZLayer}

trait AcrResolutionService:
  def resolveAchievableAcr(
      userId: UserId,
      acrValues: NonEmptyList[Acr],
      clientId: ClientId,
      flow: AuthFlow,
      sessionAmr: Set[PassedAuthFactor],
  ): Task[Option[Acr]]

  /** The first of `acrValues`, in the order asked, that the factors in `amr` already cover for this user. */
  def checkAcrSatisfaction(
      clientId: ClientId,
      acrValues: NonEmptyList[Acr],
      amr: Set[PassedAuthFactor],
      userId: UserId,
      flow: AuthFlow,
  ): Task[Option[Acr]]

  /** The factors a class the server defines itself ([[AcrResolutionService.builtIn]]) asks of this
    * user, `None` when `acr` is not such a class, when the tenant's own vocabulary defines the name
    * (the tenant's definition wins), or when nothing the flow offers can satisfy it for this user.
    */
  def builtInFactors(
      clientId: ClientId,
      acr: Acr,
      userId: UserId,
      flow: AuthFlow,
  ): Task[Option[NonEmptyList[PassedAuthFactor]]]

object AcrResolutionService:
  /** The authentication a sensitive operation asks for: the strongest factor this user has, and one
    * the sign-in flow can ask for. That is a passkey when the user has one, else a password, else a
    * one-time code -- a one-time code must never stand in for a passkey or a password the user has
    * enrolled, since it is what a SIM swap or a phished SMS yields.
    *
    * Which factor that is depends on the user, so it cannot be listed in a tenant's `acrVocabulary`,
    * whose entries are the same for everyone; a resource's `stepUpAcr` names this class instead.
    */
  val Sensitive: Acr = Acr("sensitive")

  /** Classes the server defines itself. A tenant that defines one of these names in its own
    * vocabulary overrides it. */
  val builtIn: Set[Acr] = Set(Sensitive)

  def isBuiltIn(acr: Acr): Boolean = builtIn.contains(acr)

  def live = ZLayer.fromFunction(Impl(_, _, _, _))

  class Impl(
      configurationService: OAuthConfigurationService,
      userRepository: UserRepository,
      passkeyRepository: PasskeyRepository,
      passwordService: PasswordService,
  ) extends AcrResolutionService:

    /** What a user has enrolled, looked up at most once per resolution. */
    private class UserFacts(val hasOtp: Task[Boolean], val hasPassword: Task[Boolean], val hasPasskey: Task[Boolean])

    private def userFacts(userId: UserId): UIO[UserFacts] =
      def cached(ref: Ref[Option[Boolean]])(fetch: Task[Boolean]): Task[Boolean] =
        ref.get.flatMap:
          case Some(v) => ZIO.succeed(v)
          case None => fetch.tap(v => ref.set(Some(v)))

      for
        hasOtpRef <- Ref.make[Option[Boolean]](None)
        hasPasswordRef <- Ref.make[Option[Boolean]](None)
        hasPasskeyRef <- Ref.make[Option[Boolean]](None)
      yield UserFacts(
        hasOtp = cached(hasOtpRef)(userRepository.find(userId).map(_.exists(u => u.email.isDefined || u.phone.isDefined))),
        hasPassword = cached(hasPasswordRef)(passwordService.hasPassword(userId)),
        hasPasskey = cached(hasPasskeyRef)(passkeyRepository.listByUser(userId).map(_.nonEmpty)),
      )

    /** Fails closed: a user who has a passkey the flow cannot ask for gets nothing, not a weaker
      * factor, so a tenant cannot be talked into accepting a one-time code in its place. */
    private def sensitiveFactors(facts: UserFacts, flow: AuthFlow): Task[Option[NonEmptyList[PassedAuthFactor]]] =
      def offers(factorType: AuthFactorType): Boolean = flow.primary.factors.exists(_.`type` == factorType)

      facts.hasPasskey.flatMap:
        case true => ZIO.succeed(Option.when(flow.passkey.isDefined)(NonEmptyList(PassedAuthFactor.passkey)))
        case false =>
          facts.hasPassword.flatMap:
            case true if offers(AuthFactorType.password) => ZIO.some(NonEmptyList(PassedAuthFactor.password))
            case _ => facts.hasOtp.map(hasOtp => Option.when(hasOtp && offers(AuthFactorType.otp))(NonEmptyList(PassedAuthFactor.otp)))

    override def builtInFactors(
        clientId: ClientId,
        acr: Acr,
        userId: UserId,
        flow: AuthFlow,
    ): Task[Option[NonEmptyList[PassedAuthFactor]]] =
      if !isBuiltIn(acr) then ZIO.none
      else
        configurationService.getAcrVocabulary(clientId).flatMap: vocabulary =>
          if vocabulary.contains(acr) then ZIO.none
          else userFacts(userId).flatMap(sensitiveFactors(_, flow))

    override def resolveAchievableAcr(
        userId: UserId,
        acrValues: NonEmptyList[Acr],
        clientId: ClientId,
        flow: AuthFlow,
        sessionAmr: Set[PassedAuthFactor],
    ): Task[Option[Acr]] =
      configurationService.getAcrVocabulary(clientId).flatMap: vocabulary =>
        userFacts(userId).flatMap: facts =>
          acrValues.toList.foldLeft(ZIO.succeed(Option.empty[Acr])): (acc, acr) =>
            acc.flatMap:
              case found @ Some(_) => ZIO.succeed(found)
              case None =>
                vocabulary.get(acr) match
                  case None if isBuiltIn(acr) => sensitiveFactors(facts, flow).map(_.map(_ => acr))
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
                                case PassedAuthFactor.otp => facts.hasOtp
                                case PassedAuthFactor.password => facts.hasPassword
                                // A passkey the client's flow cannot ask for is not reachable, however many
                                // the user has: nothing would present it.
                                case PassedAuthFactor.passkey =>
                                  if flow.passkey.isDefined then facts.hasPasskey else ZIO.succeed(false)
                      .map(if _ then Some(acr) else None)

    override def checkAcrSatisfaction(
        clientId: ClientId,
        acrValues: NonEmptyList[Acr],
        amr: Set[PassedAuthFactor],
        userId: UserId,
        flow: AuthFlow,
    ): Task[Option[Acr]] =
      def covers(required: NonEmptyList[PassedAuthFactor]): Boolean =
        required.forall(factor => amr.exists(_.satisfies(factor, flow.equivalents)))

      configurationService.getAcrVocabulary(clientId).flatMap { vocabulary =>
        acrValues.toList.foldLeft(ZIO.succeed(Option.empty[Acr])): (acc, acr) =>
          acc.flatMap:
            case found @ Some(_) => ZIO.succeed(found)
            case None =>
              vocabulary.get(acr) match
                case Some(required) => ZIO.succeed(Option.when(covers(required))(acr))
                // Whether the session already covers it depends on what this user has enrolled: a
                // session that passed only a one-time code does not cover a user who has a passkey.
                case None if isBuiltIn(acr) => builtInFactors(clientId, acr, userId, flow).map(_.filter(covers).map(_ => acr))
                case None => ZIO.none
      }
