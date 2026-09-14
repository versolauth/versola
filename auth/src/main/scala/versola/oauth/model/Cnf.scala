package versola.oauth.model

import zio.json.*
import zio.json.ast.Json
import zio.prelude.Equal

/** RFC 7800 confirmation claim: the key a sender-constrained token is bound to.
  *
  * The members are independent because the mechanisms that produce them are: `jkt` comes from
  * an RFC 9449 DPoP proof, `x5t#S256` from the RFC 8705 client certificate the token was
  * issued over. A grant records the whole claim rather than one mechanism's thumbprint, so
  * that adding a mechanism does not mean reshaping every row that already carries a binding.
  */
case class Cnf(
    /** RFC 9449 §6: JWK SHA-256 thumbprint of the client's DPoP public key. */
    jkt: Option[String],
    /** RFC 8705 §3.1: base64url-encoded SHA-256 hash of the DER client certificate. */
    @jsonField("x5t#S256") x5tS256: Option[String],
) derives JsonCodec, CanEqual, Equal:

  /** The claim as it appears in an access token, omitting absent members. */
  def toJsonObj: Json.Obj =
    Json.Obj(
      (jkt.map("jkt" -> Json.Str(_)) ++ x5tS256.map("x5t#S256" -> Json.Str(_))).toSeq*,
    )

object Cnf:
  def dpop(jkt: String): Cnf = Cnf(jkt = Some(jkt), x5tS256 = None)

  def certificate(x5tS256: String): Cnf = Cnf(jkt = None, x5tS256 = Some(x5tS256))
