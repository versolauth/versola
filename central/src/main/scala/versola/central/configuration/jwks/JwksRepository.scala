package versola.central.configuration.jwks

import versola.util.CacheSource
import zio.Task
import zio.json.ast.Json

trait JwksRepository extends CacheSource[Vector[JwksRecord]]:
  def getAll: Task[Vector[JwksRecord]]

  def find(kid: String): Task[Option[JwksRecord]]

  def create(kid: String, jwk: Json.Obj): Task[Unit]

  def update(kid: String, jwk: Json.Obj): Task[Unit]

  def delete(kid: String): Task[Unit]
