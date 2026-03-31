package versola.central.configuration.scopes

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.{CreateClaim, PatchClaim, PatchDescription, PatchScope}
import versola.central.configuration.tenants.TenantId
import versola.util.DatabaseSpecBase
import zio.prelude.EqualOps
import zio.test.*

trait OAuthScopeRepositorySpec extends DatabaseSpecBase[OAuthScopeRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  val tenantId = TenantId("tenant-a")
  val scopeId = ScopeToken("profile")
  val nameClaim = Claim("name")
  val emailClaim = Claim("email")
  val usernameClaim = Claim("preferred_username")

  override def testCases(env: OAuthScopeRepositorySpec.Env) =
    List(
      test("create scope and persist claims") {
        val claims = List(
          CreateClaim(nameClaim, Map("en" -> "Name")),
          CreateClaim(emailClaim, Map("en" -> "Email")),
        )

        for
          _ <- env.repository.createScope(tenantId, scopeId, Map("en" -> "Profile"), claims)
          found <- env.repository.findScope(tenantId, scopeId)
        yield assertTrue(
          found === Some(
            ScopeRecord(
              tenantId = tenantId,
              id = scopeId,
              description = Map("en" -> "Profile"),
              claims = Vector(
                ClaimRecord(nameClaim, Map("en" -> "Name")),
                ClaimRecord(emailClaim, Map("en" -> "Email")),
              ),
            )
          )
        )
      },
      test("update scope should add, update and delete claims") {
        val initialClaims = List(
          CreateClaim(nameClaim, Map("en" -> "Name")),
          CreateClaim(emailClaim, Map("en" -> "Email")),
        )
        val patch = PatchScope(
          add = List(CreateClaim(usernameClaim, Map("en" -> "Username"))),
          update = List(
            PatchClaim(
              nameClaim,
              PatchDescription(
                add = Map("ru" -> "Имя"),
                delete = Set.empty,
              ),
            )
          ),
          delete = Set(emailClaim),
          description = PatchDescription(
            add = Map("ru" -> "Профиль"),
            delete = Set.empty,
          ),
        )

        for
          _ <- env.repository.createScope(tenantId, scopeId, Map("en" -> "Profile"), initialClaims)
          _ <- env.repository.updateScope(tenantId, scopeId, patch)
          found <- env.repository.findScope(tenantId, scopeId)
        yield assertTrue(
          found === Some(
            ScopeRecord(
              tenantId = tenantId,
              id = scopeId,
              description = Map("en" -> "Profile", "ru" -> "Профиль"),
              claims = Vector(
                ClaimRecord(nameClaim, Map("en" -> "Name", "ru" -> "Имя")),
                ClaimRecord(usernameClaim, Map("en" -> "Username")),
              ),
            )
          )
        )
      },
      test("delete scope") {
        for
          _ <- env.repository.createScope(tenantId, scopeId, Map("en" -> "Profile"), Nil)
          _ <- env.repository.deleteScope(tenantId, scopeId)
          found <- env.repository.findScope(tenantId, scopeId)
        yield assertTrue(found.isEmpty)
      },
    )

object OAuthScopeRepositorySpec:
  case class Env(repository: OAuthScopeRepository)