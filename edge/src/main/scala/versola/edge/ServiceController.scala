package versola.edge

import versola.util.{EnvName, Secret}
import versola.util.http.{Controller, Unauthorized}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

import java.security.MessageDigest

object ServiceController extends Controller:
  type Env = Tracing & OAuthClientService & EdgeConfig & EnvName

  def routes: Routes[Env, Throwable] = Routes(syncEndpoint)

  val syncEndpoint =
    Method.POST / "service" / "configuration" / "sync" -> handler { (request: Request) =>
      ZIO.serviceWithZIO[EnvName]: env =>
        if env.isProd then ZIO.succeed(Response.notFound)
        else
          for
            _ <- authorizeInternal(request)
            _ <- ZIO.serviceWithZIO[OAuthClientService](_.refreshNow)
          yield Response.ok
    }

  /** Verifies `EdgeConfig.security.internalSecret` via HTTP Basic.
    *
    * Not a general-purpose credential -- this endpoint has no other caller, and this secret
    * has no other use. Absent from an environment's config, the check always fails, so an
    * environment that was never given one (every one that predates this endpoint) leaves it
    * unreachable rather than reachable with an empty secret.
    */
  private def authorizeInternal(request: Request): ZIO[EdgeConfig, Unauthorized.type, Unit] =
    request.header(Header.Authorization) match
      case Some(Header.Authorization.Basic(username, password)) if username == "edge" =>
        for
          provided <- ZIO.fromEither(Secret.fromBase64Url(password.stringValue)).orElseFail(Unauthorized)
          config <- ZIO.service[EdgeConfig]
          expected <- ZIO.fromOption(config.security.internalSecret).orElseFail(Unauthorized)
          _ <- ZIO.unless(MessageDigest.isEqual(provided, expected))(ZIO.fail(Unauthorized))
        yield ()

      case _ =>
        ZIO.fail(Unauthorized)
