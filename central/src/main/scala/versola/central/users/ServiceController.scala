package versola.central.users

import versola.central.configuration.edges.EdgeService
import versola.central.configuration.resources.ResourceService
import versola.central.{CentralConfig, authorizeBasic, authorizeInternal}
import versola.util.EnvName
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & UserOutboxProcessor & AuthClient & ResourceService & EnvName & UserService &
    CentralConfig & EdgeService

  def routes: Routes[Env, Throwable] =
    Routes(flushOutboxEndpoint, syncConfigurationEndpoint, deleteUserEndpoint, registeredUserEndpoint)

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

  /** Receives accounts auth created through self-service registration. Authorized with the
    * internal sync token, since auth calls it in production rather than an operator.
    */
  val registeredUserEndpoint =
    Method.POST / "service" / "users" / "registrations" -> handler { (request: Request) =>
      for
        _ <- authorizeInternal(request)
        service <- ZIO.service[UserService]
        body <- request.body.asJsonFromCodec[RegisteredUserRequest]
        _ <- service.indexRegistered(body)
      yield Response.status(Status.NoContent)
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
