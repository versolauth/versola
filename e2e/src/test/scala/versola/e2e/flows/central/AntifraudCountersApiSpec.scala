package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*

/** The counters reset, through central to auth and Postgres: the operator's call (`antifraud:operate`, which edge checks and the
  * unit specs of `BootstrapService` cover) answers 202 with the generation auth gave it, and what auth refuses reaches the caller
  * as 400.
  *
  * Every reset here is of one value no traffic of the other specs uses (an address of a network of its own), so that the
  * instances `AntifraudOtpSpec` runs on, which clear their counters when they read a reset, lose nothing a case of theirs counts.
  */
object AntifraudCountersApiSpec extends CentralApiSpec:

  private val reset = "/antifraud/counters/reset"
  private val adminId = "00000000-0000-0000-0000-0000000e2e07"

  private def body(fields: (String, String)*) = Json.Obj(Chunk.from(fields.map((name, value) => name -> Json.Str(value))))

  def spec = suite("Central API: antifraud counters reset")(
    test("a reset of one value is accepted with its generation, and the next has a later one") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        first <- operator.post(reset, body("scope" -> "velocity", "valueType" -> "ip", "value" -> "10.250.250.1"))
        firstJson <- first.obj
        second <- operator.post(reset, body("scope" -> "velocity", "valueType" -> "ip", "value" -> "10.250.250.2"))
        secondJson <- second.obj
      yield assertTrue(
        first.status == Status.Accepted,
        second.status == Status.Accepted,
        firstJson.num("generation").exists(_ > 0),
        secondJson.num("generation").exists(g => firstJson.num("generation").exists(_ < g)),
      )
    },
    test("a phone is reset in the velocity counters as the number, and a prefix in the budget") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        phone <- CentralApi.phone
        velocity <- operator.post(reset, body("scope" -> "velocity", "valueType" -> "phone", "value" -> phone))
        budget <- operator.post(reset, body("scope" -> "budget", "valueType" -> "prefix", "value" -> "490000"))
      yield assertTrue(velocity.status == Status.Accepted, budget.status == Status.Accepted)
    },
    test("what auth refuses is 400: a phone has no budget of its own, an address none either, a value with no kind, a bad number") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        phone <- CentralApi.phone
        budgetOfPhone <- operator.post(reset, body("scope" -> "budget", "valueType" -> "phone", "value" -> phone))
        budgetOfIp <- operator.post(reset, body("scope" -> "budget", "valueType" -> "ip", "value" -> "10.250.250.3"))
        noKind <- operator.post(reset, body("scope" -> "velocity", "value" -> "10.250.250.4"))
        badPhone <- operator.post(reset, body("scope" -> "velocity", "valueType" -> "phone", "value" -> "12"))
      yield assertTrue(
        budgetOfPhone.status == Status.BadRequest,
        budgetOfIp.status == Status.BadRequest,
        noKind.status == Status.BadRequest,
        badPhone.status == Status.BadRequest,
        budgetOfPhone.body.nonEmpty,
      )
    },
    test("a body that is not a reset, or has no admin, is 400, and a call without the central credentials is 401") {
      for
        central <- api
        operator = central.asAdmin(adminId)
        unknownScope <- operator.post(reset, body("scope" -> "everything"))
        unknownKind <- operator.post(reset, body("scope" -> "velocity", "valueType" -> "asn", "value" -> "64500"))
        noScope <- operator.post(reset, Json.Obj())
        noUser <- central.post(reset, body("scope" -> "velocity", "valueType" -> "ip", "value" -> "10.250.250.5"))
        anonymous <- central.anonymous.asAdmin(adminId).post(reset, body("scope" -> "all"))
      yield assertTrue(
        unknownScope.status == Status.BadRequest,
        unknownKind.status == Status.BadRequest,
        noScope.status == Status.BadRequest,
        noUser.status == Status.BadRequest,
        anonymous.status == Status.Unauthorized,
      )
    },
  )
