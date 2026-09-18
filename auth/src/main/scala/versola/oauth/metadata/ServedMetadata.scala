package versola.oauth.metadata

import versola.util.{ClientAssertion, Dpop}
import zio.json.ast.Json

/** The authorization server metadata document as `auth` actually serves it, paired with what
  * that document was read to mean for the checks it governs.
  *
  * All fields are derived once, when the underlying document is fetched -- see
  * `OAuthConfigurationService`'s metadata cache -- rather than on every read. A GET against a
  * document that hasn't changed should cost nothing more than a `Ref` read.
  *
  * @param document what `auth`'s metadata endpoint serves: the stored document with the
  *   algorithm fields normalized to the sets below rather than whatever the stored record
  *   happened to contain.
  * @param dpopSigningAlgorithms RFC 9449 §5.1: the signing algorithms an incoming DPoP proof's
  *   `alg` is checked against -- exactly what [[document]] advertises, so the set clients
  *   discover and the set a proof is held to cannot drift apart.
  * @param clientAssertionSigningAlgorithms RFC 7523 / RFC 8414 §2: the same arrangement for
  *   the `alg` of a `private_key_jwt` client assertion.
  */
case class ServedMetadata(
    document: Json.Obj,
    dpopSigningAlgorithms: Set[Dpop.Algorithm],
    clientAssertionSigningAlgorithms: Set[ClientAssertion.Algorithm],
)

object ServedMetadata:
  /** Each algorithm set is read off the stored document, and the served document is then
    * normalized to name exactly those sets -- so what clients discover here and what a proof
    * or an assertion is actually held to are one decision rather than two copies of one.
    */
  def derive(stored: Json.Obj): ServedMetadata =
    val dpopAlgorithms = Dpop.Algorithm.fromMetadata(stored)
    val assertionAlgorithms = ClientAssertion.Algorithm.fromMetadata(stored)
    val document = advertise(
      advertise(stored, Dpop.Algorithm.MetadataField, dpopAlgorithms.map(_.toString)),
      ClientAssertion.Algorithm.MetadataField,
      assertionAlgorithms.map(_.toString),
    )
    ServedMetadata(document, dpopAlgorithms, assertionAlgorithms)

  private def advertise(document: Json.Obj, field: String, values: Set[String]): Json.Obj =
    val advertised = Json.Arr(values.toList.sorted.map(Json.Str(_))*)
    Json.Obj((document.fields.filterNot(_._1 == field) :+ (field -> advertised))*)
