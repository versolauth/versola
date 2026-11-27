package versola.oauth.conversation

import versola.audit.AuditSupport.*
import versola.audit.AuthAuditDetails
import versola.audit.AuthAuditKinds
import versola.oauth.AuthMetrics
import versola.oauth.challenge.passkey.WebAuthnService
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{
  AuthFactor,
  AuthFactorType,
  ClientId,
  OtpType,
  PassedAuthFactor,
  PhoneRegion,
  PrimaryCredential,
  RegistrationCredential,
  RegistrationStep,
}
import versola.oauth.conversation.model.{AuthId, ConversationRecord, ConversationStep, Error}
import versola.oauth.handoff.CellHandoff
import versola.oauth.session.model.SupersededSession
import versola.user.model.{UserId, UserRecord}
import versola.user.move.{DepartureRefusal, UserDepartures}
import versola.user.{UserDirectory, UserRepository}
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.Observability
import versola.util.{CoreConfig, Email, Phone, RoutingKey, SecureRandom}
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

  /** Ends the conversation of a submission that was sealed for another cell ([[ConversationResult.Elsewhere]]): gone
    * whatever the browser does next, the sign-in continues in the other cell or not at all. False when another request
    * ended it already: of two that submit the same thing at once only one is sent on, so that there are never two seals
    * for one password or one passkey assertion. */
  def end(authId: AuthId, conversation: ConversationRecord): Task[Boolean]

  /** Records, on the conversation this cell has just started for a sign-in handed to it, the session the browser had
    * in the first cell (versolauth/versola#1048). When the sign-in finishes, that session is ended through central
    * (`UserDirectory.sessionSuperseded`); until then it is left alone, so that a sign-in that fails or is abandoned
    * costs the person nothing. */
  def supersedes(authId: AuthId, session: SupersededSession): Task[Unit]

  /** A submission another cell took and handed to this one, for the conversation this cell has just started for it
    * (`CellHandoff`, versolauth/versola#1048). The CSRF token it carries is the first cell's and is not compared: the
    * seal it came with, bound to the browser's cookie and taken once, is what says that the browser that started the
    * sign-in sent it. It is answered here whatever it names: a submission is never handed on twice.
    *
    * @param passkeyRequest the assertion request a passkey submission answers, as the first cell issued it
    */
  def submitHanded(
      authId: AuthId,
      submission: ConversationRouter.HandedSubmission,
      passkeyRequest: Option[String],
      uiLocale: Option[String],
      ipAddress: Option[String],
  ): Task[(ConversationResult.Render, ConversationRecord)]

  /** Advance an already-created conversation to the first factor step that still needs to be
    * completed. The conversation must already have `credential` populated (set at creation time
    * when `userId` and `targetAcr` are known). Satisfied factors (present in `amr`) are skipped
    * automatically by the underlying `afterFactor` routing logic.
    */
  def advance(authId: AuthId, conversation: ConversationRecord): Task[Unit]

object ConversationRouter:
  /** What a cell takes in as handed to it: a login and password or a passkey that another cell sealed
    * (`AuthorizeEndpointController.handedSubmissionRoute`), and the address a sign-in handed by `GET /challenge/handoff`
    * starts with, which is answered here without asking central again. */
  type HandedSubmission = LoginPasswordSubmission | PasskeyAssertionSubmission | EmailSubmission | PhoneSubmission

  def live = ZLayer.fromFunction(Impl(_, _, _, _, _, _, _, _, _, _, _))

  class Impl(
      conversationRepository: ConversationRepository,
      conversationService: ConversationService,
      configService: OAuthConfigurationService,
      secureRandom: SecureRandom,
      userRepository: UserRepository,
      audit: AuditEvents,
      directory: UserDirectory,
      handoff: CellHandoff,
      config: CoreConfig,
      webAuthnService: WebAuthnService,
      departures: UserDepartures,
  ) extends ConversationRouter:

    /** A user who is leaving this cell (versolauth/versola#1051) is not signed in: the step is the "service unavailable" any step
      * has when something it needs is not there, and the conversation stays where it was. `None` when the user is served here.
      * A user that has moved is no user of this cell either, and is refused alike (a sign-in for their address goes through
      * the directory, which names the cell they are in). One read by user id, where the user is looked at anyway. */
    private def refusedDeparture(userId: Option[UserId]): Task[Option[ConversationResult.Render]] =
      userId.fold(ZIO.none: Task[Option[ConversationResult.Render]]): id =>
        departures.refuse(id).foldZIO(
          {
            case _: DepartureRefusal => Observability.setError("user_leaving").as(Some(ConversationResult.ServiceUnavailable))
            case failure: Throwable => ZIO.fail(failure)
          },
          _ => ZIO.none,
        )

    /** The result of a step that has identified a user, unless that user is leaving. */
    private def unlessLeaving(result: ConversationResult.Render): Task[ConversationResult.Render] =
      val identified = result match
        case complete: ConversationResult.Complete => Some(complete.userAgentData.userId)
        case _ => None
      refusedDeparture(identified).map(_.getOrElse(result))

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
              // From the code page itself; not an alternative of this page now answers as a passkey that cannot be had.
              case otp: ConversationStep.Otp =>
                configService.getPasskeySettings(record.clientId).flatMap:
                  case None => ZIO.none
                  case Some(settings) => conversationService.startPasskeyOnCode(authId, record, otp, settings)
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
              dispatch(authId, updated, submission, ipAddress, handed = false)
                .tap(endSuperseded(updated))
                .tapErrorCause(cause => ZIO.logErrorCause("Couldn't submit auth step", cause))
                .orElseSucceed(ConversationResult.ServiceUnavailable)
                .map(_ -> updated)

    override def supersedes(authId: AuthId, session: SupersededSession): Task[Unit] =
      conversationService.find(authId).flatMap:
        case None => ZIO.unit
        case Some(conversation) =>
          conversationRepository
            .overwrite(
              authId,
              conversation.copy(supersededSessionId = Some(session.sessionId), supersededSessionUserId = Some(session.userId)),
            )
            .flatMap(written => ZIO.logWarning("The session a handed sign-in replaces was not recorded; it is left to expire").unless(written))
            .unit

    /** After a sign-in that was handed here has finished: the session the browser had in the first cell is ended there.
      * Not a reason to fail a sign-in that is done: a session that could not be ended stays until it expires, as it
      * did before it was handed over, and that is said in the log. */
    private def endSuperseded(conversation: ConversationRecord)(result: ConversationResult.Render): UIO[Unit] =
      (result, conversation.supersededSession) match
        case (_: ConversationResult.Complete, Some(session)) =>
          directory.sessionSuperseded(session).catchAllCause: cause =>
            ZIO.logWarningCause("Could not end the session a handed sign-in replaced; it is left to expire", cause)
        case _ => ZIO.unit

    override def end(authId: AuthId, conversation: ConversationRecord): Task[Boolean] =
      conversationRepository.delete(authId, conversation.version)

    override def submitHanded(
        authId: AuthId,
        submission: HandedSubmission,
        passkeyRequest: Option[String],
        uiLocale: Option[String],
        ipAddress: Option[String],
    ): Task[(ConversationResult.Render, ConversationRecord)] =
      conversationService.find(authId).orElseFail(Error.ServiceUnavailable)
        .flatMap:
          case None => ZIO.fail(Error.ConversationExpired)
          case Some(conversation) =>
            val located = withUiLocale(conversation, uiLocale)
            withHandedPasskeyRequest(authId, located, passkeyRequest)
              .flatMap:
                case None =>
                  Observability.setError("step_mismatch", Some("a passkey was handed to a request that takes none")).as(ConversationResult.BadRequest)
                case Some(prepared) => dispatch(authId, prepared, submission, ipAddress, handed = true).tap(endSuperseded(prepared))
              .tapErrorCause(cause => ZIO.logErrorCause("Couldn't submit a handed auth step", cause))
              .orElseSucceed(ConversationResult.ServiceUnavailable)
              .map(_ -> located)

    /** The conversation as it would be had this cell issued the assertion request itself: the request the first cell
      * sealed becomes the pending one of the credential card. `None` when the request this cell started has no card
      * that takes a passkey with no user (its client asks for something else here). */
    private def withHandedPasskeyRequest(
        authId: AuthId,
        conversation: ConversationRecord,
        passkeyRequest: Option[String],
    ): Task[Option[ConversationRecord]] =
      passkeyRequest match
        case None => ZIO.some(conversation)
        case Some(request) =>
          conversation.step match
            case card: ConversationStep.Credential if card.passkey && !card.passkeyOnly && conversation.userId.isEmpty =>
              val pending = conversation.copy(step = card.copy(passkeyRequest = Some(request), passkeyFailed = false))
              conversationRepository.overwrite(authId, pending).map:
                case true => Some(pending.copy(version = pending.version + 1))
                case false => None
            case _ => ZIO.none

    private def dispatch(
        authId: AuthId,
        conversation: ConversationRecord,
        submission: Submission,
        ipAddress: Option[String],
        handed: Boolean,
    ): Task[ConversationResult.Render] =
      refusedDeparture(conversation.userId).flatMap:
        case Some(refused) => ZIO.succeed(refused)
        case None => dispatchStep(authId, conversation, submission, ipAddress, handed)

    private def dispatchStep(
        authId: AuthId,
        conversation: ConversationRecord,
        submission: Submission,
        ipAddress: Option[String],
        handed: Boolean,
    ): Task[ConversationResult.Render] =
      (submission, conversation) match
        // An identity is given on the credential card, and only to a conversation that has none yet: one that already knows the
        // user (a step-up, a re-authentication) or is past the card (a code, a password, a registration step) is not asked.
        case (_: EmailSubmission | _: PhoneSubmission, _) if conversation.userId.isDefined || !asksForCredential(conversation) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (_: EmailSubmission, _) if !conversation.authFlow.primary.credentials.contains(PrimaryCredential.email) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (_: PhoneSubmission, _) if !conversation.authFlow.primary.credentials.contains(PrimaryCredential.phone) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("credential"))

        case (submitted: EmailSubmission, _) =>
          afterCredential(authId, conversation, Left(submitted.email), submitted.proof, handed).tap(observeCredential)

        case (submitted: PhoneSubmission, _) =>
          afterCredential(authId, conversation, Right(submitted.phone), submitted.proof, handed).tap(observeCredential)

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

        case (submitted: OtpResendSubmission, ConversationRecord.Otp(otp, credential)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, otp.factorIndex, submitted.proof)

        case (submitted: PasswordSubmission, ConversationRecord.Password(password)) =>
          conversationService.checkPassword(conversation, password, submitted.password, authId)
            .tap(observeDecision("password"))
            .flatMap:
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, password.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        // A login and password say who the user is, so they are accepted only where an identity is given (see `asksForCredential`).
        // A conversation that already carries an identity, or state of one, must not take another in and keep what the first one
        // passed: the factors recorded for the first user would be credited to the second. Like any submission that does not belong to
        // the step: refused, the conversation stays where it is.
        case (_: LoginPasswordSubmission, _) if conversation.userId.isDefined || !asksForCredential(conversation) =>
          (Observability.setError("step_mismatch") *>
            auditLoginPasswordRefused(authId, conversation) *>
            AuthMetrics.stepFailed(stepName(conversation.step)))
            .as(ConversationResult.BadRequest)

        case (submitted: LoginPasswordSubmission, _) =>
          sentOn(authId, conversation, handed, passkeyRequest = None)(
            held = userRepository.findByLogin(submitted.login).map(_.isDefined),
            home = directory.homeOfLogin(submitted.login),
          ).someOrElseZIO:
            // The user is resolved and refused BEFORE the credential is looked at: a right and a wrong password answer alike while
            // the user is leaving, and a password check is not charged to a user who is not served here (versolauth/versola#1051).
            // (An auth that is no cell moves nobody, so it has no departure to ask about.)
            ZIO.when(config.cellId.isDefined)(userRepository.findByLogin(submitted.login))
              .flatMap(found => refusedDeparture(found.flatten.map(_.id))).flatMap:
              case Some(refused) => ZIO.succeed(refused)
              case None =>
                conversationService.checkLoginPassword(authId, conversation, submitted.login, submitted.password)
                  .tap(observeDecision("credential"))
                  .flatMap:
                    case ConversationResult.StepPassed(updated) =>
                      refusedDeparture(updated.userId).flatMap:
                        case Some(refused) => ZIO.succeed(refused)
                        case None => afterFactor(authId, updated, nextFactorIndex = 0)
                    case other: ConversationResult.Render =>
                      ZIO.succeed(other)

        // A passkey submitted from the code page satisfies the factor the code was for, and the conversation goes on as it would
        // after the code: a passkey that proves the user is not a reason to skip what comes after.
        case (submitted: PasskeyAssertionSubmission, ConversationRecord.Otp(otp, _)) =>
          conversationService.checkPasskeyOnCode(authId, conversation, otp, submitted.response, ipAddress)
            .tap(observeDecision("passkey"))
            .flatMap:
              case ConversationResult.StepPassed(updated) =>
                afterFactor(authId, updated, otp.factorIndex + 1)
              case other: ConversationResult.Render =>
                ZIO.succeed(other)

        // The browser could not make a passkey, or the prompt was dismissed: the code page, as it was.
        case (_: PasskeyUnavailableSubmission, ConversationRecord.Otp(otp, _)) =>
          ZIO.succeed(ConversationResult.RenderStep(otp))

        case (submitted: PasskeyAssertionSubmission, _) =>
          passkeyElsewhere(authId, conversation, submitted.response, handed).someOrElseZIO:
            conversationService.finishPasskeyAssertion(authId, conversation, submitted.response, ipAddress)
              .tap(observePasskeyAssertion)
              .flatMap(unlessLeaving)

        case (_: PasskeyUnavailableSubmission, ConversationRecord.PasskeyOnly(_)) =>
          conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("passkey"))

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

        case (submitted: ConsentAllowSubmission, ConversationRecord.Consent(consentStep)) =>
          conversationService.allowConsent(authId, conversation, consentStep, submitted.scope)
            .tap(observeConsent)

        case (_: ConsentDenySubmission, ConversationRecord.Consent(consentStep)) =>
          // The user's own answer, not a failure of the request: the grant they were asked for is the subject.
          audit.log(
            AuditDraft(
              kind = AuthAuditKinds.ConsentDenied,
              outcome = AuditOutcome.denied,
              reason = None,
              actor = None,
              userId = conversation.userId,
              target = None,
              details = AuthAuditDetails.Consent(
                scope = consentStep.requestedScope.toList.map(_.toString).sorted,
                authorizationDetailTypes = Nil,
                expiresAt = None,
              ),
            ),
          ) *> conversationService.accessDenied(authId, conversation).zipLeft(AuthMetrics.stepFailed("consent"))

        case _ =>
          // Submission doesn't match the conversation's current step, e.g. a stale form
          // (browser back/forward, double submit) or a tampered request.
          (Observability.setError("step_mismatch") *>
            AuthMetrics.stepFailed(stepName(conversation.step)))
            .as(ConversationResult.BadRequest)

    /** `login.step` for a login and password that the conversation does not take. The credential is not looked at, so no subject is
      * named beyond the user the conversation already has.
      */
    private def auditLoginPasswordRefused(authId: AuthId, conversation: ConversationRecord): Task[Unit] =
      audit.log(
        AuditDraft(
          kind = AuthAuditKinds.LoginStep,
          outcome = AuditOutcome.denied,
          reason = Some("step_mismatch"),
          actor = None,
          userId = conversation.userId,
          target = None,
          details = AuthAuditDetails.LoginStep(
            step = "password",
            conversationId = authId.toString,
            kind = AuthMetrics.conversationKind(conversation),
            subjectHash = None,
            afDecision = None,
          ),
        ),
      )

    /** Whether the conversation is on the credential card and that card takes an identity: not the passkey-only card of a step-up,
      * which only confirms the user the conversation already has.
      */
    private def asksForCredential(conversation: ConversationRecord): Boolean =
      conversation.step match
        case card: ConversationStep.Credential => !card.passkeyOnly
        case _ => false

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
      runRegistrationStep(authId, conversation.copy(registrationStep = conversation.registrationStep.map(_ + 1)), ProofInput.NoScript)

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
        proof: ProofInput,
    ): Task[ConversationResult.Render] =
      conversation.currentRegistrationStep match
        case Some(_: RegistrationStep.Otp) =>
          conversation.credential match
            case Some(credential) =>
              conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = 0, proof)
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
        proof: ProofInput,
        handed: Boolean,
    ): Task[ConversationResult.Render] =
      userRepository.findByCredential(credential).flatMap:
        case Some(user) =>
          refusedDeparture(Some(user.id)).flatMap:
            case Some(refused) => ZIO.succeed(refused)
            case None => identifiedByCredential(authId, conversation, credential, proof, user)

        // Not a user of this cell. A cell asks the directory which cell the address goes to before it treats the address
        // as new, and sends the sign-in there (versolauth/versola#1046, versolauth/versola#1052).
        case None =>
          homeElsewhere(credential, handed).flatMap:
            case Left(unavailable) => ZIO.succeed(unavailable)
            case Right(Some(home)) =>
              handoff.seal(conversation, credential, home).foldZIO(
                {
                  // The home cell has no key to seal for: the same as a directory that does not answer, and the conversation stays.
                  case _: CellHandoff.NoKey =>
                    ZIO.logWarning(s"No key for cell '$home': the handoff is refused") *>
                      Observability.setError("directory_unavailable").as(ConversationResult.ServiceUnavailable)
                  case _: CellHandoff.TooLarge =>
                    Observability.setError("handoff_too_large").as(ConversationResult.ServiceUnavailable)
                  case failure => ZIO.fail(failure)
                },
                // Gone whatever the browser does next: the sign-in continues in the other cell or not at all.
                ticket => conversationRepository.delete(authId, conversation.version).as(ConversationResult.Handoff(CellHandoff.location(ticket))),
              )
            case Right(None) => afterUnknownCredential(authId, conversation, credential, proof)

    private def identifiedByCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        proof: ProofInput,
        user: UserRecord,
    ): Task[ConversationResult.Render] =
      val identified = conversation.withoutIdentityState.copy(
        userId = Some(user.id),
        credential = Some(credential),
        userEmail = user.email,
        userPhone = user.phone,
        userLogin = user.login,
        userClaims = Some(user.claims),
      )
      afterAuthenticationCredential(authId, identified, credential, proof)

    /** The cell `credential` goes to, when it is another than this one: the cell that holds the user, and for an address
      * nobody holds the cell central's keyed hash picks, so that the redirect does not say whether the address is
      * registered (versolauth/versola#1052). An `auth` that is no cell asks nothing: it is the only place a user can
      * be. A sign-in that was handed here is answered here whatever central would say now (the weights may have changed
      * since): the cell that sent it has done the choosing, and a second choice could send it back and forth. Central
      * unreachable is the step's "service unavailable": going on would send a code for an address this cell cannot say
      * is new. */
    private def homeElsewhere(
        credential: Either[Email, Phone],
        handed: Boolean,
    ): UIO[Either[ConversationResult.Render, Option[RoutingKey.CellId]]] =
      config.cellId match
        case Some(own) if !handed =>
          directory.homeOfAddress(credential).foldCauseZIO(
            cause =>
              ZIO.logWarningCause("Could not ask the directory which cell an address goes to; the step is refused", cause) *>
                Observability.setError("directory_unavailable").as(Left(ConversationResult.ServiceUnavailable)),
            found => ZIO.right(found.filterNot(_ == own)),
          )
        case _ => ZIO.right(None)

    /** Where a submission that names its user goes when this cell does not hold that user: the cell that does
      * (versolauth/versola#1048); `None` when it is answered here. It is answered here by an `auth` that is no cell, when
      * it was handed to this cell already, when this cell holds the user, and when central names this cell or none.
      * Central is asked only for what this cell does not hold, so its users sign in without it; when it is asked and
      * does not answer, the step is the "service unavailable" an address gets.
      *
      * @param held whether the user named is in this cell's database
      * @param home the cell central names for it
      */
    private def sentOn(authId: AuthId, conversation: ConversationRecord, handed: Boolean, passkeyRequest: Option[String])(
        held: Task[Boolean],
        home: Task[Option[RoutingKey.CellId]],
    ): Task[Option[ConversationResult.Render]] =
      config.cellId match
        case Some(own) if !handed && takesIdentity(conversation) =>
          held.flatMap:
            case true => ZIO.none
            case false =>
              home.foldCauseZIO(
                cause =>
                  ZIO.logWarningCause("Could not ask central which cell a sign-in belongs to; the step is refused", cause) *>
                    Observability.setError("directory_unavailable").as(Some(ConversationResult.ServiceUnavailable)),
                {
                  // The conversation is ended by the controller once the seal is made (`end`): a request too large to
                  // seal keeps its conversation.
                  case Some(cell) if cell != own => ZIO.some(ConversationResult.Elsewhere(cell, passkeyRequest))
                  case _ => ZIO.none
                },
              )
        case _ => ZIO.none

    /** A credential card that takes a login and password or a passkey from somebody not yet known: the only place a
      * submission names its user. Anything else is refused by the step itself, here. */
    private def takesIdentity(conversation: ConversationRecord): Boolean =
      conversation.userId.isEmpty && asksForCredential(conversation)

    /** The same for a passkey with no address typed: the assertion names its user (the `userHandle`), and a cell that
      * does not hold that user has no credential to verify it with. The request the assertion answers goes with it. An
      * assertion that names nobody, or that no request is pending for, is refused here as before. */
    private def passkeyElsewhere(
        authId: AuthId,
        conversation: ConversationRecord,
        response: String,
        handed: Boolean,
    ): Task[Option[ConversationResult.Render]] =
      val pending = conversation.step match
        case card: ConversationStep.Credential if conversation.authFlow.passkey.isDefined => card.passkeyRequest
        case _ => None
      pending match
        case None => ZIO.none
        case Some(request) =>
          webAuthnService.userFromResponse(response).flatMap:
            case None => ZIO.none
            case Some(userId) =>
              // Before the credential is looked at: a passkey of a user who is leaving is not used (and its counter not written).
              refusedDeparture(Some(userId)).flatMap:
                case Some(refused) => ZIO.some(refused)
                case None =>
                  sentOn(authId, conversation, handed, passkeyRequest = Some(request))(
                    held = userRepository.find(userId).map(_.isDefined),
                    home = directory.homeOfUser(userId),
                  )

    private def afterUnknownCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        proof: ProofInput,
    ): Task[ConversationResult.Render] =
      // A new user: the tenant decides whether this address may create an account. When it may
      // not, the ordinary sign-in path runs, indistinguishable from any other unknown credential.
      configService.registrationFor(conversation.clientId, credential).flatMap:
        case Some(flow) =>
          conversationService.startRegistration(authId, conversation, flow, credential).flatMap:
            case RegistrationEntry.Registering(updated) =>
              runRegistrationStep(authId, updated, proof)
            case ConversationResult.WriteConflict =>
              ZIO.succeed(ConversationResult.WriteConflict)
        case None =>
          afterAuthenticationCredential(authId, conversation.withoutIdentityState, credential, proof)

    private def afterAuthenticationCredential(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        proof: ProofInput,
    ): Task[ConversationResult.Render] =
      passkeyPending(conversation).flatMap:
        case true => conversationService.promptPasskey(authId, conversation)
        case false => afterAuthenticationCredentialFactors(authId, conversation, credential, proof)

    private def afterAuthenticationCredentialFactors(
        authId: AuthId,
        conversation: ConversationRecord,
        credential: Either[Email, Phone],
        proof: ProofInput,
    ): Task[ConversationResult.Render] =
      nextNeededFactor(conversation, conversation.authFlow.primary.factors.zipWithIndex).flatMap:
        case Some((AuthFactor(AuthFactorType.otp, _), idx)) =>
          conversationService.prepareInitialOtp(authId, conversation, credential, factorIndex = idx, proof)

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
      passkeyPending(conversation).flatMap:
        case true => conversationService.promptPasskey(authId, conversation)
        case false => afterFactorSteps(authId, conversation, nextFactorIndex)

    /** Whether the ACR this conversation pursues needs a passkey that has not been passed yet. A passkey is
      * not one of `primary.factors`, so nothing else would ask for it: the conversation goes straight to a
      * passkey-only step, ahead of any other factor, because the passkey (user-verifying) is the one that
      * proves the device and a one-time code must not stand in for it.
      */
    private def passkeyPending(conversation: ConversationRecord): Task[Boolean] =
      configService.getAcrVocabulary(conversation.clientId).map: vocabulary =>
        conversation.requiredFactors(vocabulary).exists(_.contains(PassedAuthFactor.passkey)) &&
          !conversation.amr.keySet.exists(_.satisfies(PassedAuthFactor.passkey, conversation.authFlow.equivalents))

    private def afterFactorSteps(
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
            case Some(cred) => conversationService.prepareInitialOtp(authId, conversation, cred, idx, ProofInput.NoScript)
            case _ => conversationService.accessDenied(authId, conversation)

        case Some((AuthFactor(AuthFactorType.password, _), idx)) =>
          conversationService.preparePasswordStep(authId, conversation, idx)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) if conversation.needsPasswordChange =>
          afterFactor(authId, conversation, nextFactorIndex + 1)

        case Some((AuthFactor(AuthFactorType.passkeyEnroll, _), _)) =>
          conversationService.offerPasskeyEnroll(authId, conversation)

        case None =>
          inlinePasswordPending(conversation).flatMap:
            case true =>
              conversationService.preparePasswordStep(authId, conversation, conversation.authFlow.primary.factors.size)
            case false if conversation.needsPasswordChange => conversationService.offerSetPassword(authId, conversation)
            case false => conversationService.finish(authId, conversation)

    /** Whether the ACR this conversation pursues needs a password that has not been passed yet, in a flow
      * that takes it on the credential card. No factor in `primary.factors` would ask for it again, and the
      * card is skipped for a user the conversation already knows, so without this the conversation would
      * finish with nothing having verified them. The password step goes after the listed factors.
      */
    private def inlinePasswordPending(conversation: ConversationRecord): Task[Boolean] =
      configService.getAcrVocabulary(conversation.clientId).map: vocabulary =>
        val flow = conversation.authFlow
        conversation.userId.isDefined &&
        flow.primary.inlinePassword &&
        !flow.primary.factors.exists(_.`type` == AuthFactorType.password) &&
        conversation.requiredFactors(vocabulary).exists(_.contains(PassedAuthFactor.password)) &&
        !conversation.amr.keySet.exists(_.satisfies(PassedAuthFactor.password, flow.equivalents))

    private def nextNeededFactor(
        conversation: ConversationRecord,
        factors: List[(AuthFactor, Int)],
    ): Task[Option[(AuthFactor, Int)]] =
      for
        vocabulary <- configService.getAcrVocabulary(conversation.clientId)
        reqFactors = conversation.requiredFactors(vocabulary).map(_.toSet).getOrElse(Set.empty)
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

    private def observeConsent(result: ConversationResult.Render): UIO[Unit] =
      result match
        case ConversationResult.Complete(_, _, _, _, _, _, _) => AuthMetrics.stepPassed("consent")
        case _ => AuthMetrics.stepFailed("consent")

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
        case _: ConversationStep.Consent => "consent"
        case ConversationStep.AccessDenied => "access_denied"
