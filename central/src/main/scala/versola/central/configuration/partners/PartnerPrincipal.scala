package versola.central.configuration.partners

import versola.central.configuration.clients.ClientId
import versola.central.configuration.jwks.JwksService
import versola.central.configuration.metadata.ServerMetadataService
import versola.util.JWT
import versola.util.http.Observability
import zio.json.ast.Json
import zio.json.{JsonDecoder, jsonField}
import zio.metrics.Metric
import zio.{IO, UIO, ZIO}

/** Who at the partner acted (#549 decision 4): on a management call the partner's edge forwards the staff member's access
  * token, issued by the operator's auth to the partner's console client, in `Versola-Principal`. Central verifies it against
  * the keys auth signs with (its own `JwksService`, central being where auth's keys come from), the token type, its expiry,
  * its issuer (the server metadata's `issuer`: `JWT.deserialize` checks none) and its `client_id`, and takes `sub` for the
  * audit log. Nothing else about the person is checked: who may do what at the partner is the edge's decision (#549 decision
  * 3), and the token is the evidence that a member with a live session asked. */
object PartnerPrincipal:
  val Header = "Versola-Principal"

  case class Principal(sub: String)

  /** The header is missing, or the token is not a live access token of the partner's console client from this issuer. */
  case object Mismatch

  private val mismatches = Metric.counter("partner_principal_mismatch_total")

  private case class Claims(
      iss: Option[String],
      sub: Option[String],
      @jsonField("client_id") clientId: Option[String],
  ) derives JsonDecoder

  /** The `Versola-Principal` of `raw` for the console client `consoleClientId`, or [[Mismatch]] (`partner_principal_mismatch`
    * in the request log and counted). */
  def verify(raw: Option[String], consoleClientId: ClientId): ZIO[JwksService & ServerMetadataService, Mismatch.type, Principal] =
    val outcome =
      for
        token <- ZIO.fromOption(raw.map(_.trim).filter(_.nonEmpty)).orElseFail("absent")
        keys <- ZIO.serviceWithZIO[JwksService](_.getPublicKeys)
        claims <- JWT.deserialize[Claims](token, keys, JWT.Type.AccessToken).mapError(error => s"token: $error")
        issuer <- ZIO.serviceWithZIO[ServerMetadataService](_.getMetadata)
          .map(_.flatMap(_.get("issuer")).collect { case Json.Str(value) => value })
        _ <- ZIO.fail("issuer").unless(issuer.isDefined && claims.iss == issuer)
        _ <- ZIO.fail("client").unless(claims.clientId.contains(consoleClientId: String))
        sub <- ZIO.fromOption(claims.sub.filter(_.nonEmpty)).orElseFail("subject")
      yield Principal(sub)
    outcome.catchAll: reason =>
      Observability.setError("partner_principal_mismatch", Some(reason)) *> mismatches.increment *> ZIO.fail(Mismatch)
