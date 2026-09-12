package versola.e2e.support

import zio.*
import zio.json.ast.Json

/** Minimal valid request bodies for central's admin API.
  *
  * Every builder produces a document central accepts as-is, with only the members a test
  * actually cares about exposed as parameters. A test that checks a rejection then names the
  * one thing it broke — `Fixtures.client(id, redirectUris = Set("not a uri"))` — instead of
  * spelling out fifteen unrelated members that have nothing to do with what it asserts.
  */
object Fixtures:

  val defaultTenant = "default"

  private def strings(values: Iterable[String]): Json.Arr =
    Json.Arr(Chunk.fromIterable(values.map(Json.Str(_))))

  /** A `LocalizedText` with a single English entry, which is all any of these tests read. */
  def text(value: String): Json.Obj =
    Json.Obj("en" -> Json.Str(value))

  /** `submissionLimits` defaults to a fully-configured value here so a test that inspects
    * it (rather than a test that just needs a tenant to exist) doesn't have to know that
    * `createTenant` silently swaps an unconfigured value for a recommended default --
    * matching `submissionLimits()` itself, which defaults to empty for the (separate)
    * challenge-settings endpoint.
    */
  def tenant(
      id: String,
      description: String = "e2e tenant",
      edgeId: Option[String] = None,
      submissionLimits: Json = submissionLimits(
        otpRequest = List(rateLimit(2, 60)),
        otpSubmit = List(rateLimit(3, 120)),
        passwordSubmit = List(rateLimit(5, 900)),
        passkeyAssertion = List(rateLimit(5, 300)),
        banDurationSeconds = 1800,
      ),
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "description" -> Json.Str(description),
        "submissionLimits" -> submissionLimits,
      ) ++ Chunk.fromIterable(edgeId.map(value => "edgeId" -> Json.Str(value))),
    )

  def client(
      id: String,
      tenantId: String = defaultTenant,
      name: String = "e2e client",
      redirectUris: Set[String] = Set("http://localhost:3000"),
      allowedScopes: Set[String] = Set("openid"),
      permissions: Set[String] = Set.empty,
      accessTokenTtl: Int = 3600,
      refreshTokenTtl: Option[Int] = None,
      theme: String = "default",
      otpTemplateId: String = "default",
      authFlow: Option[Json] = None,
      registrationFlow: Option[Json] = None,
      consentFlow: Option[Json] = None,
      frontChannelLogoutUri: Option[String] = None,
      frontChannelLogoutSessionRequired: Boolean = false,
      backChannelLogoutUri: Option[String] = None,
      logoUri: Option[String] = None,
      policyUri: Option[String] = None,
      tosUri: Option[String] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "tenantId" -> Json.Str(tenantId),
        "id" -> Json.Str(id),
        "clientName" -> text(name),
        "redirectUris" -> strings(redirectUris),
        "allowedScopes" -> strings(allowedScopes),
        "permissions" -> strings(permissions),
        "accessTokenTtl" -> Json.Num(accessTokenTtl),
        "theme" -> Json.Str(theme),
        "otpTemplateId" -> Json.Str(otpTemplateId),
        "frontChannelLogoutSessionRequired" -> Json.Bool(frontChannelLogoutSessionRequired),
      ) ++ Chunk.fromIterable(
        List(
          refreshTokenTtl.map(value => "refreshTokenTtl" -> Json.Num(value)),
          authFlow.map("authFlow" -> _),
          registrationFlow.map("registrationFlow" -> _),
          consentFlow.map("consentFlow" -> _),
          frontChannelLogoutUri.map(value => "frontChannelLogoutUri" -> Json.Str(value)),
          backChannelLogoutUri.map(value => "backChannelLogoutUri" -> Json.Str(value)),
          logoUri.map(value => "logoUri" -> Json.Str(value)),
          policyUri.map(value => "policyUri" -> Json.Str(value)),
          tosUri.map(value => "tosUri" -> Json.Str(value)),
        ).flatten,
      ),
    )

  /** The add/remove patch shape `PUT /configuration/clients` requires for its list members. */
  def patch(add: Set[String] = Set.empty, remove: Set[String] = Set.empty): Json.Obj =
    Json.Obj("add" -> strings(add), "remove" -> strings(remove))

  /** The add/delete patch shape every `LocalizedText` update uses. */
  def patchText(add: Map[String, String] = Map.empty, delete: Set[String] = Set.empty): Json.Obj =
    Json.Obj(
      "add" -> Json.Obj(Chunk.fromIterable(add.map((tag, value) => tag -> Json.Str(value)))),
      "delete" -> strings(delete),
    )

  /** An `UpdateClientRequest` whose three required patches are all no-ops, so a test only
    * has to supply the member it is actually changing.
    *
    * A named change replaces the default rather than being appended next to it: central
    * rejects a document with a repeated member, so appending would turn every patch test
    * into a 400 that says nothing about the behaviour under test.
    */
  def clientUpdate(clientId: String, changes: (String, Json)*): Json.Obj =
    val defaults = Chunk[(String, Json)](
      "clientId" -> Json.Str(clientId),
      "redirectUris" -> patch(),
      "scope" -> patch(),
      "permissions" -> patch(),
    )
    val overridden = changes.map(_._1).toSet
    Json.Obj(defaults.filterNot((name, _) => overridden.contains(name)) ++ Chunk.fromIterable(changes))

  def preset(
      id: String,
      redirectUri: String = "http://localhost:9005/complete",
      postLoginRedirectUri: String = "http://localhost:3000",
      postLogoutRedirectUri: Option[String] = None,
      scope: Set[String] = Set("openid"),
      responseType: String = "code",
      uiLocales: Option[List[String]] = None,
      customParameters: Map[String, List[String]] = Map.empty,
      cookieDomain: Option[String] = None,
      cookiePath: Option[String] = None,
      description: String = "e2e preset",
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "description" -> Json.Str(description),
        "redirectUri" -> Json.Str(redirectUri),
        "postLoginRedirectUri" -> Json.Str(postLoginRedirectUri),
        "scope" -> strings(scope),
        "responseType" -> Json.Str(responseType),
        "customParameters" -> Json.Obj(
          Chunk.fromIterable(customParameters.map((name, values) => name -> strings(values))),
        ),
      ) ++ Chunk.fromIterable(
        List(
          postLogoutRedirectUri.map(value => "postLogoutRedirectUri" -> Json.Str(value)),
          uiLocales.map(values => "uiLocales" -> strings(values)),
          cookieDomain.map(value => "cookieDomain" -> Json.Str(value)),
          cookiePath.map(value => "cookiePath" -> Json.Str(value)),
        ).flatten,
      ),
    )

  def presets(clientId: String, entries: Json.Obj*): Json.Obj =
    Json.Obj(
      "clientId" -> Json.Str(clientId),
      "presets" -> Json.Arr(Chunk.fromIterable(entries)),
    )

  def endpoint(
      id: String,
      method: String = "GET",
      path: String = "/items",
      fetchUserInfo: Boolean = false,
      allow: Option[String] = None,
      inject: List[Json] = Nil,
      stepUpCondition: Option[String] = None,
      stepUpAcr: Option[String] = None,
      maxAge: Option[Int] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "method" -> Json.Str(method),
        "path" -> Json.Str(path),
        "fetchUserInfo" -> Json.Bool(fetchUserInfo),
        "inject" -> Json.Arr(Chunk.fromIterable(inject)),
      ) ++ Chunk.fromIterable(
        List(
          allow.map(value => "allow" -> Json.Str(value)),
          stepUpCondition.map(value => "stepUpCondition" -> Json.Str(value)),
          stepUpAcr.map(value => "stepUpAcr" -> Json.Str(value)),
          maxAge.map(value => "maxAge" -> Json.Num(value)),
        ).flatten,
      ),
    )

  def inject(target: String, name: String, expression: String): Json.Obj =
    Json.Obj(
      "target" -> Json.Str(target),
      "name" -> Json.Str(name),
      "expression" -> Json.Str(expression),
    )

  def resource(
      resourceId: String,
      resource: String,
      tenantId: String = defaultTenant,
      audience: Set[String] = Set.empty,
      endpoints: List[Json] = Nil,
      internal: Boolean = false,
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "resourceId" -> Json.Str(resourceId),
      "resource" -> Json.Str(resource),
      "audience" -> strings(audience),
      "endpoints" -> Json.Arr(Chunk.fromIterable(endpoints)),
      "internal" -> Json.Bool(internal),
    )

  def resourceUpdate(
      resourceId: String,
      resource: Option[String] = None,
      audience: Option[Set[String]] = None,
      deleteEndpoints: Set[String] = Set.empty,
      createEndpoints: List[Json] = Nil,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "resourceId" -> Json.Str(resourceId),
        "deleteEndpoints" -> strings(deleteEndpoints),
        "createEndpoints" -> Json.Arr(Chunk.fromIterable(createEndpoints)),
      ) ++ Chunk.fromIterable(
        List(
          resource.map(value => "resource" -> Json.Str(value)),
          audience.map(values => "audience" -> strings(values)),
        ).flatten,
      ),
    )

  def claim(id: String, description: String = "e2e claim"): Json.Obj =
    Json.Obj("id" -> Json.Str(id), "description" -> text(description))

  /** A `PatchClaim`: unlike a created claim, an updated one carries a description *patch*
    * rather than a replacement.
    */
  def claimPatch(id: String, add: Map[String, String] = Map.empty, delete: Set[String] = Set.empty): Json.Obj =
    Json.Obj("id" -> Json.Str(id), "description" -> patchText(add, delete))

  def scope(
      id: String,
      tenantId: String = defaultTenant,
      description: String = "e2e scope",
      claims: List[Json] = Nil,
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "id" -> Json.Str(id),
      "description" -> text(description),
      "claims" -> Json.Arr(Chunk.fromIterable(claims)),
    )

  def scopeUpdate(
      id: String,
      tenantId: String = defaultTenant,
      add: List[Json] = Nil,
      update: List[Json] = Nil,
      delete: Set[String] = Set.empty,
      description: Json.Obj = patchText(),
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "id" -> Json.Str(id),
      "patch" -> Json.Obj(
        "add" -> Json.Arr(Chunk.fromIterable(add)),
        "update" -> Json.Arr(Chunk.fromIterable(update)),
        "delete" -> strings(delete),
        "description" -> description,
      ),
    )

  def permission(
      permission: String,
      tenantId: String = defaultTenant,
      description: String = "e2e permission",
      endpointIds: Set[String] = Set.empty,
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "permission" -> Json.Str(permission),
      "description" -> text(description),
      "endpointIds" -> strings(endpointIds),
    )

  def permissionUpdate(
      permission: String,
      tenantId: String = defaultTenant,
      description: Json.Obj = patchText(),
      endpointIds: Option[Set[String]] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "tenantId" -> Json.Str(tenantId),
        "permission" -> Json.Str(permission),
        "description" -> description,
      ) ++ Chunk.fromIterable(endpointIds.map(values => "endpointIds" -> strings(values))),
    )

  def role(
      id: String,
      tenantId: String = defaultTenant,
      description: String = "e2e role",
      permissions: Set[String] = Set.empty,
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "id" -> Json.Str(id),
      "description" -> text(description),
      "permissions" -> strings(permissions),
    )

  def roleUpdate(
      id: String,
      tenantId: String = defaultTenant,
      description: Json.Obj = patchText(),
      permissions: Json.Obj = patch(),
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "id" -> Json.Str(id),
      "description" -> description,
      "permissions" -> permissions,
    )

  /** A JSON Schema that accepts `{"type": ..., "amount": <number>}` and nothing else, which is
    * enough to tell a conforming authorization detail from a violating one.
    */
  val amountSchema: Json.Obj =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj("amount" -> Json.Obj("type" -> Json.Str("number"))),
      "required" -> Json.Arr(Chunk(Json.Str("amount"))),
    )

  def detailType(
      typeName: String,
      tenantId: String = defaultTenant,
      description: String = "e2e detail type",
      schema: Json = amountSchema,
  ): Json.Obj =
    Json.Obj(
      "tenantId" -> Json.Str(tenantId),
      "type" -> Json.Str(typeName),
      "description" -> text(description),
      "schema" -> schema,
    )

  def user(
      email: Option[String] = None,
      phone: Option[String] = None,
      login: Option[String] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk.fromIterable(
        List(
          email.map(value => "email" -> Json.Str(value)),
          phone.map(value => "phone" -> Json.Str(value)),
          login.map(value => "login" -> Json.Str(value)),
        ).flatten,
      ),
    )

  def otpTemplate(
      id: String,
      tenantId: String = defaultTenant,
      purpose: String = "otp",
      channel: String = "sms",
      body: String = "Your code is {{code}}",
  ): Json.Obj =
    Json.Obj(
      "id" -> Json.Str(id),
      "tenantId" -> Json.Str(tenantId),
      "localizations" -> text(body),
      "purpose" -> Json.Str(purpose),
      "channel" -> Json.Str(channel),
    )

  def otpTemplateKey(
      id: String,
      tenantId: String = defaultTenant,
      purpose: String = "otp",
      channel: String = "sms",
  ): Json.Obj =
    Json.Obj(
      "id" -> Json.Str(id),
      "tenantId" -> Json.Str(tenantId),
      "purpose" -> Json.Str(purpose),
      "channel" -> Json.Str(channel),
    )

  def rateLimit(maxAttempts: Int, windowSeconds: Int): Json.Obj =
    Json.Obj("maxAttempts" -> Json.Num(maxAttempts), "windowSeconds" -> Json.Num(windowSeconds))

  def submissionLimits(
      otpRequest: List[Json] = Nil,
      otpSubmit: List[Json] = Nil,
      passwordSubmit: List[Json] = Nil,
      passkeyAssertion: List[Json] = Nil,
      banDurationSeconds: Int = 0,
  ): Json.Obj =
    Json.Obj(
      "otpRequest" -> Json.Arr(Chunk.fromIterable(otpRequest)),
      "otpSubmit" -> Json.Arr(Chunk.fromIterable(otpSubmit)),
      "passwordSubmit" -> Json.Arr(Chunk.fromIterable(passwordSubmit)),
      "passkeyAssertion" -> Json.Arr(Chunk.fromIterable(passkeyAssertion)),
      "banDurationSeconds" -> Json.Num(banDurationSeconds),
    )

  def passkeySettings(
      rpId: String = "localhost",
      rpName: String = "Versola",
      origins: Set[String] = Set("http://localhost:3000"),
      userVerification: String = "preferred",
  ): Json.Obj =
    Json.Obj(
      "rpId" -> Json.Str(rpId),
      "rpName" -> Json.Str(rpName),
      "origins" -> strings(origins),
      "userVerification" -> Json.Str(userVerification),
    )

  def challengeSettings(
      tenantId: String,
      allowedPrefixes: Set[String] = Set.empty,
      submissionLimits: Json = submissionLimits(),
      otpLength: Int = 6,
      otpResendAfter: Int = 60,
      passkeySettings: Json = passkeySettings(),
      ipHeader: String = "X-Forwarded-For",
      extras: List[(String, Json)] = Nil,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "tenantId" -> Json.Str(tenantId),
        "allowedPrefixes" -> strings(allowedPrefixes),
        "submissionLimits" -> submissionLimits,
        "otpLength" -> Json.Num(otpLength),
        "otpResendAfter" -> Json.Num(otpResendAfter),
        "passkeySettings" -> passkeySettings,
        "ipHeader" -> Json.Str(ipHeader),
      ) ++ Chunk.fromIterable(extras),
    )

  def theme(id: String, css: String = ".versola { color: #123456; }", tenantId: Option[String] = None): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "id" -> Json.Str(id),
        "css" -> Json.Str(css),
      ) ++ Chunk.fromIterable(tenantId.map(value => "tenantId" -> Json.Str(value))),
    )

  /** An RSA JWK with a caller-chosen `kid`. The key material is fixed and public — these
    * tests only ever check that central stores, replaces and deletes the document it is
    * given, never that the key can sign anything.
    */
  def jwk(kid: String): Json.Obj =
    Json.Obj(
      "kid" -> Json.Str(kid),
      "kty" -> Json.Str("RSA"),
      "alg" -> Json.Str("RS256"),
      "use" -> Json.Str("sig"),
      "n" -> Json.Str(
        "sXchYwvJHxHmVJXKGZLmzLZQMPGdY9-c1eDpEwZNsIcqzHRQTd5cnzBCTQYVexOSF8h6i4qF9ZQxYAaHqxRkOgS" +
          "vLKG_pMWO9SBHfChK-uS6UOB2FxIH1jI1zaLMTgO4qw2mAtZ2sNzTLM5tnh3MB2rXlnEeh3cnG3vDbdznWNTQ",
      ),
      "e" -> Json.Str("AQAB"),
    )

  def locale(code: String, name: String, isDefault: Boolean = false, active: Boolean = false): Json.Obj =
    Json.Obj(
      "code" -> Json.Str(code),
      "name" -> Json.Str(name),
      "isDefault" -> Json.Bool(isDefault),
      "active" -> Json.Bool(active),
    )

  def localeUpdate(add: List[Json] = Nil, delete: Set[String] = Set.empty): Json.Obj =
    Json.Obj(
      "add" -> Json.Arr(Chunk.fromIterable(add)),
      "delete" -> strings(delete),
    )

  def systemSettings(
      passwordRegex: String = ".{8,}",
      passwordHistorySize: Int = 3,
      passwordNumDifferent: Int = 1,
      identityProviderLogo: Option[String] = None,
  ): Json.Obj =
    Json.Obj(
      Chunk[(String, Json)](
        "passwordRegex" -> Json.Str(passwordRegex),
        "passwordHistorySize" -> Json.Num(passwordHistorySize),
        "passwordNumDifferent" -> Json.Num(passwordNumDifferent),
      ) ++ Chunk.fromIterable(identityProviderLogo.map(value => "identityProviderLogo" -> Json.Str(value))),
    )
