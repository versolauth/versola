package versola.central.configuration

import versola.central.configuration.clients.{AuthFlow, ClientId, ConsentFlow}
import versola.central.configuration.permissions.Permission
import versola.central.configuration.scopes.ScopeToken
import versola.util.Patch
import zio.json.*
import zio.test.*

object dtoSpec extends ZIOSpecDefault:

  /** Decodes an UpdateClientRequest carrying only the required fields plus the given raw field JSON. */
  private def decodeUpdate(field: String): Either[String, UpdateClientRequest] =
    val required =
      """"clientId":"web-app",""" +
        """"redirectUris":{"add":[],"remove":[]},""" +
        """"scope":{"add":[],"remove":[]},""" +
        """"permissions":{"add":[],"remove":[]}"""
    s"""{$required$field}""".fromJson[UpdateClientRequest]

  private def createPermission(id: String): Either[String, CreatePermissionRequest] =
    s"""{"tenantId":"t","permission":"$id","description":{},"endpointIds":[]}""".fromJson[CreatePermissionRequest]

  def spec = suite("DTO JSON Codecs")(
    suite("permission ID rule")(
      test("colon-separated IDs, with hyphens and underscores, are accepted") {
        assertTrue(
          createPermission("users:read").map(_.permission) == Right(Permission("users:read")),
          createPermission("billing:invoice:read_all").isRight,
          createPermission("auth-settings:manage").isRight,
          createPermission("admin").isRight,
        )
      },
      test("create refuses an ID with a dot, naming the rule") {
        val result = createPermission("orders.read_all")
        assertTrue(result.isLeft, result.left.exists(_.contains("separated by ':'")))
      },
      test("create refuses an ID outside the alphabet") {
        assertTrue(
          createPermission("Users:read").isLeft,
          createPermission("users:1read").isLeft,
          createPermission("-users:read").isLeft,
          createPermission("users:").isLeft,
          createPermission(":read").isLeft,
          createPermission("users::read").isLeft,
          createPermission("").isLeft,
        )
      },
      test("update refuses an ID with a dot and accepts a colon one") {
        def update(id: String) =
          s"""{"tenantId":"t","permission":"$id","description":{"add":{},"delete":[]},"endpointIds":null}""".fromJson[UpdatePermissionRequest]
        assertTrue(update("legacy.read").isLeft, update("legacy:read").map(_.permission) == Right(Permission("legacy:read")))
      },
      test("a role refuses a dotted permission reference on create and on patch") {
        def create(id: String) =
          s"""{"tenantId":"t","id":"r","description":{},"permissions":["$id"]}""".fromJson[CreateRoleRequest]
        def patch(id: String) =
          s"""{"tenantId":"t","id":"r","description":{"add":{},"delete":[]},"permissions":{"add":["$id"],"remove":[]}}"""
            .fromJson[UpdateRoleRequest]
        def remove(id: String) =
          s"""{"tenantId":"t","id":"r","description":{"add":{},"delete":[]},"permissions":{"add":[],"remove":["$id"]}}"""
            .fromJson[UpdateRoleRequest]
        assertTrue(
          create("users.read").isLeft,
          create("users:read").isRight,
          patch("users.read").isLeft,
          patch("users:read").isRight,
          remove("users.read").isLeft,
        )
      },
      test("a client refuses a dotted permission reference in a patch") {
        def patch(id: String) =
          decodeUpdate("").flatMap(_ =>
            ("""{"clientId":"web-app","redirectUris":{"add":[],"remove":[]},"scope":{"add":[],"remove":[]},""" +
              s""""permissions":{"add":["$id"],"remove":[]}}""").fromJson[UpdateClientRequest],
          )
        assertTrue(patch("orders.read").isLeft, patch("orders:read").isRight)
      },
    ),
    suite("UpdateClientRequest nullable fields distinguish absent from null")(
      test("an absent auth flow means no change, an explicit null clears it") {
        val absent = decodeUpdate("")
        val cleared = decodeUpdate(""","authFlow":null""")
        val set = decodeUpdate(s""","authFlow":${AuthFlow.default.toJson}""")
        assertTrue(
          absent.map(_.authFlow) == Right(None),
          cleared.map(_.authFlow) == Right(Some(Patch.Deleted)),
          set.map(_.authFlow) == Right(Some(Patch.Modified(AuthFlow.default))),
        )
      },
      test("a finite consent duration is decoded from seconds") {
        val decoded = decodeUpdate(
          ""","consentFlow":{"allowPartial":true,"rememberDuration":1209600}""",
        )
        assertTrue(
          decoded.map(_.consentFlow) == Right(
            Some(Patch.Modified(ConsentFlowDto(allowPartial = true, rememberDuration = Some(1209600L)))),
          ),
        )
      },
      test("an absent logout URI means no change, an explicit null clears it") {
        val absent = decodeUpdate("")
        val cleared = decodeUpdate(""","frontChannelLogoutUri":null""")
        val set = decodeUpdate(""","frontChannelLogoutUri":"https://example.com/logout"""")
        assertTrue(
          absent.map(_.frontChannelLogoutUri) == Right(None),
          cleared.map(_.frontChannelLogoutUri) == Right(Some(Patch.Deleted)),
          set.map(_.frontChannelLogoutUri) == Right(Some(Patch.Modified("https://example.com/logout"))),
        )
      },
    ),
    test("ConsentFlowDto encodes a finite duration as seconds") {
      val json = ConsentFlowDto(allowPartial = false, rememberDuration = Some(1209600L)).toJson
      assertTrue(
        json.contains("\"rememberDuration\":1209600"),
        !json.contains("PT"),
      )
    },
    test("ConsentFlowDto carries required scopes and reads a record stored without them as empty") {
      val dto = ConsentFlowDto(allowPartial = true, rememberDuration = None, requiredScopes = Set(ScopeToken("phone")))
      val legacy = """{"allowPartial":true,"rememberDuration":1209600}"""
      assertTrue(
        dto.toJson.contains("\"requiredScopes\":[\"phone\"]"),
        dto.toJson.fromJson[ConsentFlowDto] == Right(dto),
        legacy.fromJson[ConsentFlowDto].map(_.requiredScopes) == Right(Set.empty[ScopeToken]),
        legacy.fromJson[ConsentFlowDto].map(_.offlineAccessOptional) == Right(false),
        dto.copy(offlineAccessOptional = true).toJson.fromJson[ConsentFlowDto].map(_.offlineAccessOptional) == Right(true),
        """{"allowPartial":true,"rememberDuration":null}""".fromJson[ConsentFlow].map(_.requiredScopes) == Right(Set.empty[ScopeToken]),
      )
    },
    test("PatchDescription encodes and decodes correctly") {
      val desc = PatchDescription(add = Map("en" -> "Hello"), delete = Set("fr"))
      val json = desc.toJson
      assertTrue(json.fromJson[PatchDescription] == Right(desc))
    },
    test("PatchDescription.patch applies add and delete") {
      val existing = Map("en" -> "Hello", "fr" -> "Bonjour")
      val patch = PatchDescription(add = Map("de" -> "Hallo"), delete = Set("fr"))
      assertTrue(patch.patch(existing) == Map("en" -> "Hello", "de" -> "Hallo"))
    },
    test("ResourceUri.apply creates URI directly") {
      val uri = ResourceUri("https://example.com")
      assertTrue((uri: String) == "https://example.com")
    },
    test("ResourceUri.parse accepts valid absolute URI without path") {
      assertTrue(ResourceUri.parse("https://example.com").isRight)
    },
    test("ResourceUri.parse rejects URI with path") {
      assertTrue(ResourceUri.parse("https://example.com/api").isLeft)
    },
    test("ResourceUri JSON round-trip") {
      val uri = ResourceUri("https://example.com")
      val json = uri.toJson
      assertTrue(json.fromJson[ResourceUri] == Right(uri))
    },
  )
