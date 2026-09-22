package versola.oauth.metadata

import versola.util.{ClientAssertion, Dpop, JWT, RequestObject}
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
  * @param requestObjectSigningAlgorithms RFC 9101 §4: and again for the `alg` of a JAR
  *   request object.
  */
case class ServedMetadata(
    document: Json.Obj,
    dpopSigningAlgorithms: Set[Dpop.Algorithm],
    clientAssertionSigningAlgorithms: Set[ClientAssertion.Algorithm],
    requestObjectSigningAlgorithms: Set[ClientAssertion.Algorithm],
)

object ServedMetadata:
  /** Each algorithm set is read off the stored document, and the served document is then
    * normalized to name exactly those sets -- so what clients discover here and what a proof
    * or an assertion is actually held to are one decision rather than two copies of one.
    */
  /** RFC 8414 §2: the field naming which client authentication methods this server accepts.
    * `private_key_jwt` is accepted unconditionally -- see `ClientAuthentication` -- so it is
    * added here rather than left to whatever the stored document happens to say, the same
    * way the algorithm fields below are derived rather than trusted verbatim. Unioned with
    * whatever is already stored, not replaced: unlike the algorithm sets, this field also
    * names methods (`client_secret_basic`, `tls_client_auth`, ...) that this derivation has
    * no opinion on and must not drop.
    *
    * The mutual-TLS methods of RFC 8705 §2 are deliberately among those: whether a
    * certificate can be honoured at all depends on the reverse proxy in front of a *tenant*
    * (its `mtlsCertificateHeader`), while this document is served once for the deployment.
    * Deriving them from one tenant's settings would advertise a promise the next tenant's
    * proxy cannot keep, so they -- and §3.3's `tls_client_certificate_bound_access_tokens` --
    * stay operator-set fields of the stored document, which passes through untouched.
    */
  private val AuthMethodsField = "token_endpoint_auth_methods_supported"

  /** RFC 9101 §4 / OpenID Connect Discovery: whether a `request` parameter is accepted at all.
    * Derived rather than stored for the same reason the algorithm sets are -- it is true
    * because `AuthorizeRequestParser` resolves one, not because a document said so. The
    * by-reference `request_uri` of §5.2 is not implemented (a `request_uri` here is always a
    * pushed request, RFC 9126), so its field is derived to `false` just as firmly.
    */
  private val RequestParameterField = "request_parameter_supported"
  private val RequestUriParameterField = "request_uri_parameter_supported"

  /** JARM §4: the algorithms a `response` JWT may be signed with. Unlike the algorithm sets
    * above -- which validate a *stored* value against what this code recognizes -- this one
    * has no stored value to validate: a JARM response is signed with whichever algorithm the
    * tenant's own key happens to be published under (see `AuthorizationResponseService`), so
    * the only honest set is whatever this deployment's JWKS actually publishes right now.
    * `derive`'s caller supplies it (from the synced JWKS, not the stored document) for exactly
    * that reason -- a value written into the stored document here would be silently replaced,
    * never read.
    */
  private val AuthorizationSigningAlgField = "authorization_signing_alg_values_supported"

  def derive(stored: Json.Obj, publishedSigningAlgorithms: Set[JWT.Algorithm] = Set.empty): ServedMetadata =
    val dpopAlgorithms = Dpop.Algorithm.fromMetadata(stored)
    val assertionAlgorithms = ClientAssertion.Algorithm.fromMetadata(stored)
    val requestObjectAlgorithms = RequestObject.Algorithm.fromMetadata(stored)
    val storedMethods = stored.get(AuthMethodsField).flatMap(_.as[Set[String]].toOption).getOrElse(Set.empty)
    val document = state(
      state(
        advertise(
          advertise(
            advertise(
              advertise(
                advertise(stored, Dpop.Algorithm.MetadataField, dpopAlgorithms.map(_.toString)),
                ClientAssertion.Algorithm.MetadataField,
                assertionAlgorithms.map(_.toString),
              ),
              RequestObject.Algorithm.MetadataField,
              requestObjectAlgorithms.map(_.toString),
            ),
            AuthorizationSigningAlgField,
            publishedSigningAlgorithms.map(_.toString),
          ),
          AuthMethodsField,
          storedMethods + ClientAssertion.MethodName,
        ),
        RequestParameterField,
        true,
      ),
      RequestUriParameterField,
      false,
    )
    ServedMetadata(document, dpopAlgorithms, assertionAlgorithms, requestObjectAlgorithms)

  private def state(document: Json.Obj, field: String, value: Boolean): Json.Obj =
    Json.Obj((document.fields.filterNot(_._1 == field) :+ (field -> Json.Bool(value)))*)

  private def advertise(document: Json.Obj, field: String, values: Set[String]): Json.Obj =
    val advertised = Json.Arr(values.toList.sorted.map(Json.Str(_))*)
    Json.Obj((document.fields.filterNot(_._1 == field) :+ (field -> advertised))*)
