package versola.oauth.model

import com.nimbusds.jose.jwk.JWKSet
import versola.oauth.client.model.{ClientId, ScopeToken}
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.{CoreConfig, JWT}
import zio.json.*
import zio.{IO, ZIO}

import java.time.Instant

case class AccessToken(
    @jsonField("sub") subject: String, // UserId for user tokens, ClientId for client_credentials
    @jsonField("client_id") clientId: ClientId,
    scope: Set[ScopeToken],
    @jsonField("requested_claims") requestedClaims: Option[RequestedClaims],
    @jsonField("ui_locales") uiLocales: Option[List[String]],
    @jsonField("exp") expiresAt: Instant,
    @jsonField("iat") issuedAt: Instant,
    @jsonField("nbf") notBefore: Option[Instant],
    @jsonField("aud") audience: Vector[ClientId],
    @jsonField("iss") issuer: Option[String],
    @jsonField("jti") jwtId: Option[String],
):
  /** Parse userId from subject if it's a valid UUID, otherwise None (for client_credentials tokens) */
  def userId: Option[UserId] = UserId.parse(subject).toOption

object AccessToken:

  given JsonDecoder[ClientId] = JsonDecoder[String].map(ClientId(_))

  given JsonDecoder[Set[ScopeToken]] = JsonDecoder[String].map: scopeString =>
    scopeString.split(" ").map(ScopeToken(_)).toSet

  given JsonDecoder[Instant] = JsonDecoder[Long].map(Instant.ofEpochSecond)

  private given audienceDecoder: JsonDecoder[Vector[ClientId]] =
    JsonDecoder[ClientId].map(Vector(_))
      .orElse(JsonDecoder[Vector[String]].map(_.map(ClientId(_))))

  given JsonDecoder[AccessToken] = DeriveJsonDecoder.gen[AccessToken]