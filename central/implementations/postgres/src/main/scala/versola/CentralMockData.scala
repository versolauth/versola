package versola

import versola.central.configuration.clients.ClientId
import versola.central.configuration.permissions.Permission
import versola.central.configuration.resources.ResourceEndpointId
import versola.central.configuration.roles.RoleId
import versola.central.configuration.scopes.{Claim, ScopeToken}
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.{AclRuleTree, CreateClaim, PermissionRule, ResourceUri}
import versola.util.RedirectUri
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.util.UUID

object CentralMockData:
  type TenantModel = (id: TenantId, description: String)

  type PermissionModel = (
      tenantId: TenantId,
      id: Permission,
      description: Map[String, String],
      endpointIds: Set[ResourceEndpointId],
  )

  type ResourceModel = (
      tenantId: TenantId,
      resource: ResourceUri,
      endpoints: Vector[ResourceEndpointModel],
  )

  type ResourceEndpointModel = (
      id: ResourceEndpointId,
      key: String,
      method: String,
      path: String,
      fetchUserInfo: Boolean,
      allowRules: AclRuleTree,
      denyRules: AclRuleTree,
      injectHeaders: Map[String, String],
  )

  type ClientModel = (
      tenantId: TenantId,
      id: ClientId,
      clientName: String,
      redirectUris: Set[RedirectUri],
      scope: Set[ScopeToken],
      audience: List[ClientId],
      permissions: Set[Permission],
      accessTokenTtl: Int,
      hasPreviousSecret: Boolean,
  )

  type ScopeModel = (
      tenantId: TenantId,
      id: ScopeToken,
      description: Map[String, String],
      claims: List[CreateClaim],
  )

  type RoleModel = (
      tenantId: TenantId,
      id: RoleId,
      description: Map[String, String],
      permissions: List[Permission],
      active: Boolean,
  )

  private val defaultTenant = TenantId("default")

  private def localized(en: String, ru: String): Map[String, String] =
    Map("en" -> en, "ru" -> ru)

  private def createClaim(id: String, en: String, ru: String): CreateClaim =
    CreateClaim(Claim(id), localized(en, ru))

  private def endpointId(key: String): ResourceEndpointId =
    ResourceEndpointId(UUID.nameUUIDFromBytes(s"central-mock-endpoint:$key".getBytes(StandardCharsets.UTF_8)))

  private def permissionModel(
      tenantId: TenantId,
      id: String,
      en: String,
      ru: String,
      endpointKeys: Set[String] = Set.empty,
  ): PermissionModel =
    (
      tenantId = tenantId,
      id = Permission(id),
      description = localized(en, ru),
      endpointIds = endpointKeys.map(endpointId),
    )

  private def permissions(ids: String*): List[Permission] =
    ids.toList.map(Permission(_))

  private def resourceModel(tenantId: TenantId, resource: String, endpoints: ResourceEndpointModel*): ResourceModel =
    (
      tenantId = tenantId,
      resource = ResourceUri(resource),
      endpoints = endpoints.toVector,
    )

  private def endpointModel(
      key: String,
      method: String,
      path: String,
      fetchUserInfo: Boolean = false,
      allowRules: Vector[PermissionRule] = Vector.empty,
      denyRules: Vector[PermissionRule] = Vector.empty,
      injectHeaders: Map[String, String] = Map.empty,
  ): ResourceEndpointModel =
    (
      id = endpointId(key),
      key = key,
      method = method,
      path = path,
      fetchUserInfo = fetchUserInfo,
      allowRules = AclRuleTree.any(
        Option.when(allowRules.nonEmpty)(
          Vector(AclRuleTree.all(allowRules.map(permissionRule => AclRuleTree.rule(permissionRule))))
        ).getOrElse(Vector.empty)
      ),
      denyRules = AclRuleTree.any(
        Option.when(denyRules.nonEmpty)(
          Vector(AclRuleTree.all(denyRules.map(permissionRule => AclRuleTree.rule(permissionRule))))
        ).getOrElse(Vector.empty)
      ),
      injectHeaders = injectHeaders,
    )

  private def rule(subject: String, operator: String, value: Json, pattern: Option[String] = None): PermissionRule =
    PermissionRule(subject, operator, value, pattern)

  private def actionRule(action: String): PermissionRule =
    rule("authorization_details.actions", "contains", Json.Str(action))

  private def clearanceRule(level: Int): PermissionRule =
    rule("jwt.clearance_level", "gte", Json.Num(level.toLong))

  private def stringArray(values: String*): Json =
    Json.Arr(values.map(Json.Str(_))*)

  val tenants: Vector[TenantModel] = Vector(
    (id = TenantId("default"), description = "Default organization"),
    (id = TenantId("acme-corp"), description = "Main production environment"),
    (id = TenantId("globex"), description = "Global expansion division"),
    (id = TenantId("initech"), description = "IT solutions provider"),
    (id = TenantId("umbrella"), description = "Pharmaceutical research"),
    (id = TenantId("wayne-enterprises"), description = "Multi-industry conglomerate"),
    (id = TenantId("stark-industries"), description = "Advanced technology research"),
    (id = TenantId("hooli"), description = "Technology company"),
    (id = TenantId("pied-piper"), description = "Compression algorithms"),
    (id = TenantId("test-org"), description = "Testing and development"),
    (id = TenantId("demo-company"), description = "Demo and sandbox environment"),
    (id = TenantId("staging-env"), description = "Pre-production testing"),
    (id = TenantId("dev-sandbox"), description = "Development playground"),
  )

  val permissionDefinitions: Vector[PermissionModel] = Vector(
    permissionModel(defaultTenant, "tenants:read", "Read tenants", "Просмотр тенантов"),
    permissionModel(defaultTenant, "tenants:write", "Create and update tenants", "Создание и обновление тенантов"),
    permissionModel(defaultTenant, "tenants:delete", "Delete tenants", "Удаление тенантов"),
    permissionModel(defaultTenant, "users:read", "Read users", "Просмотр пользователей", Set("users-list", "users-detail")),
    permissionModel(defaultTenant, "users:read:managed", "Read managed users", "Просмотр управляемых пользователей", Set("users-managed-detail")),
    permissionModel(defaultTenant, "users:write", "Create and update users", "Создание и обновление пользователей", Set("users-create", "users-update")),
    permissionModel(defaultTenant, "users:delete", "Delete users", "Удаление пользователей", Set("users-delete")),
    permissionModel(defaultTenant, "roles:read", "Read roles", "Просмотр ролей", Set("roles-read")),
    permissionModel(defaultTenant, "roles:write", "Create and update roles", "Создание и обновление ролей", Set("roles-create", "roles-update")),
    permissionModel(defaultTenant, "roles:delete", "Delete roles", "Удаление ролей", Set("roles-delete")),
    permissionModel(defaultTenant, "clients:read", "Read OAuth clients", "Просмотр OAuth клиентов", Set("clients-read")),
    permissionModel(defaultTenant, "clients:write", "Create and update OAuth clients", "Создание и обновление OAuth клиентов", Set("clients-create", "clients-update", "clients-rotate-secret")),
    permissionModel(defaultTenant, "clients:delete", "Delete OAuth clients", "Удаление OAuth клиентов", Set("clients-delete", "clients-delete-previous-secret")),
    permissionModel(defaultTenant, "scopes:read", "Read OAuth scopes", "Просмотр OAuth scopes", Set("scopes-read")),
    permissionModel(defaultTenant, "scopes:write", "Create and update OAuth scopes", "Создание и обновление OAuth scopes", Set("scopes-create", "scopes-update")),
    permissionModel(defaultTenant, "scopes:delete", "Delete OAuth scopes", "Удаление OAuth scopes", Set("scopes-delete")),
  )

  val resources: Vector[ResourceModel] = Vector(
    resourceModel(
      defaultTenant,
      "https://api.example.com",
      endpointModel(
        key = "users-list",
        method = "GET",
        path = "/api/users",
        allowRules = Vector(actionRule("read"), clearanceRule(1)),
        injectHeaders = Map(
          "X-User-ID" -> "jwt.sub",
          "X-Clearance-Level" -> "jwt.clearance_level",
        ),
      ),
      endpointModel(
        key = "users-detail",
        method = "GET",
        path = "/api/users/:id",
        allowRules = Vector(actionRule("read"), clearanceRule(1)),
        injectHeaders = Map(
          "X-User-ID" -> "jwt.sub",
          "X-Clearance-Level" -> "jwt.clearance_level",
        ),
      ),
      endpointModel(
        key = "users-managed-detail",
        method = "GET",
        path = "/api/users/:id/managed",
        fetchUserInfo = true,
        allowRules = Vector(
          actionRule("read"),
          rule("userinfo.department", "in", stringArray("engineering", "hr")),
          clearanceRule(2),
        ),
        denyRules = Vector(
          rule("userinfo.status", "eq", Json.Str("suspended")),
        ),
        injectHeaders = Map(
          "X-User-Department" -> "userinfo.department",
          "X-Allowed-User-IDs" -> "authorization_details.allowed_identifiers",
        ),
      ),
      endpointModel(
        key = "users-create",
        method = "POST",
        path = "/api/users",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
        injectHeaders = Map("X-User-ID" -> "jwt.sub"),
      ),
      endpointModel(
      key = "users-update",
        method = "PATCH",
        path = "/api/users/:id",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
        injectHeaders = Map("X-User-ID" -> "jwt.sub"),
      ),
      endpointModel(
      key = "users-delete",
        method = "DELETE",
        path = "/api/users/:id",
        allowRules = Vector(actionRule("delete"), clearanceRule(3)),
      ),
    ),
    resourceModel(
      defaultTenant,
      "https://central.example.com",
      endpointModel(
      key = "roles-read",
        method = "GET",
        path = "/v1/configuration/roles",
        allowRules = Vector(actionRule("read"), clearanceRule(1)),
      ),
      endpointModel(
      key = "roles-create",
        method = "POST",
        path = "/v1/configuration/roles",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "roles-update",
        method = "PUT",
        path = "/v1/configuration/roles",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "roles-delete",
        method = "DELETE",
        path = "/v1/configuration/roles",
        allowRules = Vector(actionRule("delete"), clearanceRule(3)),
      ),
      endpointModel(
      key = "clients-read",
        method = "GET",
        path = "/v1/configuration/clients",
        allowRules = Vector(actionRule("read"), clearanceRule(1)),
      ),
      endpointModel(
      key = "clients-create",
        method = "POST",
        path = "/v1/configuration/clients",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "clients-update",
        method = "PUT",
        path = "/v1/configuration/clients",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "clients-delete",
        method = "DELETE",
        path = "/v1/configuration/clients",
        allowRules = Vector(actionRule("delete"), clearanceRule(3)),
      ),
      endpointModel(
      key = "clients-rotate-secret",
        method = "POST",
        path = "/v1/configuration/clients/rotate-secret",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "clients-delete-previous-secret",
        method = "DELETE",
        path = "/v1/configuration/clients/previous-secret",
        allowRules = Vector(actionRule("delete"), clearanceRule(3)),
      ),
      endpointModel(
      key = "scopes-read",
        method = "GET",
        path = "/v1/configuration/scopes",
        allowRules = Vector(actionRule("read"), clearanceRule(1)),
      ),
      endpointModel(
      key = "scopes-create",
        method = "POST",
        path = "/v1/configuration/scopes",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "scopes-update",
        method = "PUT",
        path = "/v1/configuration/scopes",
        allowRules = Vector(actionRule("update"), clearanceRule(2)),
      ),
      endpointModel(
      key = "scopes-delete",
        method = "DELETE",
        path = "/v1/configuration/scopes",
        allowRules = Vector(actionRule("delete"), clearanceRule(3)),
      ),
    ),
  )

  val scopes: Vector[ScopeModel] = Vector(
    (
      tenantId = defaultTenant,
      id = ScopeToken("openid"),
      description = localized("OpenID Connect authentication", "Аутентификация OpenID Connect"),
      claims = List(createClaim("sub", "Subject identifier", "Идентификатор субъекта")),
    ),
    (
      tenantId = defaultTenant,
      id = ScopeToken("profile"),
      description = localized("User profile data", "Данные профиля пользователя"),
      claims = List(
        createClaim("name", "Full name", "Полное имя"),
        createClaim("given_name", "Given name", "Имя"),
        createClaim("family_name", "Family name", "Фамилия"),
        createClaim("middle_name", "Middle name", "Отчество"),
        createClaim("nickname", "Nickname", "Никнейм"),
        createClaim("preferred_username", "Preferred username", "Предпочитаемое имя пользователя"),
        createClaim("picture", "Profile picture URL", "URL фотографии профиля"),
        createClaim("birthdate", "Birthdate", "Дата рождения"),
        createClaim("gender", "Gender", "Пол"),
      ),
    ),
    (
      tenantId = defaultTenant,
      id = ScopeToken("email"),
      description = localized("Email address", "Адрес электронной почты"),
      claims = List(
        createClaim("email", "Email address", "Адрес электронной почты"),
        createClaim("email_verified", "Email verified flag", "Флаг подтверждения email"),
      ),
    ),
    (
      tenantId = defaultTenant,
      id = ScopeToken("phone"),
      description = localized("Phone number", "Номер телефона"),
      claims = List(
        createClaim("phone_number", "Phone number", "Номер телефона"),
        createClaim("phone_number_verified", "Phone verified flag", "Флаг подтверждения телефона"),
      ),
    ),
    (
      tenantId = defaultTenant,
      id = ScopeToken("offline_access"),
      description = localized("Offline access (refresh tokens)", "Офлайн доступ (refresh токены)"),
      claims = List.empty,
    ),
  )

  val roles: Vector[RoleModel] = Vector(
    (
      tenantId = defaultTenant,
      id = RoleId("admin"),
      description = localized("Full administrative access", "Полный административный доступ"),
      permissions = permissions("users:read", "users:read:managed", "users:write", "users:delete"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("user"),
      description = localized("Standard user access", "Стандартный пользовательский доступ"),
      permissions = permissions("users:read"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("readonly"),
      description = localized("Read-only access", "Доступ только для чтения"),
      permissions = permissions("users:read", "clients:read", "roles:read"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("support"),
      description = localized("Customer support access", "Доступ службы поддержки"),
      permissions = permissions("users:read", "users:write"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("moderator"),
      description = localized("Content moderation access", "Доступ модератора"),
      permissions = permissions("users:read", "users:read:managed", "users:write", "users:delete", "clients:read"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("developer"),
      description = localized("Developer access for testing", "Доступ разработчика для тестирования"),
      permissions = permissions("clients:read", "clients:write", "clients:delete"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("security-auditor"),
      description = localized("Security audit read-only access", "Доступ аудитора безопасности"),
      permissions = permissions("users:read", "clients:read", "roles:read"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("api-manager"),
      description = localized("API and client management", "Управление API и клиентами"),
      permissions = permissions("clients:read", "clients:write", "clients:delete"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("user-manager"),
      description = localized("User management only", "Только управление пользователями"),
      permissions = permissions("users:read", "users:read:managed", "users:write", "users:delete"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("guest"),
      description = localized("Limited guest access", "Ограниченный гостевой доступ"),
      permissions = permissions("users:read"),
      active = true,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("inactive-role"),
      description = localized("Deprecated role", "Устаревшая роль"),
      permissions = List.empty,
      active = false,
    ),
    (
      tenantId = defaultTenant,
      id = RoleId("super-admin"),
      description = localized("Super administrator with all permissions", "Суперадминистратор со всеми правами"),
      permissions = permissions(
        "users:read",
        "users:read:managed",
        "users:write",
        "users:delete",
        "roles:read",
        "roles:write",
        "roles:delete",
        "clients:read",
        "clients:write",
        "clients:delete",
      ),
      active = true,
    ),
  )

  val clients: Vector[ClientModel] = Vector(
    (
      tenantId = defaultTenant,
      id = ClientId("web-app"),
      clientName = "Web Application",
      redirectUris = Set(RedirectUri("https://app.example.com/callback"), RedirectUri("https://app.example.com/silent-renew")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email"), ScopeToken("offline_access")),
      audience = List(ClientId("api.example.com")),
      permissions = Set(Permission("clients:read"), Permission("scopes:read")),
      accessTokenTtl = 3600,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("mobile-app"),
      clientName = "Mobile Application",
      redirectUris = Set(RedirectUri("com.example.app://callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("offline_access")),
      audience = List.empty,
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 7200,
      hasPreviousSecret = true,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("admin-dashboard"),
      clientName = "Admin Dashboard",
      redirectUris = Set(RedirectUri("https://admin.example.com/auth/callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email")),
      audience = List(ClientId("admin-api.example.com")),
      permissions = Set(
        Permission("clients:read"),
        Permission("clients:write"),
        Permission("clients:delete"),
        Permission("scopes:read"),
        Permission("scopes:write"),
        Permission("roles:read"),
        Permission("roles:write"),
      ),
      accessTokenTtl = 1800,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("spa-frontend"),
      clientName = "SPA Frontend",
      redirectUris = Set(RedirectUri("https://spa.example.com/callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email")),
      audience = List.empty,
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 900,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("ios-app"),
      clientName = "iOS Application",
      redirectUris = Set(RedirectUri("com.example.ios://callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("offline_access")),
      audience = List(ClientId("mobile-api.example.com")),
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 7200,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("android-app"),
      clientName = "Android Application",
      redirectUris = Set(RedirectUri("com.example.android://callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("offline_access")),
      audience = List(ClientId("mobile-api.example.com")),
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 7200,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("api-gateway"),
      clientName = "API Gateway",
      redirectUris = Set(RedirectUri("https://gateway.example.com/callback")),
      scope = Set(ScopeToken("openid")),
      audience = List(ClientId("internal-services")),
      permissions = Set(Permission("clients:read")),
      accessTokenTtl = 300,
      hasPreviousSecret = true,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("monitoring-service"),
      clientName = "Monitoring Service",
      redirectUris = Set(RedirectUri("https://monitoring.example.com/callback")),
      scope = Set(ScopeToken("openid")),
      audience = List.empty,
      permissions = Set(Permission("users:read"), Permission("clients:read")),
      accessTokenTtl = 600,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("test-client"),
      clientName = "Test Client",
      redirectUris = Set(RedirectUri("http://localhost:3000/callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile"), ScopeToken("email"), ScopeToken("offline_access")),
      audience = List.empty,
      permissions = Set(Permission("users:read"), Permission("clients:read"), Permission("scopes:read")),
      accessTokenTtl = 3600,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("cli-tool"),
      clientName = "CLI Tool",
      redirectUris = Set(RedirectUri("http://localhost:8080/callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("profile")),
      audience = List(ClientId("api.example.com")),
      permissions = Set(Permission("users:read"), Permission("clients:read")),
      accessTokenTtl = 1800,
      hasPreviousSecret = true,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("third-party-integration"),
      clientName = "Third Party Integration",
      redirectUris = Set(RedirectUri("https://partner.example.com/callback")),
      scope = Set(ScopeToken("openid"), ScopeToken("email")),
      audience = List.empty,
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 3600,
      hasPreviousSecret = false,
    ),
    (
      tenantId = defaultTenant,
      id = ClientId("data-pipeline"),
      clientName = "Data Pipeline Service",
      redirectUris = Set(RedirectUri("https://pipeline.example.com/callback")),
      scope = Set(ScopeToken("openid")),
      audience = List(ClientId("data-warehouse")),
      permissions = Set(Permission("users:read")),
      accessTokenTtl = 600,
      hasPreviousSecret = true,
    ),
  )
