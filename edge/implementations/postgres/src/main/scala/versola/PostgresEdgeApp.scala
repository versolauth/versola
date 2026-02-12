package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.edge.{CallbackController, EdgeSessionController, PostgresEdgeSessionRepository}
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.{ConfigProvider, RLayer, ZLayer}

object PostgresEdgeApp extends EdgeApp:

  case class AppConfig(
      databases: Map[String, PostgresConfig],
  )

  val repositories =
    ZLayer.service[AppConfig].project(_.databases("postgres")) >>>
      (PostgresHikariDataSource.layer(migrate = true) >>> TransactorZIO.layer) >>>
        ZLayer.fromFunction(PostgresEdgeSessionRepository(_))

  val dependencies: RLayer[zio.Scope & ConfigProvider, Repositories] =
    parseConfig[AppConfig] >+> repositories

  def routes =
    List(
      CallbackController.routes,
      EdgeSessionController.routes,
    ).reduce(_ ++ _)

