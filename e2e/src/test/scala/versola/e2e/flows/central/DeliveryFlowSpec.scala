package versola.e2e.flows.central

import versola.e2e.support.{*, given}
import zio.*
import zio.http.Status
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.sequential

/** Delivery settings saved through central's API (#832): what is stored and what the admin API gives back.
  *
  * Outside production auth sends no code or temporary password at all, so the staged stack cannot prove a message
  * arrives; the cache and the sync that carry the record to auth, and what auth does with it, are the unit specs' ground
  * (`DeliverySettingsSyncClientSpec`, `DeliveryChannelsSpec`). The record is global, which is why the tests run one after
  * another and each removes it at the end.
  */
object DeliveryFlowSpec extends CentralApiSpec:

  private val path = "/configuration/delivery-settings"

  private def smtpBlock(port: Int): Json.Obj =
    Json.Obj(
      "host" -> Json.Str("localhost"),
      "port" -> Json.Num(port),
      "username" -> Json.Str("mailer"),
      "from" -> Json.Str("noreply@example.test"),
      "subject" -> Json.Str("Delivery check"),
      "startTls" -> Json.Bool(false),
      "enabled" -> Json.Bool(true),
    )

  private def save(central: CentralApi, fields: (String, Json)*): Task[ApiResult] =
    central.put(path, Json.Obj(fields*)).tap(result =>
      ZIO.fail(RuntimeException(s"saving failed: ${result.status} ${result.body}")).unless(result.status == Status.NoContent),
    )

  /** Removes the record whatever the test did. A cleanup that fails is logged, with what to remove by hand, and does not
    * hide the test's own result. */
  private def cleanUp(central: CentralApi): UIO[Unit] =
    central.delete(path).unit
      .catchAllCause(cause => ZIO.logWarningCause(s"Could not remove the delivery settings after a test: DELETE $path by hand", cause))

  def spec = suite("Delivery settings")(
    test("a saved server is read back without its password, and only says that one is on record") {
      for
        central <- api
        read <- (save(central, "smtp" -> smtpBlock(2525), "smtpPassword" -> Json.Str("mail-s3cret"))
          *> central.get(path)).ensuring(cleanUp(central))
        body <- ZIO.fromEither(read.status match
          case Status.Ok => Right(read.body)
          case other => Left(RuntimeException(s"reading failed: $other ${read.body}")))
      yield assertTrue(
        body.contains("\"hasSmtpPassword\":true"),
        !body.contains("mail-s3cret"),
      )
    },
  ) @@ sequential
