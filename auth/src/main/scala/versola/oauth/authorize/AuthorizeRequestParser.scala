package versola.oauth.authorize

import versola.oauth.OauthClientService
import versola.oauth.authorize.model.{AuthorizeRequest, Error, RenderableError, ResponseTypeEntry}
import versola.oauth.model.{ClientId, CodeChallenge, CodeChallengeMethod, ScopeToken, State}
import zio.http.URL
import zio.prelude.NonEmptySet
import zio.{Chunk, IO, ZIO, ZLayer}

trait AuthorizeRequestParser:
  def parse(
      params: Map[String, Chunk[String]],
  ): IO[RenderableError, AuthorizeRequest]

object AuthorizeRequestParser:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(oauthClientService: OauthClientService) extends AuthorizeRequestParser:

    def parse(
        params: Map[String, Chunk[String]],
    ): IO[RenderableError, AuthorizeRequest] =
      for
        (redirectUri, redirectUriString) <- parseRedirectUri(params)

        clientId <- getParam(params, "client_id")
          .someOrFail(Error.ClientIdMissing)
          .mapBoth(RenderableError(_, redirectUri = None, state = None), ClientId(_))

        client <- oauthClientService.find(clientId)
          .someOrFail(RenderableError(Error.UknownClientId(clientId), redirectUri = None, state = None))

        _ <- ZIO.fail(
          RenderableError(
            error = Error.RedirectUriIsNotRegistered(redirectUriString),
            redirectUri = None,
            state = None,
          ),
        ).unless(client.redirectUris.contains(redirectUriString))

        state <- getParam(params, "state")
          .mapBoth(
            RenderableError(_, redirectUri = Some(redirectUri), state = None),
            _.map(State(_)),
          )

        responseTypeEntries <- getParam(params, "response_type")
          .someOrFail(Error.ResponseTypeMissing)
          .flatMap:
            case "code" =>
              ZIO.succeed(NonEmptySet(ResponseTypeEntry.Code))
            case "code id_token" =>
              ZIO.succeed(NonEmptySet(ResponseTypeEntry.Code, ResponseTypeEntry.IdToken))
            case other =>
              ZIO.fail(Error.UnsupportedResponseType(other))
          .mapError(RenderableError(_, redirectUri = Some(redirectUri), state = state))

        codeChallenge <- getParam(params, "code_challenge")
          .someOrFail(Error.CodeChallengeMissing)
          .flatMap { string =>
            ZIO.fromEither(CodeChallenge.from(string))
              .orElseFail(Error.CodeChallengeInvalid(string))
          }
          .mapError(RenderableError(_, redirectUri = Some(redirectUri), state = state))

        codeChallengeMethod <- getParam(params, "code_challenge_method")
          .flatMap {
            case Some("S256") => ZIO.succeed(CodeChallengeMethod.S256)
            case Some("plain") | None => ZIO.succeed(CodeChallengeMethod.Plain)
            case Some(other) => ZIO.fail(Error.CodeChallengeMethodInvalid(other))
          }
          .mapError(RenderableError(_, redirectUri = Some(redirectUri), state = state))

        // TODO implement default scope
        scope <- getParam(params, "scope")
          .someOrFail(Error.ScopeMissing)
          .mapBoth(
            RenderableError(_, redirectUri = Some(redirectUri), state = state),
            _.split(',').toSet.map(ScopeToken(_)),
          )
      yield AuthorizeRequest(
        clientId = clientId,
        redirectUri = redirectUri,
        scope = scope,
        state = state,
        codeChallenge = codeChallenge,
        codeChallengeMethod = codeChallengeMethod,
        responseType = responseTypeEntries,
      )

    private def parseRedirectUri(params: Map[String, Chunk[String]]): IO[RenderableError, (URL, String)] =
      getParam(params, "redirect_uri")
        .orElseFail(Error.RedirectUriMissingOrInvalid)
        .someOrFail(Error.RedirectUriMissingOrInvalid)
        .flatMap { uri =>
          ZIO.fromEither(URL.decode(uri))
            .orElseFail(Error.RedirectUriMissingOrInvalid)
            .filterOrFail(uri => uri.isAbsolute && uri.fragment.isEmpty)(Error.RedirectUriMissingOrInvalid)
            .map(_ -> uri)
        }
        .mapError(RenderableError(_, redirectUri = None, state = None))

    private def getParam(params: Map[String, Chunk[String]], key: String): IO[Error, Option[String]] =
      ZIO.succeed(params.get(key))
        .flatMap {
          case Some(Chunk(one)) =>
            ZIO.some(one)
          case Some(chunk) =>
            ZIO.fail(Error.MultipleValuesProvided(key))
          case None =>
            ZIO.none
        }
