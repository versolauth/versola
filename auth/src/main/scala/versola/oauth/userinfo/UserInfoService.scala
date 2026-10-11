package versola.oauth.userinfo

import versola.oauth.client.OAuthConfigurationService
import versola.oauth.client.model.{Claim, ClientId, ScopeRecord, ScopeToken}
import versola.oauth.model.Nonce
import versola.oauth.userinfo.model.{RequestedClaims, UserInfoError, UserInfoResponse}
import versola.user.UserRepository
import versola.user.model.{UserId, UserRecord}
import zio.json.ast.Json
import zio.{IO, UIO, ZIO, ZLayer}

trait UserInfoService:
  def getUserInfo(
      userId: UserId,
      clientId: ClientId,
      scope: Set[ScopeToken],
      requestedClaims: Option[RequestedClaims],
  ): IO[Throwable | UserInfoError, UserInfoResponse]

  def getUserInfoForIdToken(
      user: UserRecord,
      clientId: ClientId,
      scope: Set[ScopeToken],
      requestedClaims: Option[RequestedClaims],
      uiLocales: Option[List[String]],
      nonce: Option[Nonce],
  ): UIO[UserInfoResponse]

object UserInfoService:
  def live: ZLayer[
    UserRepository & OAuthConfigurationService,
    Nothing,
    UserInfoService,
  ] = ZLayer.fromFunction(Impl(_, _))

  class Impl(
      userRepository: UserRepository,
      clientService: OAuthConfigurationService,
  ) extends UserInfoService:

    override def getUserInfo(
        userId: UserId,
        clientId: ClientId,
        scope: Set[ScopeToken],
        requestedClaims: Option[RequestedClaims],
    ): IO[Throwable | UserInfoError, UserInfoResponse] =
      for
        user <- userRepository.find(userId).someOrFail(UserInfoError.InvalidToken)
        response <- getUserInfoInternal(user, clientId, scope, requestedClaims, user.uiLocales, forIdToken = false, nonce = None)
      yield response

    override def getUserInfoForIdToken(
        user: UserRecord,
        clientId: ClientId,
        scope: Set[ScopeToken],
        requestedClaims: Option[RequestedClaims],
        uiLocales: Option[List[String]],
        nonce: Option[Nonce],
    ): UIO[UserInfoResponse] =
      getUserInfoInternal(user, clientId, scope, requestedClaims, uiLocales.orElse(user.uiLocales), forIdToken = true, nonce = nonce)

    private def getUserInfoInternal(
        user: UserRecord,
        clientId: ClientId,
        scope: Set[ScopeToken],
        requestedClaims: Option[RequestedClaims],
        uiLocalesOpt: Option[List[String]],
        forIdToken: Boolean,
        nonce: Option[Nonce],
    ): UIO[UserInfoResponse] =
      for
        authorizedClaims <- getAuthorizedClaims(clientId, scope, requestedClaims, forIdToken)

        userClaimsMap = user.claims.fields.toMap ++
          user.email.map(email => ("email", Json.Str(email))) ++
          user.phone.map(phone => ("phone_number", Json.Str(phone)))

        uiLocales = uiLocalesOpt.getOrElse(Nil)
        resolvedClaims = authorizedClaims.flatMap { claimName =>
          if uiLocales.nonEmpty then
            resolveLocalizedClaim(claimName, userClaimsMap, uiLocales)
          else
            userClaimsMap.get(claimName).map(value => (claimName, value))
        }.toMap

        claimsWithSub = resolvedClaims + ("sub" -> Json.Str(user.id.toString))

        finalClaims = if forIdToken then
          nonce match
            case Some(n) => claimsWithSub + ("nonce" -> Json.Str(n))
            case None => claimsWithSub
        else
          claimsWithSub
      yield UserInfoResponse(finalClaims)

    private def getAuthorizedClaims(
        clientId: ClientId,
        tokenScopes: Set[ScopeToken],
        requestedClaims: Option[RequestedClaims],
        forIdToken: Boolean,
    ): UIO[Set[Claim]] =
      for
        registeredScopes <- clientService.getScopes
        tokenScopeClaims = claimsOf(registeredScopes, tokenScopes)

        // What the request names through the `claims` parameter for this response (OIDC Core §5.5).
        requested = requestedClaims.map(rc => if forIdToken then rc.idToken.keySet else rc.userinfo.keySet)
          .getOrElse(Set.empty)

        // A first-party client never shows the user a consent screen (no `consentFlow`), so what it
        // asks for explicitly through `claims` is released from any scope registered for it, not only
        // the ones granted on this request. A client that does prompt keeps to the granted scopes:
        // there, a claim outside them would reach it without the user having agreed to it. Looked
        // up only when the request names claims, which is the only time it matters.
        firstPartyClaims <-
          if requested.isEmpty then ZIO.succeed(Set.empty[Claim])
          else firstPartyScopeClaims(clientId, registeredScopes)

        // OIDC Core §5.4: the claims a scope stands for are returned from the UserInfo endpoint
        // whenever an access token is issued, and only in the ID Token when none is. This server
        // supports `code` and `code id_token`, both of which issue one, so the ID Token carries
        // only what the request asked of it through the `claims` parameter's `id_token` member
        // (§5.5) -- and, as before, only what the client may be given. Putting every scope claim
        // in it as well bloats a token that is passed around and verified on every hop, and is
        // what the conformance suite warns about for scope=email.
        finalClaims =
          if forIdToken then (tokenScopeClaims ++ firstPartyClaims).intersect(requested)
          else if requested.nonEmpty then (tokenScopeClaims ++ firstPartyClaims).intersect(requested)
          else tokenScopeClaims
      yield finalClaims

    private def claimsOf(registeredScopes: Vector[ScopeRecord], scopes: Set[ScopeToken]): Set[Claim] =
      registeredScopes.filter(scope => scopes.contains(scope.scope)).flatMap(_.claims.map(_.claim)).toSet

    /** The claims of every scope registered for `clientId`, if it is a first-party client (one that
      * has no consent screen); empty for any other client or an unknown one. */
    private def firstPartyScopeClaims(clientId: ClientId, registeredScopes: Vector[ScopeRecord]): UIO[Set[Claim]] =
      clientService.find(clientId).map:
        case Some(client) if client.consentFlow.isEmpty => claimsOf(registeredScopes, client.scope)
        case _ => Set.empty

    /**
     * Resolve localized claims based on locale preferences
     *
     * Algorithm:
     * 1. Try exact locale match (e.g., "name#fr-CA")
     * 2. Try language-only match (e.g., "name#fr" for locale "fr-CA")
     * 3. Fallback to default claim (e.g., "name")
     *
     * @param claimName The base claim name (e.g., "name")
     * @param userClaims All user claims from database
     * @param locales Ordered list of locale preferences (e.g., ["fr-CA", "fr", "en"])
     * @return The resolved claim key and value, if found
     */
    private def resolveLocalizedClaim(
        claimName: String,
        userClaims: Map[String, Json],
        locales: List[String],
    ): Option[(String, Json)] =
      // Try exact locale matches first
      locales
        .flatMap: locale =>
          val localizedKey = s"$claimName#$locale"
          userClaims.get(localizedKey).map(value => (localizedKey, value))
        .headOption
        .orElse:
          // Try language-only matches (extract language from locale like "fr-CA" -> "fr")
          locales
            .flatMap: locale =>
              val lang = locale.split("-").head.toLowerCase
              val localizedKey = s"$claimName#$lang"
              userClaims.get(localizedKey).map(value => (localizedKey, value))
            .headOption
        .orElse:
          // Fallback to default claim (no locale suffix)
          userClaims.get(claimName).map(value => (claimName, value))
