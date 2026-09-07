package versola.e2e.flows.basic

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** OIDC Discovery, JWKS publication, and signature verification of a real issued token.
  *
  * The rest of the suite trusts the claims a token carries. These tests instead check them
  * against the keys auth publishes, so a token that is not actually signed by the advertised
  * key — or a key set that leaks its private half — fails here.
  */
object DiscoveryAndJwksSpec extends E2ESpec:

  /** The `kid` and `alg` of a JWT header, read without verifying anything. */
  private case class JwtHeader(kid: String, alg: String) derives JsonDecoder

  /** The registered id token claims this spec checks for sanity. `aud` is serialized as a bare
    * string when the token names a single audience, which is what a one-client grant produces.
    */
  private case class IdTokenClaims(iss: String, aud: Json, exp: Long, iat: Long) derives JsonDecoder:
    def audiences: List[String] = aud match
      case Json.Str(single) => List(single)
      case Json.Arr(many) => many.collect { case Json.Str(one) => one }.toList
      case _ => Nil

  /** Private RSA/EC JWK parameters (RFC 7517 §4, RFC 7518 §6.3.2) that a *public* set must never carry. */
  private val privateKeyMembers = Set("d", "p", "q", "dp", "dq", "qi")

  private def segment(token: String, index: Int): Task[String] =
    ZIO.attempt(String(Base64.getUrlDecoder.decode(token.split('.')(index)), StandardCharsets.UTF_8))
      .mapError(error => RuntimeException(s"Cannot decode segment $index of JWT: $error"))

  private def header(token: String): Task[JwtHeader] =
    segment(token, 0).flatMap: json =>
      ZIO.fromEither(json.fromJson[JwtHeader])
        .mapError(error => RuntimeException(s"JWT header has no usable 'kid'/'alg' [$error]: $json"))

  private def claims(token: String): Task[IdTokenClaims] =
    segment(token, 1).flatMap: json =>
      ZIO.fromEither(json.fromJson[IdTokenClaims])
        .mapError(error => RuntimeException(s"id_token claims are not as expected [$error]: $json"))

  private def string(document: Json.Obj, field: String): Task[String] =
    ZIO.fromOption(document.get(field).flatMap(_.as[String].toOption))
      .orElseFail(RuntimeException(s"Metadata has no string '$field': ${document.toJson}"))

  private def strings(document: Json.Obj, field: String): Task[List[String]] =
    ZIO.fromOption(document.get(field).flatMap(_.as[List[String]].toOption))
      .orElseFail(RuntimeException(s"Metadata has no string array '$field': ${document.toJson}"))

  private def keys(jwkSet: Json.Obj): Task[Chunk[Json.Obj]] =
    ZIO.fromOption(jwkSet.get("keys").collect { case Json.Arr(elements) => elements.collect { case o: Json.Obj => o } })
      .orElseFail(RuntimeException(s"JWK Set has no 'keys' array: ${jwkSet.toJson}"))

  /** Signs in with the shared login+password client and returns the issued id token. */
  private val loginForIdToken: RIO[Flows.Setups, (Flows.Setup, OAuthClient, String)] =
    for
      (s, auth) <- setup(Flows.Id.LoginPassword)
      authorize <- auth.authorize(clientId = Some(s.clientId), redirectUri = Some(s.redirectUri))
        .assertChallengeRedirect
      cookie = authorize.conversationCookie.get
      challenge <- auth.getChallenge(cookie).assertStep(ConversationStep.Credential)
      code <- auth.submitLoginPassword(cookie, s.login.get, s.password, challenge.csrf).assertRedirect(auth, cookie)
      token <- auth.token(
        code,
        authorize.verifier,
        clientId = Some(s.clientId),
        clientSecret = Some(s.clientSecret),
        redirectUri = Some(s.redirectUri),
      ).success
      idToken <- ZIO.fromOption(token.idToken).orElseFail(RuntimeException("token response carries no id_token"))
    yield (s, auth, idToken)

  def spec = suite("OIDC Discovery and JWKS")(
    test("serves a JSON discovery document at /.well-known/openid-configuration without authentication") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        response <- auth.probe(Method.GET, s"${auth.authBaseUrl}/.well-known/openid-configuration")
        body <- response.body.asString
        document <- auth.discoveryDocument
        issuer <- string(document, "issuer")
      yield assertTrue(response.status == Status.Ok)
        .label(s"discovery must answer 200 with no credential, got ${response.status}") &&
        assertTrue(body.fromJson[Json.Obj].isRight)
          .label(s"discovery must be a JSON object, got: $body") &&
        assertTrue(issuer == auth.authBaseUrl)
          .label(s"discovery 'issuer' must be ${auth.authBaseUrl}, got $issuer")
    },

    test("advertises endpoints that are served on the live issuer") {
      // Each endpoint is probed with the method it is registered under, because zio-http
      // answers 404 — not 405 — for a path that exists only under a different method.
      val advertised: List[(String, Method, String)] = List(
        ("authorization_endpoint", Method.GET, "/authorize"),
        ("token_endpoint", Method.POST, "/token"),
        ("userinfo_endpoint", Method.GET, "/userinfo"),
        ("jwks_uri", Method.GET, "/.well-known/jwks.json"),
        ("introspection_endpoint", Method.POST, "/introspect"),
        ("revocation_endpoint", Method.POST, "/revoke"),
        ("pushed_authorization_request_endpoint", Method.POST, "/par"),
      )
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        checked <- ZIO.foreach(advertised) { (field, method, path) =>
          for
            url <- string(document, field)
            // An endpoint advertised on a port nothing listens on fails to connect rather
            // than answering, so a probe that never lands counts as "not served" too.
            status <- auth.probe(method, url).map(r => Some(r.status)).catchAll(_ => ZIO.none)
          yield (field, url, s"${auth.authBaseUrl}$path", status)
        }
        // Control: the same probe against a path auth does not serve must be a 404, so
        // "not a 404" below is a real signal rather than something every URL satisfies.
        absent <- auth.probe(Method.GET, s"${auth.authBaseUrl}/.well-known/not-an-endpoint")
      yield assertTrue(checked.forall((_, url, expected, _) => url == expected))
        .label(
          "every advertised endpoint must point at the live issuer: " +
            checked.collect { case (f, url, expected, _) if url != expected => s"$f=$url (expected $expected)" }
              .mkString(", "),
        ) &&
        assertTrue(checked.forall((_, _, _, status) => status.exists(_ != Status.NotFound)))
          .label(
            "every advertised endpoint must actually be served: " +
              checked.collect {
                case (f, url, _, Some(s)) if s == Status.NotFound => s"$f=$url -> $s"
                case (f, url, _, None) => s"$f=$url -> unreachable"
              }.mkString(", "),
          ) &&
        assertTrue(absent.status == Status.NotFound)
          .label(s"an unserved path must answer 404 for the check above to mean anything, got ${absent.status}")
    },

    test("every advertised grant_types_supported value is one the token endpoint accepts") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        advertised <- strings(document, "grant_types_supported")
        // Sent with no other parameter: a supported grant fails later, on its own missing
        // parameters, while an unsupported one is rejected at grant-type dispatch.
        rejected <- ZIO.foreach(advertised) { grant =>
          auth.tokenRaw(Map("grant_type" -> grant), s.clientId, s.clientSecret)
            .flatMap(_.body.asString)
            .map(body => grant -> body.contains("unsupported_grant_type"))
        }
        // Control: a grant auth genuinely does not implement, and does not advertise.
        control <- auth.tokenRaw(
          Map("grant_type" -> "urn:ietf:params:oauth:grant-type:device_code"),
          s.clientId,
          s.clientSecret,
        ).flatMap(_.body.asString)
      yield assertTrue(advertised.nonEmpty)
        .label("discovery must advertise at least one grant type") &&
        assertTrue(rejected.forall(!_._2))
          .label(
            "advertised grant types the token endpoint rejects as unsupported: " +
              rejected.collect { case (g, true) => g }.mkString(", "),
          ) &&
        assertTrue(control.contains("unsupported_grant_type"))
          .label(s"an unadvertised grant must be rejected for the check above to mean anything, got: $control") &&
        assertTrue(!advertised.contains("urn:ietf:params:oauth:grant-type:device_code"))
          .label("the control grant must not itself be advertised")
    },

    test("advertises code_challenge_methods_supported that match what /authorize enforces") {
      for
        (s, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        methods <- strings(document, "code_challenge_methods_supported")
        // The advertised method must get through, and `plain` must not — OAuth 2.1 §7.5.2
        // forbids it, and advertising it would invite clients into a downgrade.
        _ <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, codeChallengeMethod = Some("S256"))
          .assertChallengeRedirect
        _ <- auth.authorizeRaw(clientId = s.clientId, redirectUri = s.redirectUri, codeChallengeMethod = Some("plain"))
          .assertErrorRedirect("invalid_request")
      yield assertTrue(methods.contains("S256"))
        .label(s"S256 is accepted by /authorize, so it must be advertised; got $methods") &&
        assertTrue(!methods.contains("plain"))
          .label(s"plain is rejected by /authorize, so it must not be advertised; got $methods")
    },

    test("jwks_uri resolves to a well-formed JWK Set") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        document <- auth.discoveryDocument
        jwksUri <- string(document, "jwks_uri")
        response <- auth.probe(Method.GET, jwksUri)
        jwkSet <- auth.jwks(jwksUri)
        published <- keys(jwkSet)
        rsa = published.filter(_.get("kty").contains(Json.Str("RSA")))
      yield assertTrue(response.status == Status.Ok)
        .label(s"jwks_uri must answer 200 with no credential, got ${response.status}") &&
        assertTrue(published.nonEmpty)
          .label(s"JWK Set must publish at least one key: ${jwkSet.toJson}") &&
        assertTrue(
          published.forall(key =>
            List("kty", "use", "kid", "alg").forall(m => key.get(m).exists(_ != Json.Null)),
          ),
        ).label(s"every JWK must carry kty/use/kid/alg: ${jwkSet.toJson}") &&
        assertTrue(published.forall(_.get("kid").flatMap(_.as[String].toOption).exists(_.nonEmpty)))
          .label(s"every 'kid' must be a non-empty string: ${jwkSet.toJson}") &&
        assertTrue(rsa.nonEmpty)
          .label(s"auth signs with RS256, so the set must hold an RSA key: ${jwkSet.toJson}") &&
        assertTrue(
          rsa.forall(key =>
            List("n", "e").forall(m => key.get(m).flatMap(_.as[String].toOption).exists(_.nonEmpty)),
          ),
        ).label(s"every RSA JWK must expose 'n' and 'e': ${jwkSet.toJson}")
    },

    test("the published JWK Set carries no private key material") {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        jwkSet <- auth.jwks()
        published <- keys(jwkSet)
        leaked = published.flatMap(key =>
          key.fields.collect { case (name, value) if privateKeyMembers(name) && value != Json.Null => name },
        )
      yield assertTrue(published.nonEmpty)
        .label("there must be a key to inspect for this guard to mean anything") &&
        assertTrue(leaked.isEmpty)
          .label(s"the public JWK Set leaks private parameters ${leaked.mkString(", ")}: ${jwkSet.toJson}")
    },

    test("verifies a real id_token's signature against the published JWKS") {
      for
        (s, auth, idToken) <- loginForIdToken
        document <- auth.discoveryDocument
        issuer <- string(document, "issuer")
        jwksUri <- string(document, "jwks_uri")
        jwkSet <- auth.jwks(jwksUri)
        published <- keys(jwkSet)
        head <- header(idToken)
        verified <- auth.verifyJwtSignature(idToken, jwkSet)
        payload <- claims(idToken)
        now <- ZIO.succeed(java.time.Instant.now())
      yield assertTrue(verified)
        .label(s"the id_token signature must verify against the published key kid=${head.kid}") &&
        assertTrue(published.exists(_.get("kid").contains(Json.Str(head.kid))))
          .label(s"the id_token header kid='${head.kid}' must be published: ${jwkSet.toJson}") &&
        assertTrue(head.alg == "RS256")
          .label(s"the id_token must be signed with RS256, got ${head.alg}") &&
        assertTrue(payload.iss == issuer)
          .label(s"id_token 'iss' must be the advertised issuer '$issuer', got '${payload.iss}'") &&
        assertTrue(payload.audiences.contains(s.clientId))
          .label(s"id_token 'aud' must contain '${s.clientId}', got ${payload.audiences}") &&
        assertTrue(payload.iat <= now.getEpochSecond + 5)
          .label(s"id_token 'iat' must not be in the future, got ${payload.iat} at ${now.getEpochSecond}") &&
        assertTrue(payload.exp > now.getEpochSecond)
          .label(s"id_token 'exp' must still be ahead, got ${payload.exp} at ${now.getEpochSecond}") &&
        assertTrue(payload.exp > payload.iat)
          .label(s"id_token 'exp' must be after 'iat', got exp=${payload.exp} iat=${payload.iat}")
    },

    test("rejects an id_token whose payload was altered under an intact signature") {
      for
        (_, auth, idToken) <- loginForIdToken
        jwkSet <- auth.jwks()
        parts = idToken.split('.')
        // Flip the first character of `sub` and keep everything else, signature included:
        // the token stays a well-formed JWT naming a published key, only the bytes that
        // were signed no longer match the signature over them.
        decoded <- segment(idToken, 1)
        subAt <- ZIO.attempt(decoded.indexOf("\"sub\":\"") + "\"sub\":\"".length)
        tamperedClaims = decoded.updated(subAt, if decoded.charAt(subAt) == '0' then '1' else '0')
        reencoded = Base64.getUrlEncoder.withoutPadding.encodeToString(tamperedClaims.getBytes(StandardCharsets.UTF_8))
        tampered = s"${parts(0)}.$reencoded.${parts(2)}"
        genuine <- auth.verifyJwtSignature(idToken, jwkSet)
        forged <- auth.verifyJwtSignature(tampered, jwkSet)
      yield assertTrue(tamperedClaims != decoded)
        .label(s"the fixture must actually alter the payload: $decoded") &&
        assertTrue(tampered.split('.')(2) == parts(2) && tampered.split('.')(0) == parts(0))
          .label("the tampered token must reuse the original header and signature verbatim") &&
        assertTrue(genuine)
          .label("the untouched token must verify, so the rejection below is about the tampering") &&
        assertTrue(!forged)
          .label("a token whose payload was altered must fail signature verification")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
