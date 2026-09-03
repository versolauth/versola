package versola.oauth.model

import com.nimbusds.jose.jwk.JWKSet
import versola.oauth.client.model.{AuthorizationDetail, ClientId, ResourceUri, ScopeToken}
import versola.oauth.session.model.PublicSessionId
import versola.oauth.userinfo.model.RequestedClaims
import versola.user.model.UserId
import versola.util.{CoreConfig, JWT}
import zio.json.*
import zio.{IO, ZIO}

import java.time.Instant

case class AccessTokenPayload(
    @jsonField("sub") subject: String, // UserId for user tokens, ClientId for client_credentials
    @jsonField("client_id") clientId: ClientId,
    scope: Set[ScopeToken],
    @jsonField("requested_claims") requestedClaims: Option[RequestedClaims],
    @jsonField("exp") expiresAt: Instant,
    @jsonField("iat") issuedAt: Instant,
    @jsonField("nbf") notBefore: Option[Instant],
    @jsonField("aud") audience: Vector[ResourceUri],
    @jsonField("iss") issuer: String,
    @jsonField("jti") id: AccessToken,
    /** RFC 9396 authorization details granted for this token, absent when the grant has none. */
    @jsonField("authorization_details") authorizationDetails: Option[List[AuthorizationDetail]],
    /** The session this token was issued under, absent for grants that have none
      * (client_credentials). Same value as the id token's `sid`. */
    @jsonField("sid") sessionId: Option[PublicSessionId],
):
  /** Parse userId from subject if it's a valid UUID, otherwise None (for client_credentials tokens) */
  def userId: Option[UserId] = UserId.parse(subject).toOption

object AccessTokenPayload:

  given JsonDecoder[ClientId] = JsonDecoder[String].map(ClientId(_))

  given JsonDecoder[Set[ScopeToken]] = JsonDecoder[String].map: scopeString =>
    scopeString.split(" ").map(ScopeToken(_)).toSet

  given JsonDecoder[Instant] = JsonDecoder[Long].map(Instant.ofEpochSecond)

  private given audienceDecoder: JsonDecoder[Vector[ResourceUri]] =
    JsonDecoder[ResourceUri].map(Vector(_))
      .orElse(JsonDecoder[List[ResourceUri]].map(_.toVector))

  given JsonDecoder[AccessTokenPayload] = DeriveJsonDecoder.gen[AccessTokenPayload]
