package versola.e2e.flows.provision

import versola.e2e.support.*
import versola.loadgen.config.*
import versola.loadgen.protocol.HttpAdminClient
import versola.loadgen.provision.{CampaignBlueprint, FlowResources, Provisioner}
import zio.*
import zio.http.{Client, Method}
import zio.json.ast.Json
import zio.test.*

/** `loadgen provision` against a real central, not [[versola.loadgen.provision.FakeCentral]].
  *
  * `FakeCentral` is a hand-maintained stand-in, kept in step with central's DTOs by eye (see its
  * own doc comment): it has twice let a wire-shape mismatch between loadgen's admin client and
  * central's real decoder pass loadgen's own suite, because the shape it never modelled -- an
  * `authFlow` central actually validates, an `audience` central actually patches -- was exactly
  * the one that had drifted. This spec runs loadgen's real [[Provisioner]] and [[HttpAdminClient]],
  * unmodified, against the same staged central/auth/edge the rest of `e2e` drives, so a decode
  * error on either side fails here instead of on a live campaign.
  *
  * Provisions the campaign's real client ids ([[CampaignBlueprint.clientIds]]), the same ones
  * production writes -- there is exactly one of these per e2e run (a fresh Postgres per CI job),
  * so nothing else in the suite creates or reads them.
  */
object LoadgenProvisionSpec extends ZIOSpec[Client & E2EConfig & EdgeApi & OAuthClient & CentralApi]:

  override val bootstrap: ZLayer[Any, Any, Client & E2EConfig & EdgeApi & OAuthClient & CentralApi] =
    (E2EConfig.live ++ Client.default) >+> (EdgeApi.live ++ OAuthClient.live ++ CentralApi.live)

  override val aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    Chunk(TestAspect.withLiveClock)

  private def targets(c: E2EConfig): TargetsConfig =
    TargetsConfig(
      authUrl = c.authUrl,
      edgeUrl = c.edgeUrl,
      // Unused by `provision` (only auth's token endpoint and edge's proxy are called), and
      // never dereferenced by central either -- the resource URIs below are stored, not read.
      mockUrl = "http://e2e-mockapi.invalid:8100",
      origin = c.redirectUri,
    )

  private def provision(c: E2EConfig): ProvisionConfig =
    ProvisionConfig(
      tenantId = "default",
      provisionerClientId = c.provisionerClientId,
      provisionerSecret = Config.Secret(c.provisionerSecret),
      mobileRedirectUri = "versola://e2e-callback",
      resources = ProvisionResourcesConfig(
        coreUri = "http://e2e-mockapi-core.invalid:8100",
        payUri = "http://e2e-mockapi-pay.invalid:8100",
        notifyUri = "http://e2e-mockapi-notify.invalid:8100",
      ),
      preset = ProvisionPresetConfig(
        id = "e2e-web-otp",
        cookieDomain = None,
        cookiePath = Some("/"),
        postLogoutRedirectUri = Some(s"${c.redirectUri}/goodbye"),
      ),
      passkey = ProvisionPasskeyConfig(rpId = "localhost", rpName = "Versola E2E", userVerification = "preferred"),
      paymentAmountThreshold = 1000000L,
    )

  def spec = suite("loadgen provision: the real admin client against a real central")(
    test("provisions the campaign's clients, and a second pass changes nothing") {
      for
        c <- ZIO.service[E2EConfig]
        client <- ZIO.service[Client]
        auth <- ZIO.service[OAuthClient]
        edgeApi <- ZIO.service[EdgeApi]
        centralApi <- ZIO.service[CentralApi]
        // Central seeds the `utils` client into its own store at bootstrap, but a token for it
        // is not reliably issuable until auth's own client cache has turned over. Forcing a
        // sync here (which does make auth re-fetch `configuration/clients/sync` from central --
        // confirmed in its own log) is not sufficient by itself: empirically, the very next
        // token request can still 401 for a further ~20-60s even after this call answers 200
        // (versolauth/versola#412 -- filed separately; root cause not yet pinned down beyond
        // "auth's own periodic configuration-cache-refresh-interval eventually resolves it, an
        // explicit sync does not reliably do so sooner"). Retried on a 5xx for the reason
        // `HttpAdminClient.syncConfiguration` is: this makes central call auth over a pooled
        // connection, and one auth closed while it sat idle surfaces here as a 500 on the first
        // attempt.
        _ <- centralApi.postEmpty("/service/configuration/sync")
          .filterOrFail(_.response.status.isSuccess)(RuntimeException("initial sync did not succeed"))
          .retry(Schedule.recurs(5) && Schedule.spaced(1.second))
        flows <- FlowResources.load
        blueprint = CampaignBlueprint(targets(c), provision(c), flows)
        admin <- HttpAdminClient.make(client, targets(c), provision(c))
        // Bounded to comfortably clear a full `configuration-cache-refresh-interval` (dev
        // default: 1 minute) rather than the sync race above -- `provision` is re-runnable by
        // construction (see `Provisioner`'s own doc comment), so retrying the whole first pass
        // is exactly as safe as the "finishes a run that died halfway" case its own suite
        // already covers.
        firstPass <- Provisioner.run(admin, blueprint)
          .retry(Schedule.recurs(40) && Schedule.spaced(2.seconds))
        secondPass <- Provisioner.run(admin, blueprint)
        token <- auth.clientCredentials(
          clientId = c.provisionerClientId,
          clientSecret = c.provisionerSecret,
          resources = Some(List("resource://central")),
        ).success
        listed <- edgeApi.proxy(
          Method.GET,
          "central",
          "/configuration/clients",
          EdgeAuth.Bearer(token.accessToken),
          query = List("tenantId" -> "default"),
        )
        body <- listed.obj
        ids = body.get("clients").toList.flatMap:
          case Json.Arr(entries) => entries.toList.flatMap(_.asObject).flatMap(_.get("id")).collect { case Json.Str(id) => id }
          case _ => Nil
      yield assertTrue(
        firstPass.keySet == CampaignBlueprint.clientIds.toSet,
        secondPass.keySet == CampaignBlueprint.clientIds.toSet,
        CampaignBlueprint.clientIds.forall(ids.contains),
      )
    },
  )
