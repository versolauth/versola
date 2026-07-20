package versola.central.users

import versola.util.EnvName
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & UserOutboxProcessor & EnvName

  def routes: Routes[Env, Throwable] = Routes(flushOutboxEndpoint)

  val flushOutboxEndpoint =
    Method.POST / "service" / "users" / "outbox" / "flush" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else ZIO.serviceWithZIO[UserOutboxProcessor](_.flush()).as(Response.ok)
    }
