package versola.oauth.delivery

import versola.auth.TestEnvConfig
import versola.oauth.delivery.DeliveryChannels.Origin
import versola.oauth.delivery.DeliverySettingsSyncClient.{EndpointMissing, Fetched, Saved}
import versola.util.Phone
import versola.util.delivery.{FakeSmtpServer, SmsProviderSettings, SmtpSettings}
import versola.util.{CacheSource, CoreConfig, Email}
import zio.*
import zio.http.{Client, Handler, Method, Request, Response, TestClient, URL}
import zio.test.*
import zio.test.TestAspect.silentLogging

object DeliveryChannelsSpec extends ZIOSpecDefault:

  /** Holds an SMTP server and an SMS provider, the way `auth.conf` can. */
  private val withBoth = TestEnvConfig.coreConfig
  private val withNeither = TestEnvConfig.coreConfig.copy(smtp = None, otpProvider = None)

  private val centralSmtp = SmtpSettings(
    host = "smtp.central.example.com",
    port = 587,
    username = "mailer",
    password = Some("mail-s3cret"),
    from = Email("noreply@example.com"),
    subject = "Your code",
    startTls = true,
  )

  private val centralSms = SmsProviderSettings(
    method = Method.POST,
    url = URL.decode("https://sms.central.example.com/send").toOption.get,
    username = Some("sms"),
    password = Some("sms-s3cret"),
    body = Map("phones" -> "{{phone}}"),
  )

  private def resolved(config: CoreConfig, fetched: Fetched): ZIO[Client, Nothing, DeliveryChannels.Resolved] =
    ZIO.serviceWith[Client](client => DeliveryChannels.resolve(config = config, client = client, centralClient = client, central = fetched))

  /** A client that counts the requests it gets, to see which of two a sender was built on. */
  private def counting: ZIO[Scope, Nothing, (Client, Ref[Int])] =
    for
      env <- TestClient.layer.build
      hits <- Ref.make(0)
      _ <- env.get[TestClient].addRoutes(Handler.fromFunctionZIO[Request](_ => hits.update(_ + 1).as(Response.ok)).toRoutes)
    yield (env.get[Client], hits)

  def spec = suite("DeliveryChannels")(
    suite("resolve")(
      test("uses the blocks of auth.conf when central holds nothing") {
        for r <- resolved(withBoth, Fetched.empty)
        yield assertTrue(
          r.email.isDefined,
          r.emailOrigin == Origin.Config,
          !r.emailOverridesConfig,
          r.sms.isDefined,
          r.smsOrigin == Origin.Config,
          !r.smsOverridesConfig,
        )
      },
      test("has no channel when neither central nor auth.conf holds one") {
        for r <- resolved(withNeither, Fetched.empty)
        yield assertTrue(r.email.isEmpty, r.emailOrigin == Origin.Absent, r.sms.isEmpty, r.smsOrigin == Origin.Absent)
      },
      test("prefers a block saved in central that is switched on, and says it overrides auth.conf") {
        for r <- resolved(withBoth, Fetched(Some(Saved(centralSmtp, enabled = true)), Some(Saved(centralSms, enabled = true))))
        yield assertTrue(
          r.email.isDefined,
          r.emailOrigin == Origin.Central,
          r.emailOverridesConfig,
          r.sms.isDefined,
          r.smsOrigin == Origin.Central,
          r.smsOverridesConfig,
        )
      },
      test("uses a block saved in central when auth.conf has none, without calling it an override") {
        for r <- resolved(withNeither, Fetched(Some(Saved(centralSmtp, enabled = true)), None))
        yield assertTrue(r.email.isDefined, r.emailOrigin == Origin.Central, !r.emailOverridesConfig)
      },
      test("sends nothing through a block saved in central that is switched off, even when auth.conf has one") {
        for r <- resolved(withBoth, Fetched(Some(Saved(centralSmtp, enabled = false)), Some(Saved(centralSms, enabled = false))))
        yield assertTrue(r.email.isEmpty, r.emailOrigin == Origin.Off, r.sms.isEmpty, r.smsOrigin == Origin.Off)
      },
      test("decides each channel on its own: saving only an SMTP server keeps the SMS provider of auth.conf") {
        for r <- resolved(withBoth, Fetched(Some(Saved(centralSmtp, enabled = true)), None))
        yield assertTrue(
          r.emailOrigin == Origin.Central,
          r.smsOrigin == Origin.Config,
          r.sms.isDefined,
        )
      },
      test("decides each channel on its own: saving only an SMS provider keeps the SMTP server of auth.conf") {
        for r <- resolved(withBoth, Fetched(None, Some(Saved(centralSms, enabled = true))))
        yield assertTrue(r.emailOrigin == Origin.Config, r.email.isDefined, r.smsOrigin == Origin.Central)
      },
      test("switching one channel off leaves the other as it was") {
        for r <- resolved(withBoth, Fetched(Some(Saved(centralSmtp, enabled = false)), None))
        yield assertTrue(r.emailOrigin == Origin.Off, r.email.isEmpty, r.smsOrigin == Origin.Config, r.sms.isDefined)
      },
    ).provide(TestClient.layer),
    suite("the target policy")(
      test("the provider saved in central is called through the policy's client, the one of auth.conf through the shared one") {
        for
          (shared, sharedHits) <- counting
          (guarded, guardedHits) <- counting
          fromCentral = DeliveryChannels.resolve(
            config = withNeither,
            client = shared,
            centralClient = guarded,
            central = Fetched(None, Some(Saved(centralSms, enabled = true))),
          )
          fromConfig = DeliveryChannels.resolve(config = withBoth, client = shared, centralClient = guarded, central = Fetched.empty)
          _ <- ZIO.foreachDiscard(fromCentral.sms)(_.send(Phone("+12025551234"), "code"))
          afterCentral <- sharedHits.get.zip(guardedHits.get)
          _ <- ZIO.foreachDiscard(fromConfig.sms)(_.send(Phone("+12025551234"), "code"))
          afterConfig <- sharedHits.get.zip(guardedHits.get)
        yield assertTrue(afterCentral == (0, 1), afterConfig == (1, 1))
      },
      test("the SMTP server saved in central is refused on loopback, the one of auth.conf is not held to the policy") {
        for
          smtp <- ZIO.acquireRelease(ZIO.succeed(FakeSmtpServer.start()))(server => ZIO.succeed(server.close()))
          local = centralSmtp.copy(host = "localhost", port = smtp.port, startTls = false, password = None)
          fromCentral <- resolved(withNeither, Fetched(Some(Saved(local, enabled = true)), None))
          fromConfig <- resolved(
            withBoth.copy(smtp = withBoth.smtp.map(_.copy(host = "localhost", port = smtp.port, startTls = false, password = ""))),
            Fetched.empty,
          )
          refused <- ZIO.foreach(fromCentral.email)(_.send(Email("user@example.com"), "hello").either)
          sent <- ZIO.foreach(fromConfig.email)(_.send(Email("user@example.com"), "hello").either)
        yield assertTrue(refused.exists(_.isLeft), sent.exists(_.isRight), smtp.messages.size == 1)
      }.provide(TestClient.layer, Scope.default),
    ),
    suite("a missing endpoint")(
      test("as the first answer means nothing is saved, so auth.conf is read") {
        for
          client <- ZIO.service[Client]
          sync = new CacheSource[Fetched]:
            override def getAll: Task[Fetched] = ZIO.fail(EndpointMissing())
          source <- DeliveryChannels.resolvedSource(config = withBoth, client = client, centralClient = client, sync = sync)
          r <- source.getAll
        yield assertTrue(r.emailOrigin == Origin.Config, r.smsOrigin == Origin.Config)
      },
      test("after central has answered once is a failed refresh, not nothing saved, so a switched-off channel stays off") {
        for
          client <- ZIO.service[Client]
          answers <- Ref.make(List[Task[Fetched]](
            ZIO.succeed(Fetched(Some(Saved(centralSmtp, enabled = false)), None)),
            ZIO.fail(EndpointMissing()),
          ))
          sync = new CacheSource[Fetched]:
            override def getAll: Task[Fetched] = answers.modify(list => list.head -> list.tail).flatten
          source <- DeliveryChannels.resolvedSource(config = withBoth, client = client, centralClient = client, sync = sync)
          first <- source.getAll
          second <- source.getAll.either
        yield assertTrue(first.emailOrigin == Origin.Off, second.left.exists(_.isInstanceOf[EndpointMissing]))
      },
      test("a refresh that hits it keeps the senders already built") {
        for
          client <- ZIO.service[Client]
          answers <- Ref.make(List[Task[Fetched]](
            ZIO.succeed(Fetched(Some(Saved(centralSmtp, enabled = false)), None)),
            ZIO.fail(EndpointMissing()),
          ))
          sync = new CacheSource[Fetched]:
            override def getAll: Task[Fetched] = answers.modify(list => list.head -> list.tail).flatten
          source <- DeliveryChannels.resolvedSource(config = withBoth, client = client, centralClient = client, sync = sync)
          channels <- DeliveryChannels.make(source, 1.hour)
          _ <- channels.refresh
          email <- channels.email
        yield assertTrue(email.isEmpty)
      },
    ).provide(TestClient.layer, Scope.default),
    suite("refresh")(
      test("serves the senders of the first load, then those of the next one") {
        for
          client <- ZIO.service[Client]
          current <- Ref.make(Fetched.empty)
          source = new CacheSource[DeliveryChannels.Resolved]:
            override def getAll: Task[DeliveryChannels.Resolved] =
              current.get.map(fetched => DeliveryChannels.resolve(config = withNeither, client = client, centralClient = client, central = fetched))
          channels <- DeliveryChannels.make(source, 1.hour)
          before <- channels.email
          _ <- current.set(Fetched(Some(Saved(centralSmtp, enabled = true)), None))
          _ <- channels.refresh
          after <- channels.email
          _ <- current.set(Fetched(Some(Saved(centralSmtp, enabled = false)), None))
          _ <- channels.refresh
          off <- channels.email
        yield assertTrue(before.isEmpty, after.isDefined, off.isEmpty)
      },
      test("keeps the senders it has when a reload fails, and does not fail the caller") {
        for
          client <- ZIO.service[Client]
          failing <- Ref.make(false)
          source = new CacheSource[DeliveryChannels.Resolved]:
            override def getAll: Task[DeliveryChannels.Resolved] =
              failing.get.flatMap:
                case true => ZIO.fail(RuntimeException("central is unreachable"))
                case false =>
                  ZIO.succeed(DeliveryChannels.resolve(config = withBoth, client = client, centralClient = client, central = Fetched.empty))
          channels <- DeliveryChannels.make(source, 1.hour)
          _ <- failing.set(true)
          _ <- channels.refresh
          email <- channels.email
          sms <- channels.sms
        yield assertTrue(email.isDefined, sms.isDefined)
      },
    ).provide(TestClient.layer, Scope.default),
  ) @@ silentLogging
