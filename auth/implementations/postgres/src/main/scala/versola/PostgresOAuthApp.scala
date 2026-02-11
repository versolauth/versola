package versola

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.oauth.conversation.PostgresConversationRepository
import versola.oauth.conversation.otp.EmailOtpProvider
import versola.oauth.session.PostgresSessionRepository
import versola.oauth.{PostgresAuthorizationCodeRepository, PostgresOAuthClientRepository, PostgresOAuthScopeRepository}
import versola.user.PostgresUserRepository
import versola.util.postgres.{PostgresConfig, PostgresHikariDataSource}
import zio.{ConfigProvider, RLayer, ZLayer}

object PostgresOAuthApp extends OAuthApp:

  case class AppConfig(
      databases: Map[String, PostgresConfig],
  )

  val serviceProviders =
    EmailOtpProvider.live

  val repositories =
    ZLayer.service[AppConfig].project(_.databases("postgres")) >>>
      (PostgresHikariDataSource.layer(migrate = true) >>> TransactorZIO.layer) >>> (
        // ZLayer.fromFunction(PostgresBanRepository(_)) ++
        ZLayer.fromFunction(PostgresUserRepository(_)) ++
          ZLayer.fromFunction(PostgresOAuthClientRepository(_)) ++
          ZLayer.fromFunction(PostgresOAuthScopeRepository(_)) ++
          ZLayer.fromFunction(PostgresConversationRepository(_)) ++
          ZLayer.fromFunction(PostgresAuthorizationCodeRepository(_)) ++
          ZLayer.fromFunction(PostgresSessionRepository(_))
      )

  val dependencies: RLayer[zio.Scope & ConfigProvider, Repositories & ServiceProviders] =
    parseConfig[AppConfig] >+> serviceProviders >+> repositories
