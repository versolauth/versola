package versola.central.configuration.jwks

import versola.util.{JWT, ReloadingCache}
import zio.json.ast.Json
import zio.{Schedule, Scope, Task, UIO, ZLayer}

/** Central is the source of truth for the JWKS, stored in the database and
  * served from a periodically reloaded cache.
  *
  *   - [[getPublicKeys]] serves the parsed keys used to verify admin-console
  *     tokens.
  *   - [[getRaw]] backs the `/configuration/jwks/sync` endpoint used by
  *     auth/edge and the admin console view.
  */
trait JwksService:
  def getPublicKeys: UIO[JWT.PublicKeys]
  def getRaw: UIO[Json.Obj]
  def sync(): Task[Unit]
  def createKey(kid: String, jwk: Json.Obj): Task[Unit]
  def updateKey(kid: String, jwk: Json.Obj): Task[Unit]
  def deleteKey(kid: String): Task[Unit]

object JwksService:
  def live(
      schedule: Schedule[Any, Any, Any],
  ): ZLayer[JwksRepository & Scope, Throwable, JwksService] =
    ZLayer(ReloadingCache.make[Vector[JwksRecord]](schedule))
      >>> ZLayer.fromFunction(Impl(_, _))

  private def toJwks(records: Vector[JwksRecord]): Json.Obj =
    Json.Obj("keys" -> Json.Arr(records.map(_.jwk)*))

  case class Impl(
      cache: ReloadingCache[Vector[JwksRecord]],
      repository: JwksRepository,
  ) extends JwksService:
    override def getPublicKeys: UIO[JWT.PublicKeys] =
      cache.get.map(records => JWT.PublicKeys.fromJson(toJwks(records)))

    override def getRaw: UIO[Json.Obj] =
      cache.get.map(toJwks)

    override def sync(): Task[Unit] =
      repository.getAll.flatMap(cache.set)

    override def createKey(kid: String, jwk: Json.Obj): Task[Unit] =
      repository.create(kid, jwk)

    override def updateKey(kid: String, jwk: Json.Obj): Task[Unit] =
      repository.update(kid, jwk)

    override def deleteKey(kid: String): Task[Unit] =
      repository.delete(kid)
