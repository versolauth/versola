package versola.oauth.consent

import versola.oauth.authorize.model.Prompt
import versola.oauth.client.model.{OAuthClientRecord, ScopeToken}
import versola.oauth.consent.model.ConsentRecord
import versola.user.model.UserId
import zio.{Clock, Task, ZIO, ZLayer}

/** Whether the user still has to be asked before a code may be issued. */
sealed trait ConsentDecision

object ConsentDecision:
  /** The consent screen must be shown for `requestedScope`. */
  case class Required(requestedScope: Set[ScopeToken]) extends ConsentDecision

  /** No prompt needed; `grantedScope` is what the issued code carries. */
  case class Satisfied(grantedScope: Set[ScopeToken]) extends ConsentDecision

trait ConsentService:
  /** Decides whether the consent screen is needed before a code is issued.
    *
    * A client with no `consentFlow` configured never prompts (e.g. trusted first-party clients),
      * matching the pre-consent behavior. Otherwise, `prompt=consent` always re-prompts (OIDC Core
      * §3.1.2.1). The stored grant is reused when it has not expired and still covers everything
      * now requested, so a widened scope re-prompts (incremental consent).
    */
  def decide(
      userId: UserId,
      client: OAuthClientRecord,
      requestedScope: Set[ScopeToken],
      prompt: Set[Prompt],
  ): Task[ConsentDecision]

  /** Validates what the consent screen submitted against what was requested, returning the
    * scope to grant or a reason the submission is not acceptable.
    */
  def validateSubmission(
      client: OAuthClientRecord,
      requestedScope: Set[ScopeToken],
      submittedScope: Set[ScopeToken],
  ): Either[String, Set[ScopeToken]]

  /** Persists the grant, expiring it after the client's `rememberDuration` when one is set. */
  def grant(userId: UserId, client: OAuthClientRecord, scope: Set[ScopeToken]): Task[Unit]

  def revoke(userId: UserId, client: OAuthClientRecord): Task[Unit]

object ConsentService:
  /** Scopes the user may never deselect: dropping `openid` would silently turn an OIDC request
    * into a plain OAuth one, and dropping `offline_access` would strip the refresh token the
    * client asked for. Both must be granted as requested or the whole request denied.
    */
  val NonDeselectable: Set[ScopeToken] = Set(ScopeToken.OpenId, ScopeToken.OfflineAccess)

  def live = ZLayer.fromFunction(Impl(_))

  class Impl(consentRepository: ConsentRepository) extends ConsentService:

    override def decide(
        userId: UserId,
        client: OAuthClientRecord,
        requestedScope: Set[ScopeToken],
        prompt: Set[Prompt],
    ): Task[ConsentDecision] =
      client.consentFlow match
        case None =>
          // No consent flow configured for this client: it never prompts, as before consent existed.
          ZIO.succeed(ConsentDecision.Satisfied(requestedScope))
        case Some(_) if prompt.contains(Prompt.consent) =>
          ZIO.succeed(ConsentDecision.Required(requestedScope))
        case Some(_) =>
          for
            existing <- consentRepository.find(userId, client.id)
            now <- Clock.instant
          yield existing match
            // A stored grant may be wider than this request; only what was requested is carried
            // into the code, so a narrow request never silently receives more.
            case Some(record) if record.covers(requestedScope, now) =>
              ConsentDecision.Satisfied(requestedScope)
            case _ =>
              ConsentDecision.Required(requestedScope)

    override def validateSubmission(
        client: OAuthClientRecord,
        requestedScope: Set[ScopeToken],
        submittedScope: Set[ScopeToken],
    ): Either[String, Set[ScopeToken]] =
      val missingMandatory = (requestedScope & NonDeselectable) -- submittedScope
      if !submittedScope.subsetOf(requestedScope) then
        Left(s"Granted scope exceeds the requested scope - ${(submittedScope -- requestedScope).mkString(" ")}")
      else if missingMandatory.nonEmpty then
        Left(s"Scope cannot be deselected - ${missingMandatory.mkString(" ")}")
      else if !client.consentFlow.exists(_.allowPartial) && submittedScope != requestedScope then
        Left("Client does not allow granting a subset of the requested scope")
      else
        Right(submittedScope)

    override def grant(userId: UserId, client: OAuthClientRecord, scope: Set[ScopeToken]): Task[Unit] =
      Clock.instant.flatMap: now =>
        consentRepository.upsert(
          ConsentRecord(
            userId = userId,
            clientId = client.id,
            scope = scope,
            grantedAt = now,
            expiresAt = client.consentFlow.flatMap(_.rememberDuration).map(d => now.plusSeconds(d.toSeconds)),
          ),
        )

    override def revoke(userId: UserId, client: OAuthClientRecord): Task[Unit] =
      consentRepository.delete(userId, client.id)
