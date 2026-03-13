package versola.oauth.userinfo

import versola.oauth.client.model.{Claim, ScopeToken}
import versola.oauth.client.OAuthClientService
import versola.oauth.model.AccessToken
import versola.oauth.userinfo.model.{RequestedClaims, UserInfoError, UserInfoResponse}
import versola.user.UserRepository
import versola.user.model.UserId
import zio.json.ast.Json
import zio.{IO, UIO, ZIO, ZLayer}

trait UserInfoService:
  def getUserInfo(token: AccessToken): IO[Throwable | UserInfoError, UserInfoResponse]

object UserInfoService:
  def live: ZLayer[
    UserRepository & OAuthClientService,
    Nothing,
    UserInfoService,
  ] = ZLayer.fromFunction(Impl(_, _))

  class Impl(
      userRepository: UserRepository,
      clientService: OAuthClientService,
  ) extends UserInfoService:

    override def getUserInfo(token: AccessToken): IO[Throwable | UserInfoError, UserInfoResponse] =
      for
        authorizedClaims <- getAuthorizedClaims(token.scope, token.requestedClaims)
        user <- userRepository.find(token.userId).someOrFail(UserInfoError.InvalidToken)
        response <- buildResponse(
          token.userId,
          authorizedClaims,
          user.claims,
          token.uiLocales.getOrElse(Vector.empty),
        )
      yield response

    private def getAuthorizedClaims(
        tokenScopes: Set[ScopeToken],
        requestedClaims: Option[RequestedClaims],
    ): UIO[Set[Claim]] =
      for
        registeredScopes <- clientService.getAllScopesCached
        tokenScopeClaims = tokenScopes
          .flatMap(scope => registeredScopes.get(scope).map(_.claims).getOrElse(Set.empty))

        finalClaims = requestedClaims match
          case Some(rc) if rc.userinfo.nonEmpty =>
            tokenScopeClaims.intersect(rc.userinfo.keySet)
          case _ =>
            tokenScopeClaims
      yield finalClaims

    private def buildResponse(
        userId: UserId,
        authorizedClaims: Set[Claim],
        userClaims: Json,
        uiLocales: Vector[String],
    ): IO[UserInfoError, UserInfoResponse] =
      ZIO.succeed:
        val userClaimsMap = userClaims match
          case Json.Obj(fields) => fields.toMap
          case _ => Map.empty[String, Json]

        // Resolve localized claims for each authorized claim
        val resolvedClaims = authorizedClaims.flatMap { claimName =>
          if uiLocales.nonEmpty then
            resolveLocalizedClaim(claimName, userClaimsMap, uiLocales)
          else
            userClaimsMap.get(claimName).map(value => (claimName, value))
        }.toMap

        // Always include 'sub' claim if openid scope was granted
        val finalClaims = resolvedClaims + ("sub" -> Json.Str(userId.toString))

        UserInfoResponse(finalClaims)

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
        locales: Vector[String],
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
