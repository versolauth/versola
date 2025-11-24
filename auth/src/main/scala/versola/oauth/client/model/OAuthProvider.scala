package versola.oauth.client.model

import zio.json.*
import zio.schema.*

enum OauthProviderName derives Schema:
  case google, github

enum OAuthProvider(
    val name: String,
    val authUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
):
  case Google extends OAuthProvider(
      "google",
      "https://accounts.google.com/o/oauth2/v2/auth",
      "https://oauth2.googleapis.com/token",
      "https://www.googleapis.com/oauth2/v2/userinfo",
    )
  case GitHub extends OAuthProvider(
      "github",
      "https://github.com/login/oauth/authorize",
      "https://github.com/login/oauth/access_token",
      "https://api.github.com/user",
    )

object OAuthProvider:
  given Schema[OAuthProvider] = Schema.derived[OAuthProvider]

case class OAuthTokenRequest(
    clientId: String,
    clientSecret: String,
    code: String,
    redirectUri: String,
) derives Schema

case class OAuthTokenResponse(
    @jsonField("access_token") accessToken: String,
    @jsonField("token_type") tokenType: String,
    scope: Option[String],
    @jsonField("expires_in") expiresIn: Option[Int],
    @jsonField("refresh_token") refreshToken: Option[String],
) derives Schema, JsonCodec

case class OAuthUserInfo(
    id: String,
    email: Option[String],
    name: Option[String],
    picture: Option[String],
    provider: OAuthProvider,
) derives Schema

// Google-specific user info response
case class GoogleUserInfo(
    id: String,
    email: Option[String],
    verified_email: Option[Boolean],
    name: Option[String],
    given_name: Option[String],
    family_name: Option[String],
    picture: Option[String],
    locale: Option[String],
) derives Schema, JsonCodec

// GitHub-specific user info response
case class GitHubUserInfo(
    id: Long,
    login: String,
    name: Option[String],
    email: Option[String],
    avatar_url: Option[String],
    location: Option[String],
    company: Option[String],
) derives Schema, JsonCodec
