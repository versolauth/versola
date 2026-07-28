package versola.central.configuration.metadata

import versola.util.CacheSource
import zio.Task
import zio.json.ast.Json

trait ServerMetadataRepository extends CacheSource[Option[ServerMetadataRecord]]:
  def get: Task[Option[ServerMetadataRecord]]
  
  override def getAll: Task[Option[ServerMetadataRecord]] = get

  def upsert(metadata: Json.Obj): Task[Unit]
