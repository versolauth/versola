package versola.account

import versola.auth.model.{CredentialId, PasskeyRecord}
import versola.oauth.challenge.passkey.{PasskeyCeremony, PasskeyRepository, WebAuthnService}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.ClientId
import versola.oauth.session.SessionRepository
import versola.oauth.session.model.SessionRecord
import versola.user.UserRepository
import versola.user.model.UserId
import versola.util.http.Unauthorized
import zio.*

import java.util.UUID

case class AuthSettingsPageData(
    sessions: List[(UUID, SessionRecord)],
    passkeys: Vector[PasskeyRecord],
    passkeyResult: Option[PasskeyCeremony],
    style: String,
    jsCompiled: Option[String],
    css: String,
    locale: String,
    locales: List[String],
    translations: Map[String, String],
    allTranslations: Map[String, Map[String, String]],
    pageTitle: String,
)

trait AuthSettingsService:
  def getPageData(userId: UserId, clientId: ClientId): Task[Option[AuthSettingsPageData]]
  def invalidateSession(publicSessionId: UUID): Task[Unit]
  def deletePasskey(credentialId: CredentialId, userId: UserId): Task[Unit]
  def finishPasskeyRegistration(
      userId: UserId,
      clientId: ClientId,
      ceremonyRequest: String,
      response: String,
      name: Option[String],
  ): Task[Unit]

object AuthSettingsService:

  type Env =
    SessionRepository &
      PasskeyRepository &
      WebAuthnService &
      OAuthConfigurationService &
      UserRepository

  val live: ZLayer[Env, Nothing, AuthSettingsService] =
    ZLayer:
      for
        sessionRepo <- ZIO.service[SessionRepository]
        passkeyRepo <- ZIO.service[PasskeyRepository]
        webAuthn    <- ZIO.service[WebAuthnService]
        oauthConfig <- ZIO.service[OAuthConfigurationService]
        userRepo    <- ZIO.service[UserRepository]
      yield Impl(sessionRepo, passkeyRepo, webAuthn, oauthConfig, userRepo)

  private[account] class Impl(
      sessionRepo: SessionRepository,
      passkeyRepo: PasskeyRepository,
      webAuthn: WebAuthnService,
      oauthConfig: OAuthConfigurationService,
      userRepo: UserRepository,
  ) extends AuthSettingsService:

    override def getPageData(userId: UserId, clientId: ClientId): Task[Option[AuthSettingsPageData]] =
      for
        sessions <- sessionRepo.findByUserIdWithId(userId)
        passkeys <- passkeyRepo.listByUser(userId)
        formOpt  <- oauthConfig.getForm("auth-settings")
        client   <- oauthConfig.find(clientId)
        themeId   = client.map(_.theme).getOrElse("default")
        css      <- oauthConfig.getTheme(themeId).flatMap:
          case Some(t) if t.css.nonEmpty => ZIO.succeed(t.css)
          case _                         => oauthConfig.getTheme("default").map(_.map(_.css).getOrElse(""))
        locales  <- oauthConfig.getLocales
        result   <- formOpt match
          case None       => ZIO.none
          case Some(form) =>
            for
              passkeySettings <- oauthConfig.getPasskeySettings(clientId)
              displayName     <- userRepo.find(userId).map: userOpt =>
                userOpt
                  .flatMap(u => u.login.map(_.toString).orElse(u.email.map(_.toString)).orElse(u.phone.map(_.toString)))
                  .getOrElse(userId.toString)
              passkeyResult <- passkeySettings match
                case Some(settings) =>
                  webAuthn.startRegistration(settings, userId, displayName)
                    .map(Some(_))
                    .catchAll(e => ZIO.logWarning(s"startRegistration failed: ${e.getMessage}") *> ZIO.none)
                case None => ZIO.none

              activeCodes     = (locales.locales.map(_.code).toSet + locales.default) & form.localizations.keySet
              allLocalesList  = activeCodes.toList.sorted
              effectiveLocale =
                if form.localizations.contains(locales.default) then locales.default
                else allLocalesList.headOption.getOrElse(locales.default)
              translations    = form.localizations.getOrElse(effectiveLocale, Map.empty)
              pageTitle       = translations.getOrElse("page_title", "Account Settings")
            yield Some(AuthSettingsPageData(
              sessions        = sessions,
              passkeys        = passkeys,
              passkeyResult   = passkeyResult,
              style           = form.style,
              jsCompiled      = form.jsCompiled,
              css             = css,
              locale          = effectiveLocale,
              locales         = allLocalesList,
              translations    = translations,
              allTranslations = form.localizations,
              pageTitle       = pageTitle,
            ))
      yield result

    override def invalidateSession(publicSessionId: UUID): Task[Unit] =
      sessionRepo.invalidate(publicSessionId)

    override def deletePasskey(credentialId: CredentialId, userId: UserId): Task[Unit] =
      passkeyRepo.deleteByUser(credentialId, userId)

    override def finishPasskeyRegistration(
        userId: UserId,
        clientId: ClientId,
        ceremonyRequest: String,
        response: String,
        name: Option[String],
    ): Task[Unit] =
      for
        passkeySettings <- oauthConfig.getPasskeySettings(clientId)
        settings        <- ZIO.fromOption(passkeySettings).orElseFail(Unauthorized)
        _               <- webAuthn.finishRegistration(settings, userId, ceremonyRequest, response, name)
      yield ()
