package versola.oauth.client

import versola.user.authorizeInternal
import versola.util.{CoreConfig, EnvName}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & OAuthConfigurationService & EnvName & CoreConfig & versola.user.UserRepository

  def routes: Routes[Env, Throwable] = Routes(syncEndpoint, deleteUserEndpoint)

  val syncEndpoint =
    Method.POST / "service" / "configuration" / "sync" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeInternal(request)
            _ <- ZIO.serviceWithZIO[OAuthConfigurationService](_.syncConfiguration)
          yield Response.ok
    }

  val deleteUserEndpoint =
    Method.DELETE / "service" / "users" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeInternal(request)
            repo <- ZIO.service[versola.user.UserRepository]
            id <- request.url.queryZIO[versola.user.model.UserId]("id")
            _ <- repo.delete(id)
          yield Response.status(Status.NoContent)
    }
