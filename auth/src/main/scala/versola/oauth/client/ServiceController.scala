package versola.oauth.client

import versola.oauth.jwks.JwksService
import versola.user.authorizeInternal
import versola.util.{CoreConfig, EnvName}
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & OAuthConfigurationService & JwksService & EnvName & CoreConfig &
    versola.user.UserRepository

  def routes: Routes[Env, Throwable] = Routes(syncEndpoint, deleteUserEndpoint)

  val syncEndpoint =
    Method.POST / "service" / "configuration" / "sync" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeInternal(request)
            _ <- ZIO.serviceWithZIO[OAuthConfigurationService](_.syncConfiguration)
            // The signing key a tenant points at is configuration like any other, but it
            // lives in the JWKS cache rather than the configuration one -- a sync that
            // reloaded only the selection would leave the key it names unsynced.
            _ <- ZIO.serviceWithZIO[JwksService](_.refresh)
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
