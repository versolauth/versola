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
    @jsonField("sub") userId: UserId,
    @jsonField("client_id") clientId: ClientId,
    scope: Set[ScopeToken],
    @jsonField("requested_claims") requestedClaims: Option[RequestedClaims],
    @jsonField("ui_locales") uiLocales: Option[Vector[String]],
    @jsonField("exp") expiresAt: Instant,
    @jsonField("iat") issuedAt: Instant,
    @jsonField("nbf") notBefore: Option[Instant],
    @jsonField("aud") audience: Vector[ClientId],
    @jsonField("iss") issuer: Option[String],
    @jsonField("jti") jwtId: Option[String],
)

object AccessToken:

  given JsonDecoder[UserId] = JsonDecoder[String].mapOrFail: s =>
    UserId.parse(s).left.map(_.getMessage)

  given JsonDecoder[ClientId] = JsonDecoder[String].map(ClientId(_))

  given JsonDecoder[Set[ScopeToken]] = JsonDecoder[String].map: scopeString =>
    scopeString.split(" ").map(ScopeToken(_)).toSet

  given JsonDecoder[Instant] = JsonDecoder[Long].map(Instant.ofEpochSecond)

  private given audienceDecoder: JsonDecoder[Vector[ClientId]] =
    JsonDecoder[ClientId].map(Vector(_))
      .orElse(JsonDecoder[Vector[String]].map(_.map(ClientId(_))))

  given JsonDecoder[AccessToken] = DeriveJsonDecoder.gen[AccessToken]