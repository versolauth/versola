package versola.central.configuration.jwks

import versola.util.{CacheSource, Secret}
import zio.Task
import zio.json.ast.Json

trait JwksRepository extends CacheSource[Vector[JwksRecord]]:
  def getAll: Task[Vector[JwksRecord]]

  def find(kid: String): Task[Option[JwksRecord]]

  /** `privateKey` is the encrypted PKCS#8 private half, or `None` for a key central can only
    * publish for verification -- an operator-supplied JWK has no private half here.
    */
  def create(kid: String, jwk: Json.Obj, privateKey: Option[Secret]): Task[Unit]

  /** Replaces the published JWK only. The private half is written once, at creation: a key
    * whose public and private halves could be updated independently is a key whose kid can
    * come to name a different keypair than the one that signs under it.
    */
  def update(kid: String, jwk: Json.Obj): Task[Unit]

  def delete(kid: String): Task[Unit]
