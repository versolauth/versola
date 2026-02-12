package versola.edge

import versola.edge.model.{EdgeSession, EdgeSessionId}
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.util.{MAC, SecureRandom, SecurityService}
import zio.{Duration, Task, ZIO, ZLayer}
import zio.durationInt
import zio.http.*
import zio.json.*

import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

trait EdgeSessionService:
  def createSession(state: Option[String]): Task[MAC.Of[EdgeSessionId]]

  def createSessionWithTokens(
      state: Option[String],
      code: String,
      clientId: ClientId,
  ): Task[MAC.Of[EdgeSessionId]]

  def getSession(sessionId: MAC.Of[EdgeSessionId]): Task[Option[EdgeSession]]

  def refreshSession(
      sessionId: MAC.Of[EdgeSessionId],
      refreshToken: String,
  ): Task[EdgeSession]

  def deleteSession(sessionId: MAC.Of[EdgeSessionId]): Task[Unit]

  def deleteSessionsByClientId(clientId: ClientId): Task[Unit]

  def cleanupExpiredSessions(): Task[Unit]

object EdgeSessionService:
  def live: ZLayer[
    EdgeSessionRepository & EdgeCredentialsService & SecurityService & SecureRandom & Client,
    Nothing,
    EdgeSessionService,
  ] =
    ZLayer.fromFunction(Impl(_, _, _, _, _))

  class Impl(
      repository: EdgeSessionRepository,
      credentialsService: EdgeCredentialsService,
      securityService: SecurityService,
      secureRandom: SecureRandom,
      httpClient: Client,
  ) extends EdgeSessionService:

    private val sessionTtl = 24.hours // 24 hour session TTL

    // Encryption key derived from a fixed secret (in production, this should come from config)
    private val encryptionKey: SecretKey =
      val keyBytes = Array.fill(32)(0.toByte) // TODO: Load from config
      new SecretKeySpec(keyBytes, "AES")

    override def createSession(state: Option[String]): Task[MAC.Of[EdgeSessionId]] =
      ZIO.fail(new UnsupportedOperationException(
        "Use createSessionWithTokens instead - sessions must include OAuth tokens"
      ))

    override def createSessionWithTokens(
        state: Option[String],
        code: String,
        clientId: ClientId,
    ): Task[MAC.Of[EdgeSessionId]] =
      for
        now <- zio.Clock.instant
        sessionIdBytes <- secureRandom.nextBytes(32)
        sessionId = MAC(sessionIdBytes)

        // Get client credentials
        credentials <- credentialsService.getCredentials(clientId)
          .someOrFail(new RuntimeException(s"Client credentials not found for $clientId"))

        // Exchange code for tokens
        tokenResponse <- exchangeCodeWithProvider(code, credentials)

        // Encrypt tokens before storing
        accessTokenEncrypted <- securityService.encryptAes256(
          tokenResponse.accessToken.getBytes("UTF-8"),
          encryptionKey,
        ).map(bytes => java.util.Base64.getEncoder.encodeToString(bytes))

        refreshTokenEncrypted <- ZIO.foreach(tokenResponse.refreshToken)(rt =>
          securityService.encryptAes256(
            rt.getBytes("UTF-8"),
            encryptionKey,
          ).map(bytes => java.util.Base64.getEncoder.encodeToString(bytes))
        )

        tokenExpiresAt = tokenResponse.expiresIn
          .map(seconds => now.plusSeconds(seconds.toLong))
          .getOrElse(now.plusSeconds(3600)) // Default 1 hour

        scopes = tokenResponse.scope
          .map(_.split(" ").toSet.map(ScopeToken(_)))
          .getOrElse(credentials.scopes)

        session = EdgeSession(
          clientId = clientId,
          state = state,
          accessTokenEncrypted = accessTokenEncrypted,
          refreshTokenEncrypted = refreshTokenEncrypted,
          tokenExpiresAt = tokenExpiresAt,
          scope = scopes,
          createdAt = now,
          sessionExpiresAt = now.plusSeconds(sessionTtl.toSeconds),
        )

        _ <- repository.create(sessionId, session, sessionTtl)
      yield sessionId

    override def getSession(sessionId: MAC.Of[EdgeSessionId]): Task[Option[EdgeSession]] =
      repository.find(sessionId)

    override def refreshSession(
        sessionId: MAC.Of[EdgeSessionId],
        refreshToken: String,
    ): Task[EdgeSession] =
      // TODO: Implement token refresh logic
      ZIO.fail(new UnsupportedOperationException("Token refresh not yet implemented"))

    override def deleteSession(sessionId: MAC.Of[EdgeSessionId]): Task[Unit] =
      repository.delete(sessionId)

    override def deleteSessionsByClientId(clientId: ClientId): Task[Unit] =
      repository.deleteByClientId(clientId)

    override def cleanupExpiredSessions(): Task[Unit] =
      // This would typically be implemented with a database query to delete expired sessions
      // For now, we rely on the repository's find method to filter expired sessions
      ZIO.unit

    private def exchangeCodeWithProvider(
        code: String,
        credentials: versola.edge.model.EdgeCredentials,
    ): Task[OAuthTokenResponse] =
      val tokenUrl = s"${credentials.providerUrl}/v1/token"
      val requestBody = Form(
        FormField.simpleField("grant_type", "authorization_code"),
        FormField.simpleField("code", code),
        FormField.simpleField("client_id", credentials.clientId),
        FormField.simpleField("client_secret", credentials.clientSecret.toString),
      )

      ZIO.scoped {
        for
          response <- httpClient.request(
            Request
              .post(tokenUrl, Body.fromURLEncodedForm(requestBody))
              .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
          )
          body <- response.body.asString
          tokenResponse <- ZIO.fromEither(body.fromJson[OAuthTokenResponse])
            .mapError(err => new RuntimeException(s"Failed to parse token response: $err"))
        yield tokenResponse
      }

  // OAuth token response model
  case class OAuthTokenResponse(
      @jsonField("access_token") accessToken: String,
      @jsonField("token_type") tokenType: String,
      scope: Option[String],
      @jsonField("expires_in") expiresIn: Option[Int],
      @jsonField("refresh_token") refreshToken: Option[String],
  ) derives JsonCodec

