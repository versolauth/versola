package versola.oauth.conversation

import versola.oauth.AuthMetrics
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{AuthFactor, AuthFactorType, OtpType, PassedAuthFactor, PrimaryCredential, RegistrationCredential, RegistrationStep}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.user.UserRepository
import versola.util.http.Observability
import versola.util.{Email, Phone, SecureRandom}
import zio.{Task, UIO, ZIO, ZLayer}

trait ConversationRouter:
  def getConversation(authId: AuthId): Task[Option[ConversationRecord]]

  def submit(
      authId: AuthId,
      submission: Submission,
      uiLocale: Option[String],
      ipAddress: Option[String],
  ): Task[(ConversationResult.Render, ConversationRecord)]

  /** Begin a discoverable-credentials assertion and return the JSON public-key options.
    * Called by GET /challenge/passkey/options; does NOT go through the submit/render cycle.
    */
  def startPasskeyOptions(authId: AuthId): Task[Option[String]]

  /** Advance an already-created conversation to the first factor step that still needs to be
    * completed. The conversation must already have `credential` populated (set at creation time
    * when `userId` and `targetAcr` are known). Satisfied factors (present in `amr`) are skipped
    * automatically by the underlying `afterFactor` routing logic.
    */
  def advance(authId: AuthId, conversation: ConversationRecord): Task[Unit]

object ConversationRouter:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      conversationService: ConversationService,
      configService: OAuthConfigurationService,
      secureRandom: SecureRandom,
      userRepository: UserRepository,
  ) extends ConversationRouter:

    /** Sets `auth.user_id` and `auth.user_agent_id` as soon as an already-identified conversation
      * is fetched, so every log line for the rest of this request carries them — even when this
      * request's own processing (e.g. an OTP check) never itself discovers them. Both are set at
      * `/authorize`, but each `/challenge` request starts from a fresh annotation context. */
    private def setIdentifiersFromRecord(record: Option[ConversationRecord]): UIO[Unit] =
      ZIO.foreachDiscard(record.flatMap(_.userId))(uid => Observability.setUserId(uid.toString)) *>
        ZIO.foreachDiscard(record.flatMap(_.userAgentCookie))(cookie => Observability.setUserAgentId(cookie.id.toString))

    override def getConversation(authId: AuthId): Task[Option[ConversationRecord]] =
      conversationService.find(authId).orElseFail(Error.ServiceUnavailable)
        .tap(setIdentifiersFromRecord)

    override def advance(authId: AuthId, conversation: ConversationRecord): Task[Unit] =
      afterFactor(authId, conversation, nextFactorIndex = 0).unit

    override def startPasskeyOptions(authId: AuthId): Task[Option[String]] =
      conversationService.find(authId)
        .orElseFail(Error.ServiceUnavailable)
        .tap(setIdentifiersFromRecord)
        .flatMap:
          case None => ZIO.fail(Error.ConversationExpired)
          case Some(record) =>
            record.step match
              case cred: ConversationStep.Credential if record.authFlow.passkey.isDefined =>
                configService.getPasskeySettings(record.clientId).flatMap:
                  case None =>
                    ZIO.fail(new Exception("Passkeys not configured for this tenant"))
                  case Some(settings) =>
                    conversationService.startPasskeyAssertion(authId, record, cred, settings).map(Some(_))
              case _: ConversationStep.Credential =>
                ZIO.none
              case _ =>
                ZIO.fail(new Exception("startPasskeyAssertion called outside Credential step"))

    override def submit(
        authId: AuthId,
        submission: Submission,
        uiLocale: Option[String],
        ipAddress: Option[String],
    ): Task[(ConversationResult.Render, ConversationRecord)] =
      conversationService.find(authId).orElseFail(Error.ServiceUnavailable)
        .tap(setIdentifiersFromRecord)
        .flatMap:
          case None => ZIO.fail(Error.ConversationExpired)
          case Some(conversation) =>
            if submission.csrf != conversation.csrfToken then
              ZIO.fail(Error.BadRequest)
            else
              val updated = withUiLocale(conversation, uiLocale)
              dispatch(authId, updated, submission, ipAddress)
                .tapErrorCause(cause => ZIO.logErrorCause("Couldn't submit auth step", cause))
                .orElseSucceed(ConversationResult.ServiceUnavailable)
                .map(_ -> updated)

    private def dispatch(
        authId: AuthId,
        conversation: ConversationRecord,
        submission: Submission,
        ipAddress: Option[String],
    ): Task[ConversationResult.Render] =
      (submission, conversation) match
        case (_: EmailSubmission | _: PhoneSubmission, _) if conversation.userId.isDefined =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (_: EmailSubmission, _) if !conversation.authFlow.primary.credentials.contains(PrimaryCredential.email) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (_: PhoneSubmission, _) if !conversation.authFlow.primary.credentials.contains(PrimaryCredential.phone) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (submitted: EmailSubmission, _) =>
          afterCredential(authId, conversation, Left(submitted.email)).tap(observeCredential)

        case (submitted: PhoneSubmission, _) =>
          afterCredential(authId, conversation, Right(submitted.phone)).tap(observeCredential)

        case (submitted: OtpSubmission, ConversationRecord.Otp(otp, _)) =>
          conversationService.checkOtp(conversation, otp, submitted.code, authId)
            .tap(observeDecision("otp"))
            .flatMap:
              case ConversationResult.StepPassed(updated) if updated.isRegistering =>
                afterRegistrationStep(authId, updated)
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, otp.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        case (_: OtpResendSubmission, ConversationRecord.Otp(otp, credential)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, otp.factorIndex)

        case (submitted: PasswordSubmission, ConversationRecord.Password(password)) =>
          conversationService.checkPassword(conversation, password, submitted.password, authId)
            .tap(observeDecision("password"))
            .flatMap:
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, password.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        case (submitted: LoginPasswordSubmission, _) =>
          conversationService.checkLoginPassword(authId, conversation, submitted.login, submitted.password)
            .tap(observeDecision("credential"))
            .flatMap:
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, nextFactorIndex = 0)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        case (submitted: PasskeyAssertionSubmission, _) =>
          conversationService.finishPasskeyAssertion(authId, conversation, submitted.response, ipAddress)
            .tap(observePasskeyAssertion)

        case (submitted: PasskeyEnrollSubmission, ConversationRecord.PasskeyEnroll(enroll)) =>
          conversationService.finishPasskeyEnroll(authId, conversation, enroll, submitted.response, submitted.name)
            .tap(observePasskeyEnrollment)
            .flatMap:
              case ConversationResult.StepPassed(updated) if updated.isRegistering =>
                afterRegistrationStep(authId, updated)
              case ConversationResult.StepPassed(updated) =>
                conversationService.finish(authId, updated)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        case (_: PasskeySkipSubmission, ConversationRecord.PasskeyEnroll(enroll)) =>
          conversationService.skipPasskey(authId, conversation).flatMap:
            case ConversationResult.StepPassed(updated) if updated.isRegistering =>
              afterRegistrationStep(authId, updated)
            case ConversationResult.StepPassed(updated) =>
              conversationService.finish(authId, updated)
            case other: ConversationResult.Render =>
              ZIO.succeed(other)

        case (submitted: SetPasswordSubmission, ConversationRecord.SetPassword(setPasswordStep)) =>
          conversationService.setNewPassword(conversation, setPasswordStep, submitted.password, authId)
            .tap(observeDecision("set_password"))
            .flatMap:
              case ConversationResult.StepPassed(updated) if updated.isRegistering =>
                afterRegistrationStep(authId, updated)
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, setPasswordStep.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        case _ =>
          // Submission doesn't match the conversation's current step, e.g. a stale form
          // (browser back/forward, double submit) or a tampered request.
          (Observability.setError("step_mismatch") *>
            AuthMetrics.stepFailed(stepName(conversation.step)))
            .as(ConversationResult.BadRequest)

    /** Promote the locale the user picked in the form to the front of the conversation's preferred
      * locales, so every subsequent render keeps it. Applied in-memory before dispatch — it is
      * persisted by the same overwrite the submission already performs.
      */
    private def withUiLocale(conversation: ConversationRecord, uiLocale: Option[String]): ConversationRecord =
      uiLocale.fold(conversation): locale =>
        conversation.copy(uiLocales = Some(locale :: conversation.uiLocales.getOrElse(Nil).filterNot(_ == locale)))

    /** A factor is already satisfied when some passed factor in `conversation.amr` matches it
      * directly or counts as an equivalent (e.g. a passed passkey satisfies an otp factor).
      */
    private def isSatisfied(conversation: ConversationRecord, factor: AuthFactor): Boolean =
      PassedAuthFactor.fromFactorType(factor.`type`)
        .exists(required => conversation.amr.keySet.exists(_.satisfies(required, conversation.authFlow.equivalents)))

    /** Advance past the registration step that just passed and run the next one. */
    private def afterRegistrationStep(
        authId: AuthId,
        conversation: ConversationRecord,
    ): Task[ConversationResult.Render] =
      runRegistrationStep(authId, conversation.copy(registrationStep = conversation.registrationStep.map(_ + 1)))

    /** Resolve the account only after OTP verification, immediately before an account-bound setup
      * step or successful completion.
      */
    private def withVerifiedRegistrationUser(
        authId: AuthId,
        conversation: ConversationRecord,
    )(continue: ConversationRecord => Task[ConversationResult.Render]): Task[ConversationResult.Render] =
      val credentialVerified =
        conversation.amr.contains(PassedAuthFactor.otp) &&
          conversation.registrationStep.exists: index =>
            conversation.registrationFlow.exists(_.steps.take(index).contains(RegistrationStep.Otp()))
      if conversation.userId.isDefined then continue(conversation)
      else if !credentialVerified then
        conversationService.accessDenied(authId, conversation)
      else
        (conversation.registrationFlow, conversation.credential) match
          case (Some(flow), Some(credential)) =>
            conversationService.registerVerifiedUser(authId, conversation, flow, credential).flatMap:
              case RegistrationEntry.Registering(updated) => continue(updated)
              case ConversationResult.WriteConflict => ZIO.succeed(ConversationResult.WriteConflict)
          case _ =>
            conversationService.accessDenied(authId, conversation)

    /** Render the pending registration step, or finish once the flow is exhausted.
      *
      * Registration steps stand in for the authentication factors rather than running in
      * addition to them: the user has just proven the same credential the auth flow would ask
      * about, so on completion the conversation finishes instead of falling back into
      * `afterFactor`.
      */
    private def runRegistrationStep(
        authId: AuthId,
        conversation: ConversationRecord,
    ): Task[ConversationResult.Render] =
      conversation.currentRegistrationStep match
        case Some(_: RegistrationStep.Otp) =>
          conversation.credential match
            case Some(credential) =>
              conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = 0)
            case None =>
              conversationService.accessDenied(authId, conversation)

        case Some(_: RegistrationStep.SetPassword) =>
          withVerifiedRegistrationUser(authId, conversation): verified =>
            conversationService.offerSetPassword(authId, verified)

        case Some(_: RegistrationStep.PasskeyEnroll) =>
          withVerifiedRegistrationUser(authId, conversation): verified =>
            conversationService.offerPasskeyEnroll(authId, verified)

        case None =>
          withVerifiedRegistrationUser(authId, conversation): verified =>
            conversationService.finish(authId, verified)

    /** Determine the first factor step after the user submits their credential,
      * skipping any factor types already recorded in `conversation.amr`.
      */
    private def afterCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
    ): Task[ConversationResult.Render] =
      userRepository.findByCredential(credential).flatMap: user =>
        (user, conversation.registrationFlow) match
          case (Some(user), _) =>
            val identified = conversation.copy(
              userId = Some(user.id),
              credential = Some(credential),
              userEmail = user.email,
              userPhone = user.phone,
              userLogin = user.login,
              userClaims = Some(user.claims),
            )
            afterAuthenticationCredential(authId, identified, credential)

          case (None, Some(flow)) if flow.credential == RegistrationCredential.from(credential) =>
            conversationService.startRegistration(authId, conversation, flow, credential).flatMap:
              case RegistrationEntry.Registering(updated) =>
                runRegistrationStep(authId, updated)
              case ConversationResult.WriteConflict =>
                ZIO.succeed(ConversationResult.WriteConflict)

          case (None, Some(_)) =>
            afterAuthenticationCredential(authId, conversation, credential)

          case (None, None) =>
            afterAuthenticationCredential(authId, conversation, credential)

    private def afterAuthenticationCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
    ): Task[ConversationResult.Render] =
      nextNeededFactor(conversation, conversation.authFlow.primary.factors.zipWithIndex).flatMap:
        case Some((AuthFactor(AuthFactorType.otp, _), idx)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = idx)

        case Some((AuthFactor(AuthFactorType.password, _), idx)) =>
          conversationService.prepareInitialPassword(authId, conversation, credential, factorIndex = idx)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) =>
          // passkeyEnroll configured as a primary factor is a tenant configuration bug -- it's
          // only ever offered after all primary factors pass, never as one itself.
          Observability.setError("illegal_state", Some("passkeyEnroll configured as primary factor")) *>
            conversationService.accessDenied(authId, conversation)

        case None =>
          conversationService.finish(authId, conversation)

    /** Determine the next step after a factor step passes, or finish if no more factors.
      * The `conversation.amr` map already includes the factor that just passed.
      * Factors whose type appears in that map are skipped.
      */
    private def afterFactor(
        authId: AuthId,
        conversation: ConversationRecord,
        nextFactorIndex: Int,
    ): Task[ConversationResult.Render] =
      nextNeededFactor(conversation, conversation.authFlow.primary.factors.zipWithIndex.drop(nextFactorIndex)).flatMap:
        case Some((AuthFactor(AuthFactorType.otp, _), idx)) =>
          val credential = conversation.authFlow.otpType match
            case OtpType.email =>
              conversation.userEmail.map(Left(_))
            case OtpType.sms =>
              conversation.userPhone.map(Right(_))

          credential match
            case Some(cred) => conversationService.prepareInitialOtp(authId, conversation, cred, idx)
            case _ => conversationService.accessDenied(authId, conversation)

        case Some((AuthFactor(AuthFactorType.password, _), idx)) =>
          conversationService.preparePasswordStep(authId, conversation, idx)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) if conversation.needsPasswordChange =>
          afterFactor(authId, conversation, nextFactorIndex + 1)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) =>
          conversationService.offerPasskeyEnroll(authId, conversation)

        case None if conversation.needsPasswordChange =>
          conversationService.offerSetPassword(authId, conversation)

        case None =>
          conversationService.finish(authId, conversation)

    private def nextNeededFactor(
        conversation: ConversationRecord,
        factors: List[(AuthFactor, Int)],
    ): Task[Option[(AuthFactor, Int)]] =
      for
        vocabulary <- configService.getAcrVocabulary(conversation.clientId)
        reqFactors = conversation.targetAcr.flatMap(vocabulary.get).map(_.toList.toSet).getOrElse(Set.empty)
      yield factors.find { case (factor, _) =>
        val passed = isSatisfied(conversation, factor)
        val needed = factor.required || PassedAuthFactor.fromFactorType(factor.`type`).exists(reqFactors.contains)
        !passed && needed
      }

    private def observeCredential(result: ConversationResult.Render): UIO[Unit] =
      result match
        case ConversationResult.BadRequest | ConversationResult.WriteConflict | ConversationResult.ServiceUnavailable |
            ConversationResult.RenderStep(ConversationStep.AccessDenied) =>
          AuthMetrics.stepFailed("credential")
        case _ =>
          AuthMetrics.stepPassed("credential")

    private def observeDecision(step: String)(result: ConversationResult): UIO[Unit] =
      result match
        case ConversationResult.StepPassed(_) => AuthMetrics.stepPassed(step)
        case _ => AuthMetrics.stepFailed(step)

    private def observePasskeyAssertion(result: ConversationResult.Render): UIO[Unit] =
      result match
        case ConversationResult.Complete(_, _, _, _, _, _, _) |
            ConversationResult.RenderStep(_: ConversationStep.PasskeyEnroll) =>
          AuthMetrics.stepPassed("passkey")
        case _ =>
          AuthMetrics.stepFailed("passkey")

    private def observePasskeyEnrollment(result: ConversationResult): UIO[Unit] =
      result match
        case ConversationResult.Complete(_, _, _, _, _, _, _) | ConversationResult.StepPassed(_) =>
          AuthMetrics.stepPassed("passkey_enroll")
        case ConversationResult.RenderStep(step: ConversationStep.PasskeyEnroll) if !step.enrollFailed =>
          AuthMetrics.stepPassed("passkey_enroll")
        case _ =>
          AuthMetrics.stepFailed("passkey_enroll")

    private def stepName(step: ConversationStep): String =
      step match
        case _: ConversationStep.Credential => "credential"
        case _: ConversationStep.Otp => "otp"
        case _: ConversationStep.Password => "password"
        case _: ConversationStep.SetPassword => "set_password"
        case _: ConversationStep.PasskeyEnroll => "passkey_enroll"
        case ConversationStep.AccessDenied => "access_denied"
