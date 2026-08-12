package versola.central.users

import versola.central.authorizeBasic
import versola.central.configuration.resources.ResourceService
import versola.util.EnvName
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & UserOutboxProcessor & AuthClient & ResourceService & EnvName & UserService

  def routes: Routes[Env, Throwable] = Routes(flushOutboxEndpoint, syncConfigurationEndpoint, deleteUserEndpoint)

  val flushOutboxEndpoint =
    Method.POST / "service" / "users" / "outbox" / "flush" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeBasic(request)
            _ <- ZIO.serviceWithZIO[UserOutboxProcessor](_.flush())
          yield Response.ok
    }

  val syncConfigurationEndpoint =
    Method.POST / "service" / "configuration" / "sync" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeBasic(request)
            _ <- ZIO.serviceWithZIO[AuthClient](_.syncConfiguration())
          yield Response.ok
    }

  val deleteUserEndpoint =
    Method.DELETE / "service" / "users" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeBasic(request)
            service <- ZIO.service[UserService]
            id <- request.queryZIO[UserId]("id")
            _ <- service.delete(id)
          yield Response.status(Status.NoContent)
    }
