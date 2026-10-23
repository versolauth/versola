package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The antifraud lists' admin API: central names the caller and passes the request to auth, which owns the lists and
  * the rules of who may change what.
  *
  * Central is called directly, so these specs play the part of edge and send the identity it would add from the
  * verified token (`asAdmin`); the injection itself is covered by the unit specs of `BootstrapService`. What is proven
  * here is the round trip through central, auth and Postgres: the status codes, the audit, the tenant limits. What an
  * entry does to a sign-in is not: the lists are off by default (`antifraud.lists.enabled`).
  */
object AntifraudListApiSpec extends CentralApiSpec:

  private val lists = "/antifraud/lists"
  private val operator = "default"

  private def entry(
      valueType: String,
      value: String,
      tenantId: Option[String] = None,
      listType: String = "deny",
      extra: Map[String, Json] = Map.empty,
  ): Json =
    Json.Obj(
      Chunk(
        "listType" -> Json.Str(listType),
        "valueType" -> Json.Str(valueType),
        "value" -> Json.Str(value),
      ) ++ tenantId.map(t => "tenantId" -> Json.Str(t)) ++ extra,
    )

  private def device(prefix: String): UIO[String] = CentralApi.id(prefix)

  /** Creates an entry as an operator, and removes it when `use` is done. */
  private def withEntry[A](central: CentralApi, body: Json)(use: Json.Obj => Task[A]): Task[A] =
    for
      created <- central.asAdmin("e2e-operator", operator).post(lists, body)
      json <- created.obj
      result <- use(json).ensuring(
        ZIO.foreachDiscard(json.str("id"))(id => central.asAdmin("e2e-operator", operator).delete(lists, "id" -> id).ignore),
      )
    yield result

  def spec = suite("Central API: antifraud lists")(
    test("a call that carries no admin identity is refused, and so is one without the central credentials") {
      for
        central <- api
        anonymousAdmin <- central.get(lists, "global" -> "true")
        noCredentials <- central.anonymous.asAdmin("e2e-operator", operator).get(lists)
      yield assertTrue(anonymousAdmin.status == Status.Forbidden, noCredentials.status == Status.Unauthorized)
    },
    test("an operator adds a global entry, lists it, finds it in the audit, and removes it") {
      for
        central <- api
        operator_ = central.asAdmin("e2e-operator", operator)
        value <- device("dev")
        created <- operator_.post(lists, entry("device_id", value, extra = Map("reason" -> Json.Str("e2e"))))
        json <- created.obj
        id = json.str("id").getOrElse("")
        listed <- operator_.get(lists, "global" -> "true", "valueType" -> "device_id", "limit" -> "500")
        listedValues <- listed.objects.map(_.flatMap(_.str("value")).toSet)
        audited <- operator_.get(s"$lists/audit", "entryId" -> id)
        auditRecords <- audited.objects
        removed <- operator_.delete(lists, "id" -> id)
        again <- operator_.delete(lists, "id" -> id)
        afterRemoval <- operator_.get(s"$lists/audit", "entryId" -> id).flatMap(_.objects)
      yield assertTrue(
        created.status == Status.Created,
        json.str("createdBy").contains("e2e-operator"),
        json.str("tenantId").isEmpty,
        listed.status == Status.Ok,
        listedValues.contains(value),
        auditRecords.exists(r => r.str("action").contains("create") && r.str("actor").contains("e2e-operator")),
        removed.status == Status.NoContent,
        again.status == Status.NotFound,
        afterRemoval.map(_.str("action")).toSet.contains(Some("delete")),
      )
    },
    test("an admin of a tenant changes their tenant's entries only, reads no audit, and sees no other tenant's") {
      val tenant = Fixtures.suiteTenant
      for
        central <- api
        admin = central.asAdmin("e2e-tenant-admin", tenant)
        mine <- device("mine")
        theirs <- device("theirs")
        global <- device("global")
        created <- admin.post(lists, entry("device_id", mine, tenantId = Some(tenant)))
        mineId <- created.stringAt("id")
        globalDenied <- admin.post(lists, entry("device_id", global))
        otherDenied <- admin.post(lists, entry("device_id", theirs, tenantId = Some("some-other-tenant")))
        auditDenied <- admin.get(s"$lists/audit")
        previewDenied <- admin.post(s"$lists/preview", entry("device_id", global))
        previewOwn <- admin.post(s"$lists/preview", entry("device_id", mine, tenantId = Some(tenant)))
        other <- withEntry(central, entry("device_id", theirs, tenantId = Some("some-other-tenant"))) { _ =>
          admin.get(lists, "limit" -> "500").flatMap(_.objects).map(_.flatMap(_.str("value")).toSet)
        }
        globalEntry <- withEntry(central, entry("device_id", global)) { g =>
          admin.delete(lists, "id" -> g.str("id").getOrElse("")).map(_.status)
        }
        removed <- admin.delete(lists, "id" -> mineId)
      yield assertTrue(
        created.status == Status.Created,
        globalDenied.status == Status.Forbidden,
        otherDenied.status == Status.Forbidden,
        auditDenied.status == Status.Forbidden,
        previewDenied.status == Status.Forbidden,
        previewOwn.status == Status.Ok,
        !other.contains(theirs),
        other.contains(mine),
        globalEntry == Status.Forbidden,
        removed.status == Status.NoContent,
      )
    },
    test("auth's validation reaches the caller as 400: a value of the wrong shape, an allow for a network, a broad entry with no expiry") {
      for
        central <- api
        operator_ = central.asAdmin("e2e-operator", operator)
        badIp <- operator_.post(lists, entry("ip", "not-an-ip"))
        allowNetwork <- operator_.post(lists, entry("cidr", "10.0.0.0/8", listType = "allow"))
        broadNoExpiry <- operator_.post(lists, entry("prefix", "49"))
        notAnObject <- operator_.raw(Method.POST, lists, "[]")
        unknownType <- operator_.post(lists, entry("nope", "x"))
        badPage <- operator_.get(lists, "limit" -> "0")
        badId <- operator_.delete(lists, "id" -> "nope")
      yield assertTrue(
        badIp.status == Status.BadRequest,
        allowNetwork.status == Status.BadRequest,
        broadNoExpiry.status == Status.BadRequest,
        notAnObject.status == Status.BadRequest,
        unknownType.status == Status.BadRequest,
        badPage.status == Status.BadRequest,
        badId.status == Status.BadRequest,
      )
    },
    test("a phone is stored as its keyed hash, never as the number, and adding it again refreshes the one entry") {
      for
        central <- api
        operator_ = central.asAdmin("e2e-operator", operator)
        phone <- CentralApi.phone
        body = Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("phone_hash"), "phone" -> Json.Str(phone))
        first <- operator_.post(lists, body)
        firstJson <- first.obj
        second <- operator_.post(lists, body)
        secondJson <- second.obj
        _ <- ZIO.foreachDiscard(firstJson.str("id"))(id => operator_.delete(lists, "id" -> id))
        wrongKind <- operator_.post(lists, Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("device_id"), "phone" -> Json.Str(phone)))
      yield assertTrue(
        first.status == Status.Created,
        firstJson.str("value").exists(v => v.nonEmpty && !v.contains(phone.stripPrefix("+"))),
        secondJson.str("id") == firstJson.str("id"),
        secondJson.str("value") == firstJson.str("value"),
        wrongKind.status == Status.BadRequest,
      )
    },
    test("a preview answers the counts without storing anything") {
      for
        central <- api
        operator_ = central.asAdmin("e2e-operator", operator)
        value <- device("preview")
        previewed <- operator_.post(s"$lists/preview", entry("device_id", value))
        counts <- previewed.obj
        listed <- operator_.get(lists, "global" -> "true", "valueType" -> "device_id", "limit" -> "500").flatMap(_.objects)
      yield assertTrue(
        previewed.status == Status.Ok,
        counts.has("total"),
        !listed.exists(_.str("value").contains(value)),
      )
    },
  )
