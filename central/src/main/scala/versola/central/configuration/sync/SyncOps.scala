package versola.central.configuration.sync

import versola.central.configuration.tenants.TenantId
import versola.util.ReloadingCache
import zio.{Task, UIO}

object SyncOps:
  def syncCache(event: SyncEvent.ModifyCache)(
      cache: ReloadingCache[Vector[event.Record]],
      find: => Task[Option[event.Record]],
  ): Task[Unit] = {
    event.op match
      case SyncEvent.Op.DELETE =>
        cache.update(_.filterNot(event.matches))

      case _ =>
        find.flatMap:
          case None =>
            cache.update(_.filterNot(event.matches))

          case Some(record) =>
            cache.update { records =>
              event.sort(
                records.filterNot(event.matches).appended(record)
              )
            }

  }
