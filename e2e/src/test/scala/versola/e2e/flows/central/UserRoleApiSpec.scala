package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** Role assignments, sessions and rate-limit counters for a user.
  *
  * These are the endpoints an operator reaches for during an incident: grant a role, kick every
  * session, clear a lockout. All three answer `202`/`204` and settle asynchronously, so the
  * assertions poll rather than read once.
  */
object UserRoleApiSpec extends CentralApiSpec:

  private val roles = "/users/roles"
  private val sessions = "/users/sessions"
  private val limits = "/users/limits/reset"

  private def assigned(central: CentralApi, userId: String, tenantId: String = Fixtures.defaultTenant): Task[Set[String]] =
    central.get(roles, "id" -> userId, "tenantId" -> tenantId).flatMap(_.obj).map(_.strings("roles"))

  private def eventuallyAssigned(
      central: CentralApi,
      userId: String,
      tenantId: String = Fixtures.defaultTenant,
  )(condition: Set[String] => Boolean): Task[Set[String]] =
    assigned(central, userId, tenantId)
      .repeat(Schedule.spaced(100.millis) *> Schedule.recurUntil[Set[String]](condition))
      .timeout(10.seconds)
      .map(_.getOrElse(Set.empty))

  private def patch(
      central: CentralApi,
      userId: String,
      add: Set[String] = Set.empty,
      remove: Set[String] = Set.empty,
      tenantId: String = Fixtures.defaultTenant,
  ): Task[ApiResult] =
    central.patch(
      roles,
      Json.Obj(
        "userId" -> Json.Str(userId),
        "tenantId" -> Json.Str(tenantId),
        "add" -> Json.Arr(Chunk.fromIterable(add.map(Json.Str(_)))),
        "remove" -> Json.Arr(Chunk.fromIterable(remove.map(Json.Str(_)))),
      ),
    )

  private def user(central: CentralApi): Task[String] =
    CentralApi.login("probe").flatMap(login => central.post("/users", Fixtures.user(login = Some(login))))
      .flatMap(_.stringAt("id"))

  /** A role that exists for the duration of the test body. */
  private def withRole[A](central: CentralApi)(use: String => Task[A]): Task[A] =
    for
      roleId <- CentralApi.id("e2e-role")
      _ <- central.post("/configuration/roles", Fixtures.role(roleId))
      result <- use(roleId).ensuring(
        central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> roleId).ignore,
      )
    yield result

  def spec = suite("Central API: user roles, sessions and limits")(
    test("a new user holds no roles") {
      for
        central <- api
        userId <- user(central)
        held <- assigned(central, userId)
      yield assertTrue(held.isEmpty)
    },
    test("a granted role shows up on the user") {
      for
        central <- api
        userId <- user(central)
        held <- withRole(central) { roleId =>
          patch(central, userId, add = Set(roleId)) *> eventuallyAssigned(central, userId)(_.contains(roleId))
            .map(_ -> roleId)
        }
      yield assertTrue(held._1 == Set(held._2))
    },
    test("granting a role answers before the grant has landed") {
      for
        central <- api
        userId <- user(central)
        accepted <- withRole(central)(roleId => patch(central, userId, add = Set(roleId)))
      yield assertTrue(accepted.status == Status.Accepted)
        .label("202, not 200: the console must not render the grant as already effective")
    },
    test("two grants accumulate rather than replace") {
      for
        central <- api
        userId <- user(central)
        first <- CentralApi.id("e2e-role")
        second <- CentralApi.id("e2e-role")
        _ <- central.post("/configuration/roles", Fixtures.role(first))
        _ <- central.post("/configuration/roles", Fixtures.role(second))
        _ <- patch(central, userId, add = Set(first))
        _ <- eventuallyAssigned(central, userId)(_.contains(first))
        _ <- patch(central, userId, add = Set(second))
        held <- eventuallyAssigned(central, userId)(_.contains(second))
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> first)
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> second)
      yield assertTrue(held == Set(first, second))
        .label("granting a second role must not quietly revoke the first")
    },
    test("several roles can be granted in one call") {
      for
        central <- api
        userId <- user(central)
        first <- CentralApi.id("e2e-role")
        second <- CentralApi.id("e2e-role")
        _ <- central.post("/configuration/roles", Fixtures.role(first))
        _ <- central.post("/configuration/roles", Fixtures.role(second))
        _ <- patch(central, userId, add = Set(first, second))
        held <- eventuallyAssigned(central, userId)(_ == Set(first, second))
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> first)
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> second)
      yield assertTrue(held == Set(first, second))
    },
    test("a revoked role stops showing up on the user") {
      for
        central <- api
        userId <- user(central)
        held <- withRole(central) { roleId =>
          patch(central, userId, add = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.contains(roleId))
            *> patch(central, userId, remove = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.isEmpty)
        }
      yield assertTrue(held.isEmpty)
        .label("revocation is the direction that matters when an employee leaves")
    },
    test("one call grants one role and revokes another") {
      for
        central <- api
        userId <- user(central)
        before <- CentralApi.id("e2e-role")
        after <- CentralApi.id("e2e-role")
        _ <- central.post("/configuration/roles", Fixtures.role(before))
        _ <- central.post("/configuration/roles", Fixtures.role(after))
        _ <- patch(central, userId, add = Set(before))
        _ <- eventuallyAssigned(central, userId)(_.contains(before))
        _ <- patch(central, userId, add = Set(after), remove = Set(before))
        held <- eventuallyAssigned(central, userId)(_ == Set(after))
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> before)
        _ <- central.delete("/configuration/roles", "tenantId" -> Fixtures.defaultTenant, "roleId" -> after)
      yield assertTrue(held == Set(after))
    },
    test("granting a role the user already holds changes nothing") {
      for
        central <- api
        userId <- user(central)
        held <- withRole(central) { roleId =>
          patch(central, userId, add = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.contains(roleId))
            *> patch(central, userId, add = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.contains(roleId))
        }
      yield assertTrue(held.size == 1)
        .label("a desired-state apply repeats every grant it already made")
    },
    test("revoking a role the user never held is not an error") {
      for
        central <- api
        userId <- user(central)
        accepted <- patch(central, userId, remove = Set("e2e-never-granted"))
        held <- assigned(central, userId).delay(1.second)
      yield assertTrue(accepted.status == Status.Accepted) && assertTrue(held.isEmpty)
    },
    test("a patch with both lists empty is accepted and changes nothing") {
      for
        central <- api
        userId <- user(central)
        held <- withRole(central) { roleId =>
          patch(central, userId, add = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.contains(roleId))
            *> patch(central, userId)
            *> assigned(central, userId).delay(1.second)
        }
      yield assertTrue(held.size == 1)
    },
    test("a patch that omits the required lists is refused") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.patch(
          roles,
          Json.Obj("userId" -> Json.Str(userId), "tenantId" -> Json.Str(Fixtures.defaultTenant)),
        )
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("a patch without a tenant is refused") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.patch(
          roles,
          Json.Obj("userId" -> Json.Str(userId), "add" -> Json.Arr(), "remove" -> Json.Arr()),
        )
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
          .label("an assignment with no tenant would be ambiguous across every tenant the user exists in")
    },
    test("reading roles without a tenant is refused") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.get(roles, "id" -> userId)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("tenantId"))
    },
    test("reading roles without a user is refused") {
      for
        central <- api
        rejected <- central.get(roles, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("id"))
    },
    test("a role granted in one tenant is not held in another") {
      for
        central <- api
        userId <- user(central)
        tenantId <- CentralApi.id("e2e-tenant")
        _ <- central.post("/configuration/tenants", Fixtures.tenant(tenantId))
        outcome <- withRole(central) { roleId =>
          patch(central, userId, add = Set(roleId))
            *> eventuallyAssigned(central, userId)(_.contains(roleId))
            *> assigned(central, userId, tenantId)
        }
        _ <- central.delete("/configuration/tenants", "tenantId" -> tenantId)
      yield assertTrue(outcome.isEmpty)
        .label("one identity across tenants, but the access it carries has to stop at the tenant boundary")
    },
    test("a user that does not exist holds no roles") {
      for
        central <- api
        id <- CentralApi.uuid
        held <- assigned(central, id.toString)
      yield assertTrue(held.isEmpty)
        .label("an empty answer rather than a 500: the caller may be probing a stale id")
    },
    test("a user with no sessions reports an empty list") {
      for
        central <- api
        userId <- user(central)
        listed <- central.get(sessions, "id" -> userId).flatMap(_.array)
      yield assertTrue(listed.isEmpty)
    },
    test("reading sessions of a user that does not exist reports an empty list") {
      for
        central <- api
        id <- CentralApi.uuid
        listed <- central.get(sessions, "id" -> id.toString).flatMap(_.array)
      yield assertTrue(listed.isEmpty)
    },
    test("reading sessions without a user is refused") {
      for
        central <- api
        rejected <- central.get(sessions)
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("invalidating the sessions of a user with none is not an error") {
      for
        central <- api
        userId <- user(central)
        invalidated <- central.delete(sessions, "userId" -> userId)
      yield assertTrue(invalidated.status == Status.NoContent)
        .label("an operator kicking a possibly-idle account must not have to check first")
    },
    test("invalidating sessions without a user is refused") {
      for
        central <- api
        rejected <- central.delete(sessions)
      yield assertTrue(rejected.status == Status.BadRequest) &&
        assertTrue(rejected.body.contains("userId"))
    },
    test("resetting the limit counters of a user is accepted") {
      for
        central <- api
        userId <- user(central)
        reset <- central.post(
          limits,
          Json.Obj("userId" -> Json.Str(userId), "tenantId" -> Json.Str(Fixtures.defaultTenant)),
        )
      yield assertTrue(reset.status == Status.Accepted)
    },
    test("resetting limits for a credential the user does not hold is accepted") {
      for
        central <- api
        userId <- user(central)
        email <- CentralApi.email("probe-unrelated")
        reset <- central.post(
          limits,
          Json.Obj(
            "userId" -> Json.Str(userId),
            "tenantId" -> Json.Str(Fixtures.defaultTenant),
            "email" -> Json.Str(email),
          ),
        )
      yield assertTrue(reset.status == Status.Accepted)
        .label("counters are keyed by credential, and an operator clearing a lockout may guess the wrong one")
    },
    test("resetting limits without a tenant is refused") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.post(limits, Json.Obj("userId" -> Json.Str(userId)))
      yield assertTrue(rejected.status == Status.BadRequest)
    },
    test("an anonymous caller cannot read a user's roles") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.anonymous.get(roles, "id" -> userId, "tenantId" -> Fixtures.defaultTenant)
      yield assertTrue(rejected.status == Status.Unauthorized)
    },
    test("an anonymous caller cannot grant a role") {
      for
        central <- api
        userId <- user(central)
        outcome <- withRole(central) { roleId =>
          central.anonymous
            .patch(
              roles,
              Json.Obj(
                "userId" -> Json.Str(userId),
                "tenantId" -> Json.Str(Fixtures.defaultTenant),
                "add" -> Json.Arr(Chunk(Json.Str(roleId))),
                "remove" -> Json.Arr(),
              ),
            )
            .zip(assigned(central, userId).delay(1.second))
        }
        (rejected, held) = outcome
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(held.isEmpty)
    },
    test("an anonymous caller cannot invalidate a user's sessions") {
      for
        central <- api
        userId <- user(central)
        rejected <- central.anonymous.delete(sessions, "userId" -> userId)
      yield assertTrue(rejected.status == Status.Unauthorized)
        .label("otherwise anyone knowing a user id could log that user out at will")
    },
    test("a caller presenting the wrong secret cannot grant a role") {
      for
        central <- api
        userId <- user(central)
        outcome <- withRole(central) { roleId =>
          central.withCredentials("central", "d3Jvbmctc2VjcmV0LWZvci1lMmUtdGVzdHM")
            .patch(
              roles,
              Json.Obj(
                "userId" -> Json.Str(userId),
                "tenantId" -> Json.Str(Fixtures.defaultTenant),
                "add" -> Json.Arr(Chunk(Json.Str(roleId))),
                "remove" -> Json.Arr(),
              ),
            )
            .zip(assigned(central, userId).delay(1.second))
        }
        (rejected, held) = outcome
      yield assertTrue(rejected.status == Status.Unauthorized) && assertTrue(held.isEmpty)
          .label("self-granting a role is the shortest path from a leaked console to full access")
    },
  ) @@ TestAspect.sequential @@ TestAspect.timeout(180.seconds)
