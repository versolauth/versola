package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, Error, ResponseTypeEntry}
import versola.oauth.client.OAuthClientService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, State}
import zio.http.{Method, Request, URL}
import zio.prelude.NonEmptySet
import zio.{Chunk, IO, Task, ZIO, ZLayer}

trait AuthorizeRequestParser:
  def parse(
      request: Request,
  ): IO[Error, AuthorizeRequest]

object AuthorizeRequestParser:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(oauthClientService: OAuthClientService) extends AuthorizeRequestParser:

    def parse(
        request: Request,
    ): IO[Error, AuthorizeRequest] =
      for
        params <- extractRequestParams(request).orElseFail(Error.BadRequest)
        (redirectUri, redirectUriString) <- parseRedirectUri(params)

        clientId <- getParam(params, "client_id")
          .orElseFail(Error.BadRequest)
          .someOrFail(Error.BadRequest)
          .map(ClientId(_))

        _ <- oauthClientService.findCached(clientId)
          .someOrFail(Error.BadRequest)
          .filterOrFail(_.redirectUris.contains(redirectUriString))(Error.BadRequest)

        state <- getParam(params, "state")
          .mapBoth(
            _ => Error.MultipleValuesProvided(redirectUri, None, "state"),
            _.map(State(_)),
          )

        responseTypeEntries <- getParam(params, "response_type")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "response_type"))
          .someOrFail(Error.ResponseTypeMissing(redirectUri, state))
          .flatMap:
            case "code" =>
              ZIO.succeed(NonEmptySet(ResponseTypeEntry.Code))
            case "code id_token" =>
              ZIO.succeed(NonEmptySet(ResponseTypeEntry.Code, ResponseTypeEntry.IdToken))
            case other =>
              ZIO.fail(Error.UnsupportedResponseType(redirectUri, state, other))

        codeChallenge <- getParam(params, "code_challenge")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "code_challenge"))
          .someOrFail(Error.CodeChallengeMissing(redirectUri, state))
          .flatMap { string =>
            ZIO.fromEither(CodeChallenge.from(string))
              .orElseFail(Error.CodeChallengeInvalid(redirectUri, state, string))
          }

        codeChallengeMethod <- getParam(params, "code_challenge_method")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "code_challenge_method"))
          .flatMap {
            case Some("S256") => ZIO.succeed(CodeChallengeMethod.S256)
            case Some("plain") | None => ZIO.succeed(CodeChallengeMethod.Plain)
            case Some(other) => ZIO.fail(Error.CodeChallengeMethodInvalid(redirectUri, state, other))
          }

        // TODO implement default scope
        scope <- getParam(params, "scope")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "scope"))
          .someOrFail(Error.ScopeMissing(redirectUri, state))
          .map(_.split(',').toSet.map(ScopeToken(_)))

        authorizeRequest = AuthorizeRequest(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          state = state,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          responseType = responseTypeEntries,
        )
      yield authorizeRequest

    private def parseRedirectUri(params: Map[String, Chunk[String]]): IO[Error, (URL, String)] =
      getParam(params, "redirect_uri")
        .orElseFail(Error.BadRequest)
        .someOrFail(Error.BadRequest)
        .flatMap { uri =>
          ZIO.fromEither(URL.decode(uri))
            .orElseFail(Error.BadRequest)
            .filterOrFail(uri => uri.isAbsolute && uri.fragment.isEmpty)(Error.BadRequest)
            .map(_ -> uri)
        }

    private def extractRequestParams(request: Request): Task[Map[String, Chunk[String]]] =
      request.method match
        case Method.GET =>
          ZIO.succeed(request.url.queryParams.map)
        case Method.POST | _ =>
          request.body.asURLEncodedForm
            .map(_.formData.flatMap(fd => fd.stringValue.map(v => fd.name -> Chunk(v))).toMap)

    private def getParam(params: Map[String, Chunk[String]], key: String): IO[Unit, Option[String]] =
      ZIO.succeed(params.get(key))
        .flatMap {
          case Some(Chunk(one)) =>
            ZIO.some(one)
          case Some(chunk) =>
            ZIO.fail(())
          case None =>
            ZIO.none
        }
