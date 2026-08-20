package versola.oauth.authorize

import versola.oauth.authorize.model.{AuthorizeRequest, Error, Prompt, ResponseTypeEntry}
import versola.oauth.client.{AuthorizationDetailResolver, OAuthConfigurationService, ResourceResolver}
import versola.oauth.client.model.{Acr, AuthorizationDetail, ClientId, OAuthClientRecord, PrimaryCredential, ResourceUri, ScopeToken}
import versola.oauth.model.{CodeChallenge, CodeChallengeMethod, Nonce, RequestUri, State}
import versola.oauth.model.{SessionCookie, UserAgentCookie}
import versola.oauth.session.model.SessionId
import versola.oauth.userinfo.model.RequestedClaims
import versola.util.CoreConfig
import versola.util.http.Observability
import versola.util.{Base64, Email, JsonSchemaValidator, Phone, Secret, SecurityService}
import zio.http.{Form, Header, Method, Request, URL}
import zio.json.*
import zio.prelude.{NonEmptyList, NonEmptySet}
import zio.{Chunk, IO, Task, ZIO, ZLayer}

trait AuthorizeRequestParser:
  def parse(
      request: Request,
  ): IO[Error, AuthorizeRequest]

  /** Validates an already extracted parameter set, as the `/par` endpoint does before the
    * request is ever seen by a user agent (RFC 9126 §2.1).
    */
  def validate(
      params: Map[String, Chunk[String]],
      request: Request,
  ): IO[Error, AuthorizeRequest]

object AuthorizeRequestParser:
  def live = ZLayer.fromFunction(Impl(_, _, _, _, _))

  /** Keeps `state` (echoed into the ConversationCookie alongside the redirect URI) small
    * enough that it can't push that cookie past the ~4 KiB per-cookie limit browsers
    * enforce, which would otherwise silently drop the cookie and break /challenge.
    */
  private val MaxStateLength = 128

  /** Flattens a form into the same shape query parameters have, splitting the repeatable
    * `resource` parameter the same way `/authorize` does for POST bodies.
    */
  def paramsFromForm(form: Form): Map[String, Chunk[String]] =
    form.formData
      .flatMap { field =>
        field.stringValue.toList.flatMap { value =>
          val values =
            if field.name == "resource" then ResourceUri.splitFormValue(value)
            else List(value)
          values.map(field.name -> _)
        }
      }
      .groupMap(_._1)(_._2).view.mapValues(Chunk.fromIterable).toMap

  class Impl(
      config: CoreConfig,
      oauthClientService: OAuthConfigurationService,
      pushedAuthorizationRepository: PushedAuthorizationRepository,
      securityService: SecurityService,
      schemaValidator: JsonSchemaValidator,
  ) extends AuthorizeRequestParser:

    def parse(
        request: Request,
    ): IO[Error, AuthorizeRequest] =
      for
        rawParams <- extractRequestParams(request).orElseFail(Error.BadRequest)
        params <- resolvePushedRequest(rawParams)
        authorizeRequest <- validate(params, request)
      yield authorizeRequest

    /** RFC 9126 §4: a `request_uri` obtained from `/par` replaces the authorization request
      * payload entirely, is bound to the client that pushed it, and is single-use.
      */
    private def resolvePushedRequest(
        params: Map[String, Chunk[String]],
    ): IO[Error, Map[String, Chunk[String]]] =
      getParam(params, "request_uri").orElseFail(Error.BadRequest).flatMap:
        case None => ZIO.succeed(params)
        case Some(requestUri) =>
          for
            reference <- ZIO.fromEither(RequestUri.parse(requestUri)).orElseFail(Error.BadRequest)
            referenceMac <- securityService.mac(Secret(reference), config.security.parRequestsSecret)
              .orElseFail(Error.BadRequest)
            record <- pushedAuthorizationRepository.consume(referenceMac)
              .orElseFail(Error.BadRequest)
              .someOrFail(Error.BadRequest)
            // RFC 9126 §4 (via JAR §6.3): the outer client_id is required and must match the
            // client bound to the pushed request, not merely absent-or-matching.
            clientId <- getParam(params, "client_id").orElseFail(Error.BadRequest).someOrFail(Error.BadRequest)
            _ <- ZIO.fail(Error.BadRequest).unless(ClientId(clientId) == record.clientId)
          yield record.params.view.mapValues(Chunk.fromIterable).toMap

    def validate(
        params: Map[String, Chunk[String]],
        request: Request,
    ): IO[Error, AuthorizeRequest] =
      for
        (redirectUri, redirectUriString) <- parseRedirectUri(params)

        clientId <- getParam(params, "client_id")
          .orElseFail(Error.BadRequest)
          .someOrFail(Error.BadRequest)
          .map(ClientId(_))

        client <- oauthClientService.find(clientId)
          .someOrFail(Error.BadRequest)
          .filterOrFail(_.redirectUris.contains(redirectUriString))(Error.BadRequest)

        ipHeader <- oauthClientService.getIpHeader(clientId)
        ip = request.headers.get(ipHeader).map(_.split(',').head.trim).filter(_.nonEmpty)
        _ <- ZIO.foreachDiscard(ip)(Observability.setIp)

        state <- getParam(params, "state")
          .mapBoth(
            _ => Error.MultipleValuesProvided(redirectUri, None, "state"),
            _.map(State(_)),
          )
          .filterOrFail(_.forall(_.length <= MaxStateLength))(Error.StateInvalid(redirectUri))

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
          .someOrFail(Error.CodeChallengeMethodMissing(redirectUri, state))
          .flatMap {
            case "S256" => ZIO.succeed(CodeChallengeMethod.S256)
            case other => ZIO.fail(Error.CodeChallengeMethodInvalid(redirectUri, state, other))
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
          .orElseFail[Error](Error.MultipleValuesProvided(redirectUri, state, "acr_values"))
          .flatMap:
            case None => ZIO.none
            case Some(values) =>
              ZIO.fromOption(NonEmptyList.fromIterableOption(values.split(' ').map(Acr(_)).toList))
                .orElseFail(Error.NoValuesProvided(redirectUri, state, "acr_values"))
                .asSome

        userAgent =
          request.header(Header.UserAgent)
            .map(_.renderedValue)

        sessionId =
          request.cookie(SessionCookie.name)
            .flatMap(c => SessionCookie.parse(c.content, config.security.sessionCookieSecret).toOption)

        userAgentCookie =
          request.cookie(UserAgentCookie.name)
            .flatMap(c => UserAgentCookie.parse(c.content, config.security.userAgentCookieSecret).toOption)

        loginHint <- getParam(params, "login_hint")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "login_hint"))
          .flatMap {
            case None => ZIO.none
            case Some(value) if value.startsWith("+") && value.drop(1).forall(_.isDigit) => parsePhoneLoginHint(value, client, redirectUri, state)
            case Some(value) => parseEmailLoginHint(value, client, redirectUri, state)
          }

        idTokenHint <- getParam(params, "id_token_hint")
          .orElseFail(Error.MultipleValuesProvided(redirectUri, state, "id_token_hint"))

        resources <- resolveResources(params, client, redirectUri, state)

        authorizationDetails <- resolveAuthorizationDetails(params, client, redirectUri, state)

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
          userAgentCookie = userAgentCookie,
          prompt = prompt,
          maxAge = maxAge,
          acrValues = acrValues,
          sessionId = sessionId,
          loginHint = loginHint,
          idTokenHint = idTokenHint,
          resources = resources,
          authorizationDetails = authorizationDetails,
          ip = ip,
        )
      yield authorizeRequest

    /** RFC 9396 §2: parses `authorization_details` (a JSON array), then validates each object
      * against the type registered for the client's tenant. An unknown type, an object that
      * does not satisfy its type's schema, or a `locations` value that is not a resource
      * registered for the client all fail the request with `invalid_authorization_details`.
      */
    private def resolveAuthorizationDetails(
        params: Map[String, Chunk[String]],
        client: OAuthClientRecord,
        redirectUri: URL,
        state: Option[State],
    ): IO[Error, Option[List[AuthorizationDetail]]] =
      getParam(params, AuthorizationDetail.Parameter)
        .orElseFail(Error.MultipleValuesProvided(redirectUri, state, AuthorizationDetail.Parameter))
        .flatMap:
          case None => ZIO.none
          case Some(raw) =>
            ZIO.fromEither(AuthorizationDetail.parseAll(raw))
              .mapError(reason => Error.InvalidAuthorizationDetails(redirectUri, state, reason))
              .flatMap: details =>
                AuthorizationDetailResolver.resolve(oauthClientService, schemaValidator, client, details)
                  .mapError(rejected =>
                    Error.InvalidAuthorizationDetails(redirectUri, state, s"${rejected.`type`} - ${rejected.reason}"),
                  )
                  .asSome

    /** RFC 8707 §2: parses all `resource` values (the parameter may be repeated), validates
      * each is a well-formed resource URI, and resolves it to a resource registered for the
      * client's tenant. Public resources retain their registered URI. Explicit internal
      * resources use `resource://{resourceId}`; when no resource is requested, public resources
      * are returned alongside the `resource://edge` indicator, even if no internal resource exists;
      * the edge indicator cannot be combined with an explicitly requested internal resource;
      * any unresolvable value fails the whole request with `invalid_target`.
      */
    private def resolveResources(
        params: Map[String, Chunk[String]],
        client: OAuthClientRecord,
        redirectUri: URL,
        state: Option[State],
    ): IO[Error, List[ResourceUri]] =
      params.getOrElse("resource", Chunk.empty).toList match
        case Nil =>
          ResourceResolver.resolve(oauthClientService, client, None)
            .mapError(resource => Error.InvalidTarget(redirectUri, state, resource.toString))
        case values =>
          ZIO.foreach(values)(value =>
            ZIO.fromEither(ResourceUri.parse(value))
              .orElseFail(Error.InvalidTarget(redirectUri, state, value)),
          ).flatMap(resources =>
            ResourceResolver.resolve(oauthClientService, client, Some(resources))
              .mapError(resource => Error.InvalidTarget(redirectUri, state, resource.toString)),
          )

    private def parseEmailLoginHint(
        value: String,
        client: OAuthClientRecord,
        redirectUri: URL,
        state: Option[State],
    ): IO[Error.LoginHintInvalid, Option[Either[Email, Phone]]] =
      val allowed = client.authFlow.exists(_.primary.credentials.contains(PrimaryCredential.email))
      if !allowed then ZIO.fail(Error.LoginHintInvalid(redirectUri, state))
      else
        ZIO.fromEither(Email.from(value))
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
          if prefixes.isEmpty || prefixes.exists(value.startsWith) then
            ZIO.fromEither(Phone.parse(value)).mapBoth(_ => Error.LoginHintInvalid(redirectUri, state), p => Some(Right(p)))
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
            .map(_.formData.flatMap { field =>
              field.stringValue.toList.flatMap { value =>
                val values =
                  if field.name == "resource" then ResourceUri.splitFormValue(value)
                  else List(value)
                values.map(field.name -> _)
              }
            })
            .map(_.groupMap(_._1)(_._2).view.mapValues(Chunk.fromIterable).toMap)

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
