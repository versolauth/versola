package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.{Method, Status}
import zio.json.ast.Json
import zio.test.*

/** The antifraud lists' admin API: central passes the request to auth, which owns the lists and validates the entries.
  *
  * Central is called directly, so these specs play the part of edge and send the `userId` it would add from the verified
  * token (`asAdmin`: a query parameter of a `GET`, a body field of the rest); the injection itself is covered by the unit
  * specs of `BootstrapService`. What is proven here is the round trip through central, auth and Postgres: the status codes and the audit. What an
  * entry does to a sign-in is not: the lists are off by default (`antifraud.lists.enabled`).
  */
object AntifraudListApiSpec extends CentralApiSpec:

  private val lists = "/antifraud/lists"

  /** The user id edge would add from the token. */
  private val adminId = "00000000-0000-0000-0000-0000000e2e01"

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

  /** An address of its own for the entry: an exact kind every request carries, unlike a device id (refused until
    * P1-1 issues one). */
  private def address: UIO[String] =
    Random.nextLongBounded(1L << 24).map(bits => s"10.${(bits >>> 16) & 0xff}.${(bits >>> 8) & 0xff}.${(bits & 0xff).max(3)}")

  /** Creates an entry, and removes it when `use` is done. */
  private def withEntry[A](central: CentralApi, body: Json)(use: Json.Obj => Task[A]): Task[A] =
    for
      created <- central.asAdmin(adminId).post(lists, body)
      json <- created.obj
      result <- use(json).ensuring(
        ZIO.foreachDiscard(json.str("id"))(id =>
          central.asAdmin(adminId).deleteWithBody(lists, Json.Obj("id" -> Json.Str(id))).ignore,
        ),
      )
    yield result

  def spec = suite("Central API: antifraud lists")(
    test("a call that carries no user id is a bad request, and one without the central credentials is 401") {
      for
        central <- api
        anonymousAdmin <- central.get(lists, "global" -> "true")
        noCredentials <- central.anonymous.asAdmin(adminId).get(lists)
      yield assertTrue(anonymousAdmin.status == Status.BadRequest, noCredentials.status == Status.Unauthorized)
    },
    test("an admin adds a global entry, lists it, finds it in the audit, and removes it") {
      for
        central <- api
        admin_ = central.asAdmin(adminId)
        value <- address
        created <- admin_.post(lists, entry("ip", value, extra = Map("reason" -> Json.Str("e2e"))))
        json <- created.obj
        id = json.str("id").getOrElse("")
        listed <- admin_.get(lists, "global" -> "true", "valueType" -> "ip", "limit" -> "500")
        listedValues <- listed.items("entries").map(_.flatMap(_.str("value")).toSet)
        audited <- admin_.get(s"$lists/audit", "entryId" -> id)
        auditRecords <- audited.items("records")
        removed <- admin_.deleteWithBody(lists, Json.Obj("id" -> Json.Str(id)))
        again <- admin_.deleteWithBody(lists, Json.Obj("id" -> Json.Str(id)))
        afterRemoval <- admin_.get(s"$lists/audit", "entryId" -> id).flatMap(_.items("records"))
      yield assertTrue(
        created.status == Status.Created,
        json.str("createdBy").contains(adminId),
        json.str("tenantId").isEmpty,
        listed.status == Status.Ok,
        listedValues.contains(value),
        auditRecords.exists(r => r.str("action").contains("create") && r.str("actor").contains(adminId)),
        removed.status == Status.NoContent,
        again.status == Status.NotFound,
        afterRemoval.map(_.str("action")).toSet.contains(Some("delete")),
      )
    },
    test("auth's validation reaches the caller as 400: a value of the wrong shape, an allow for a network, a broad entry with no expiry") {
      for
        central <- api
        admin_ = central.asAdmin(adminId)
        badIp <- admin_.post(lists, entry("ip", "not-an-ip"))
        allowNetwork <- admin_.post(lists, entry("cidr", "10.0.0.0/8", listType = "allow"))
        broadNoExpiry <- admin_.post(lists, entry("prefix", "49"))
        notAnObject <- admin_.raw(Method.POST, lists, "[]")
        unknownType <- admin_.post(lists, entry("nope", "x"))
        badPage <- admin_.get(lists, "limit" -> "0")
        badId <- admin_.deleteWithBody(lists, Json.Obj("id" -> Json.Str("nope")))
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
        admin_ = central.asAdmin(adminId)
        phone <- CentralApi.phone
        body = Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("phone_hash"), "phone" -> Json.Str(phone))
        first <- admin_.post(lists, body)
        firstJson <- first.obj
        second <- admin_.post(lists, body)
        secondJson <- second.obj
        _ <- ZIO.foreachDiscard(firstJson.str("id"))(id => admin_.deleteWithBody(lists, Json.Obj("id" -> Json.Str(id))))
        wrongKind <- admin_.post(lists, Json.Obj("listType" -> Json.Str("deny"), "valueType" -> Json.Str("ip"), "phone" -> Json.Str(phone)))
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
        admin_ = central.asAdmin(adminId)
        value <- address
        previewed <- admin_.post(s"$lists/preview", entry("ip", value))
        counts <- previewed.obj
        listed <- admin_.get(lists, "global" -> "true", "valueType" -> "ip", "limit" -> "500").flatMap(_.items("entries"))
      yield assertTrue(
        previewed.status == Status.Ok,
        counts.has("total"),
        !listed.exists(_.str("value").contains(value)),
      )
    },
  )
