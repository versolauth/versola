package versola.configuration.themes

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import versola.central.configuration.tenants.TenantId
import versola.central.configuration.themes.{ThemeRecord, ThemeRepository}
import versola.util.postgres.BasicCodecs
import zio.{Task, ZLayer}

class PostgresThemeRepository(xa: TransactorZIO) extends ThemeRepository, BasicCodecs:

  given DbCodec[TenantId] = DbCodec.StringCodec.biMap(TenantId(_), identity[String])
  given DbCodec[ThemeRecord] = DbCodec.derived

  override def getAll: Task[Vector[ThemeRecord]] =
    xa.trackedConnect:
      sql"""SELECT id, css, tenant_id FROM themes ORDER BY id"""
        .query[ThemeRecord]
        .run()

  override def create(theme: ThemeRecord): Task[Unit] =
    xa.trackedConnect:
      sql"""INSERT INTO themes (id, css, tenant_id)
            VALUES (${theme.id}, ${theme.css}, ${theme.tenantId})"""
        .update.run()
    .unit

  override def update(theme: ThemeRecord): Task[Unit] =
    xa.trackedConnect:
      sql"""UPDATE themes SET css = ${theme.css} WHERE id = ${theme.id}"""
        .update.run()
    .unit

  override def delete(id: String): Task[Unit] =
    xa.trackedConnect:
      sql"""DELETE FROM themes WHERE id = $id"""
        .update.run()
    .unit

object PostgresThemeRepository:
  def live: ZLayer[TransactorZIO, Nothing, ThemeRepository] =
    ZLayer.fromFunction(PostgresThemeRepository(_))
