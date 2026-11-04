package versola.oauth.client

import versola.audit.AuditSupport.*
import versola.audit.{AuthActors, AuthAuditDetails, AuthAuditKinds}
import versola.oauth.delivery.DeliveryChannels
import versola.oauth.jwks.JwksService
import versola.oauth.mtls.EdgeCertificateTrust
import versola.user.authorizeInternal
import versola.util.audit.{AuditDraft, AuditEvents, AuditOutcome}
import versola.util.http.Controller
import versola.util.{CoreConfig, EnvName}
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.tracing.Tracing

object ServiceController extends Controller:
  type Env = Tracing & OAuthConfigurationService & JwksService & EnvName & CoreConfig &
    versola.user.UserRepository & EdgeCertificateTrust & DeliveryChannels & AuditEvents

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
            // And the edges central lists, which `/userinfo` checks an edge's certificate against.
            _ <- ZIO.serviceWithZIO[EdgeCertificateTrust](_.refresh)
            // And the SMTP server and SMS provider an administrator saved in the console.
            _ <- ZIO.serviceWithZIO[DeliveryChannels](_.refresh)
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
            events <- ZIO.service[AuditEvents]
            // A test deployment's clean-up (not served in production), recorded like any removal of an account.
            _ <- events.atomically(
              AuditDraft(
                kind = AuthAuditKinds.AccountDeleted,
                outcome = AuditOutcome.success,
                actor = Some((AuthActors.service, None)),
                userId = Some(id),
                target = Some(("user", id.toString)),
                details = AuthAuditDetails.Account(via = "service"),
              ),
            )(repo.delete(id, _))
          yield Response.status(Status.NoContent)
    }
