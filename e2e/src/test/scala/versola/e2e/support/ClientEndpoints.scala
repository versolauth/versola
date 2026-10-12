package versola.e2e.support

import versola.loadgen.provision.FakeCentral
import zio.http.Method
import zio.json.ast.Json

/** Central registers and updates a client at the endpoint of its kind
  * (`/configuration/clients/{web,native,service}`), each taking only the members its kind has.
  *
  * The fixtures here describe a client as one flat document, in the shape of the registration the
  * kinds replaced. This picks the endpoint such a document belongs to and cuts it to the members
  * that endpoint declares, so a spec says what it needs of a client and not which request type
  * carries it. A spec checking what an endpoint refuses calls the endpoint itself, as
  * `TypedClientApiSpec` does.
  */
object ClientEndpoints:

  val Path = "/configuration/clients"

  private val kinds = List("web", "native", "service")

  /** The kind a flat client document is: what its template names, else a native application
    * type or a public client (no credential, so it cannot be a confidential web client), else a
    * client with nowhere to send a user. */
  def kindOf(body: Json.Obj): String =
    val templated = body.get("template").collect { case template: Json.Obj => template }
      .flatMap(_.get("kind")).collect { case Json.Str(kind) => kind }
    def text(member: String) = body.get(member).collect { case Json.Str(value) => value }
    def present(member: String) = body.get(member).exists:
      case Json.Null => false
      case _ => true
    templated match
      case Some("device") => "native"
      case Some("service") => "service"
      case Some(_) => "web"
      case None =>
        if text("applicationType").contains("native") || text("authMethod").contains("none") then "native"
        else if !present("authFlow") && !body.get("redirectUris").exists { case Json.Arr(uris) => uris.nonEmpty; case _ => false } then "service"
        else "web"

  private def only(body: Json.Obj, kind: String, method: Method): Json.Obj =
    val allowed = FakeCentral.allowedMembers((method, s"$Path/$kind"))
    Json.Obj(body.fields.filter((member, _) => allowed.contains(member)))

  /** The endpoint and body that register `body`. */
  def registration(body: Json.Obj): (String, Json.Obj) =
    val kind = kindOf(body)
    (s"$Path/$kind", only(body, kind, Method.POST))

  /** Every endpoint an update of `body` may be for, in the order to try them: the update names a
    * client and not its kind, and a kind's endpoint answers `409` for a client of another. */
  def updates(body: Json.Obj): List[(String, Json.Obj)] =
    kinds.map(kind => (s"$Path/$kind", only(body, kind, Method.PUT)))
