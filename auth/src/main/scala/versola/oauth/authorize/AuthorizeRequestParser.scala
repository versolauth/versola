package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, Error, Prompt, ResponseTypeEntry}
import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{ClientId, OAuthClientRecord, PrimaryCredential, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, State}
import versola.oauth.model.SessionCookie
import versola.oauth.session.model.SessionId
import versola.oauth.userinfo.model.RequestedClaims
import versola.util.{Base64, Email, Phone}
import zio.http.{Header, Method, Request, URL}
import zio.json.*
import zio.prelude.NonEmptySet
import zio.{Chunk, IO, Task, ZIO, ZLayer}

trait AuthorizeRequestParser:
  def parse(
      request: Request,
  ): IO[Error, AuthorizeRequest]

object AuthorizeRequestParser:
  def live = ZLayer.fromFunction(Impl(_))

  class Impl(oauthClientService: OAuthConfigurationService) extends AuthorizeRequestParser:

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

        client <- oauthClientService.find(clientId)
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

        scope <- getParam(params, "scope")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "scope"))
          .someOrFail(Error.ScopeMissing(redirectUri, state))
          .map(_.split(' ').toSet.filter(_.nonEmpty).map(ScopeToken(_)))

        uiLocales <- getParam(params, "ui_locales")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "ui_locales"))
          .map(_.map(_.split(' ').toList))

        requestedClaims <- getParam(params, "claims")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "claims"))
          .flatMap {
            case Some(claimsJson) =>
              ZIO.fromEither(claimsJson.fromJson[RequestedClaims])
                .orElseFail(Error.InvalidClaims(redirectUri, state))
                .map(Some(_))
            case None =>
              ZIO.none
          }

        nonce <- getParam(params, "nonce")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "nonce"))
          .map(_.map(Nonce(_)))

        prompt <- getParam(params, "prompt")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "prompt"))
          .flatMap {
            case None => ZIO.succeed(Set.empty[Prompt])
            case Some(raw) =>
              val prompts = raw.split(' ').iterator.filter(_.nonEmpty).flatMap(Prompt.fromString).toSet
              ZIO.cond(
                !(prompts.contains(Prompt.none) && prompts.size > 1),
                prompts,
                Error.PromptInvalid(redirectUri, state),
              )
          }

        maxAge <- getParam(params, "max_age")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "max_age"))
          .map(_.flatMap(_.toLongOption))

        acrValues <- getParam(params, "acr_values")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "acr_values"))
          .map(_.map(_.split(' ').toList))

        userAgent =
          request.header(Header.UserAgent)
            .map(_.renderedValue)

        sessionId =
          request.cookie(SessionCookie.name)
            .flatMap(c => scala.util.Try(SessionId(Base64.urlDecode(c.content))).toOption)

        loginHint <- getParam(params, "login_hint")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "login_hint"))
          .flatMap {
            case None                                  => ZIO.none
            case Some(value) if value.startsWith("+") && value.drop(1).forall(_.isDigit) => parsePhoneLoginHint(value, client, redirectUri, state)
            case Some(value)                           => parseEmailLoginHint(value, client, redirectUri, state)
          }

        idTokenHint <- getParam(params, "id_token_hint")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "id_token_hint"))

        authorizeRequest = AuthorizeRequest(
          clientId = clientId,
          redirectUri = redirectUri,
          scope = scope,
          state = state,
          codeChallenge = codeChallenge,
          codeChallengeMethod = codeChallengeMethod,
          responseType = responseTypeEntries,
          requestedClaims = requestedClaims,
          uiLocales = uiLocales,
          nonce = nonce,
          userAgent = userAgent,
          prompt = prompt,
          maxAge = maxAge,
          acrValues = acrValues,
          sessionId = sessionId,
          loginHint = loginHint,
          idTokenHint = idTokenHint,
        )
      yield authorizeRequest

    private def parseEmailLoginHint(
        value: String,
        client: OAuthClientRecord,
        redirectUri: URL,
        state: Option[State],
    ): IO[Error.LoginHintInvalid, Option[Either[Email, Phone]]] =
      val allowed = client.authFlow.exists(_.primary.credentials.contains(PrimaryCredential.email))
      if !allowed then ZIO.fail(Error.LoginHintInvalid(redirectUri, state))
      else ZIO.fromEither(Email.from(value))
        .mapBoth(_ => Error.LoginHintInvalid(redirectUri, state), e => Some(Left(e)))

    private def parsePhoneLoginHint(
        value: String,
        client: OAuthClientRecord,
        redirectUri: URL,
        state: Option[State],
    ): IO[Error.LoginHintInvalid, Option[Either[Email, Phone]]] =
      val allowed = client.authFlow.exists(_.primary.credentials.contains(PrimaryCredential.phone))
      if !allowed then ZIO.fail(Error.LoginHintInvalid(redirectUri, state))
      else if value.drop(1).forall(_.isDigit) then
        oauthClientService.getAllowedPhonePrefixes(client.id).flatMap: prefixes =>
          if prefixes.isEmpty || prefixes.exists(value.startsWith) then ZIO.fromEither(Phone.parse(value)).mapBoth(_ => Error.LoginHintInvalid(redirectUri, state), p => Some(Right(p)))
          else ZIO.fail(Error.LoginHintInvalid(redirectUri, state))
      else ZIO.fail(Error.LoginHintInvalid(redirectUri, state))

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
