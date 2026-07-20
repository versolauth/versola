package versola.oauth.client

import versola.util.EnvName
import versola.util.http.Controller
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & OAuthConfigurationService & EnvName

  def routes: Routes[Env, Throwable] = Routes(syncEndpoint)

  val syncEndpoint =
    Method.POST / "service" / "configuration" / "sync" -> handler { (_: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else ZIO.serviceWithZIO[OAuthConfigurationService](_.syncConfiguration).as(Response.ok)
    }
