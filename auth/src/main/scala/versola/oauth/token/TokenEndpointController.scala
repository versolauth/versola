package versola.oauth.token

import versola.http.Controller
import versola.oauth
import versola.oauth.OAuthClientService
import versola.oauth.model.{AuthorizationCode, ClientId, ClientSecret, CodeVerifier, GrantType}
import versola.oauth.token.model.{TokenEndpointError, TokenErrorResponse, TokenRequest, TokenResponse}
import zio.*
import zio.http.*
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing

object TokenEndpointController extends Controller:
  type Env = Tracing & OAuthTokenService & OAuthClientService

  def routes: Routes[Env, Nothing] = Routes(
    tokenEndpoint,
  )

  val tokenEndpoint =
    Method.POST / "api" / "v1" / "token" -> handler { (request: Request) =>
      (for
        params <- parseRequest(request)
        service <- ZIO.service[OAuthTokenService]
        response <- ZIO.succeed(
          Response.json(???)
            .addHeader(Header.CacheControl.NoStore),
        )
      yield response)
        .catchAll {
          case error: TokenEndpointError =>
            ZIO.succeed:
              val errorResponse = TokenErrorResponse.from(error)
              Response
                .json(errorResponse.toJson)
                .status(Status.BadRequest)
                .addHeader(Header.CacheControl.NoStore)

          case ex: Throwable =>
            ZIO.succeed(Response.internalServerError)
        }
    }

  private def parseRequest(request: Request): Task[Map[String, String]] =
    for
      params <- request.body.asURLEncodedForm
        .map(_.formData.flatMap(fd => fd.stringValue.map(v => fd.name -> v)).toMap)
    yield ???

  private def authenticateClient(
      request: Request,
  ): ZIO[OAuthClientService, TokenEndpointError.Unauthorized, (ClientId, Option[ClientSecret])] =
    for
      (clientId, clientSecret) <- request.header(Header.Authorization) match
        case Some(Header.Authorization.Basic(username, password)) =>
          val secret = password.stringValue
          ZIO.succeed((ClientId(username), Option.when(secret.nonEmpty)(ClientSecret(secret))))
        case _ =>
          ZIO.fail(TokenEndpointError.CredentialsNotProvided)
    yield (clientId, None)
      
