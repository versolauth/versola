package versola.auth.model

import versola.user.model.UserResponse
import zio.schema.*

/* Изменение этой модели приведет к изменению ответа эндпоинта */
case class IssuedTokens(
    accessToken: AccessToken,
    refreshToken: RefreshToken,
    deviceId: Option[DeviceId],
    user: Option[UserResponse]
) derives Schema

case class StartPasskeyRequest(
    username: Option[String] = None,
    displayName: Option[String] = None,
) derives Schema

case class StartPasskeyResponse(
    options: String,
    challenge: String,
    flow: String, // "registration" or "authentication"
) derives Schema

case class FinishPasskeyRequest(
    credential: String,
    challenge: String,
) derives Schema
