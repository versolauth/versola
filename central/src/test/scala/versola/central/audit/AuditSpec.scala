package versola.central.audit

import versola.central.configuration.clients.{ClientId, UpdateWebClientRequest}
import versola.central.configuration.tenants.TenantId
import versola.central.users.UserId
import versola.util.Patch
import versola.util.audit.AuditContext
import zio.*
import zio.json.*
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

import java.util.UUID

/** What a handler tells the log with [[Audit]]: the target by its typed id, and the names of the fields of a request that it sets. */
object AuditSpec extends ZIOSpecDefault:

  private case class Sample(
      clientId: ClientId,
      tenantId: TenantId,
      clientName: Option[String],
      accessTokenTtl: Option[Long],
      theme: Option[Patch[String]],
      note: String,
  )
  private object Sample:
    given Schema[Sample] = DeriveSchema.gen[Sample]

  private val sample = Sample(
    clientId = ClientId("c1"),
    tenantId = TenantId("t1"),
    clientName = None,
    accessTokenTtl = Some(600),
    theme = Some(Patch.Deleted),
    note = "a value that must not be written",
  )

  private def changedBy(effect: UIO[Unit]) = AuditContext.reset(AuditContext.State.empty)(effect *> AuditContext.current.map(_.changed))

  def spec = suite("Audit")(
    test("changed lists the fields a request sets, by name, without the ones that name what is updated, the ones left out and any value") {
      for names <- changedBy(Audit.changed(sample, "clientId", "tenantId"))
      yield assertTrue(names == Set("accessTokenTtl", "theme", "note"), !names.exists(_.contains("must not")))
    },
    test("a patch that deletes is a field set, and an optional one that is left out is not") {
      for
        deleted <- changedBy(Audit.changed(sample.copy(accessTokenTtl = None, note = "n"), "clientId", "tenantId", "note"))
        none <- changedBy(Audit.changed(sample.copy(accessTokenTtl = None, theme = None), "clientId", "tenantId", "note"))
      yield assertTrue(deleted == Set("theme"), none.isEmpty)
    },
    test("replaced lists every field but the ones that name the record: a field set to nothing is one too") {
      for names <- changedBy(Audit.replaced(sample, "clientId", "tenantId"))
      yield assertTrue(names == Set("clientName", "accessTokenTtl", "theme", "note"))
    },
    test("a request with many fields (a Transform of a generic record) is read the same way") {
      val update =
        """{"clientId":"c1","redirectUris":{"add":[],"remove":[]},"scope":{"add":[],"remove":[]},"accessTokenTtl":600,"logoUri":null}""".fromJson[
          UpdateWebClientRequest,
        ]
      for names <- changedBy(ZIO.fromEither(update).mapError(RuntimeException(_)).orDie.flatMap(Audit.changed(_, "clientId")))
      // the patches of required members (`redirectUris`, `scope`) are in every request, as they are in its JSON
      yield assertTrue(names == Set("redirectUris", "scope", "accessTokenTtl", "logoUri"))
    },
    test("a name that is no field of the request is a defect: a renamed field fails loudly and not silently") {
      for exit <- changedBy(Audit.changed(sample, "client")).exit
      yield assertTrue(exit.isFailure)
    },
    test("the target is given as a typed id, and a user is also the user of the row") {
      val user = UserId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
      for
        client <- AuditContext.reset(AuditContext.State.empty)(Audit.target(ClientId("c1")) *> AuditContext.current)
        person <- AuditContext.reset(AuditContext.State.empty)(Audit.target(user) *> AuditContext.current)
      yield assertTrue(
        client.target.map(_._2) == Some("c1"),
        client.userId.isEmpty,
        person.target.map(_._2) == Some(user.toString),
        person.userId.contains(user),
      )
    },
    test("a bare String is not a target: it has to be a typed id, or named as text") {
      for errors <- typeCheck("""Audit.target("a bare string")""")
      yield assertTrue(errors.isLeft)
    },
  )
