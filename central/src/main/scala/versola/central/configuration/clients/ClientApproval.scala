package versola.central.configuration.clients

import zio.json.{JsonDecoder, JsonEncoder}
import zio.prelude.Equal
import zio.schema.*

/** Where a client stands with the operator (#549 decision 7). A first-party client is `approved` from the start; a client a
  * partner registered through its edge is `pending` until the operator approves it, and `rejected` if they do not. A client
  * that is not `approved` is synced to nobody and issues no token. */
enum ClientApproval(val code: String):
  case Pending extends ClientApproval("pending")
  case Approved extends ClientApproval("approved")
  case Rejected extends ClientApproval("rejected")

object ClientApproval:
  def from(code: String): Either[String, ClientApproval] =
    values.find(_.code == code).toRight(s"unknown client approval '$code', expected one of ${values.map(_.code).mkString(", ")}")

  given Schema[ClientApproval] = Schema.primitive[String].transformOrFail(from, approval => Right(approval.code))
  given JsonDecoder[ClientApproval] = JsonDecoder.string.mapOrFail(from)
  given JsonEncoder[ClientApproval] = JsonEncoder.string.contramap(_.code)
  given Equal[ClientApproval] = (a, b) => a == b
