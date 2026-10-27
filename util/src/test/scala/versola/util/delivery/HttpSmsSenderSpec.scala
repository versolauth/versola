package versola.util.delivery

import versola.util.Phone
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.silentLogging

import java.util.concurrent.TimeoutException

object HttpSmsSenderSpec extends ZIOSpecDefault:

  private val phone = Phone("+12025551234")
  private val message = "Use 123456 to login"

  private def settings(
      username: Option[String] = None,
      password: Option[String] = None,
      body: Map[String, String] = Map("login" -> "versola", "phones" -> "{{phone}}", "mes" -> "{{message}}"),
      method: Method = Method.POST,
  ) =
    SmsProviderSettings(method = method, url = URL.empty, username = username, password = password, body = body)

  /** Answers every request with `status` and records it. */
  private def capture(status: Status = Status.Ok): ZIO[TestClient, Nothing, Ref[Option[Request]]] =
    for
      seen <- Ref.make(Option.empty[Request])
      _ <- TestClient.addRoutes(
        Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(Response.status(status))).toRoutes,
      )
    yield seen

  def spec = suite("HttpSmsSender")(
    test("renders the placeholders into the JSON body and sends it with the configured method") {
      for
        seen <- capture()
        client <- ZIO.service[Client]
        _ <- SmsSender.http(client, settings()).send(phone, message)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
        bodyString <- request.body.asString
        body <- ZIO.fromEither(bodyString.fromJson[Map[String, String]])
      yield assertTrue(
        request.method == Method.POST,
        request.header(Header.ContentType).exists(_.mediaType == MediaType.application.json),
        body == Map("login" -> "versola", "phones" -> phone, "mes" -> message),
      )
    },
    test("sends with the method the settings name") {
      for
        seen <- capture()
        client <- ZIO.service[Client]
        _ <- SmsSender.http(client, settings(method = Method.PUT)).send(phone, message)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
      yield assertTrue(request.method == Method.PUT)
    },
    test("sends HTTP Basic when both a username and a password are present") {
      for
        seen <- capture()
        client <- ZIO.service[Client]
        _ <- SmsSender.http(client, settings(username = Some("user"), password = Some("pass"))).send(phone, message)
        request <- seen.get.someOrFail(RuntimeException("No request captured"))
      yield assertTrue(request.header(Header.Authorization).contains(Header.Authorization.Basic("user", "pass")))
    },
    test("sends no Authorization header when the credentials are absent, or only one of them is present") {
      for
        seen <- capture()
        client <- ZIO.service[Client]
        _ <- SmsSender.http(client, settings()).send(phone, message)
        withNone <- seen.get.someOrFail(RuntimeException("No request captured"))
        _ <- SmsSender.http(client, settings(username = Some("user"))).send(phone, message)
        withUsernameOnly <- seen.get.someOrFail(RuntimeException("No request captured"))
        _ <- SmsSender.http(client, settings(password = Some("pass"))).send(phone, message)
        withPasswordOnly <- seen.get.someOrFail(RuntimeException("No request captured"))
      yield assertTrue(
        withNone.header(Header.Authorization).isEmpty,
        withUsernameOnly.header(Header.Authorization).isEmpty,
        withPasswordOnly.header(Header.Authorization).isEmpty,
      )
    },
    test("fails when the provider answers with a status that is not a success, naming only the status") {
      for
        _ <- capture(Status.ServiceUnavailable)
        client <- ZIO.service[Client]
        result <- SmsSender.http(client, settings()).send(phone, message).either
      yield assertTrue(
        result.left.map(_.getMessage) == Left("OTP provider responded with 503"),
        result.left.exists(_.isInstanceOf[SmsSender.ProviderRejected]),
      )
    },
    test("gives up on a provider that never answers, naming the timeout and not the provider") {
      for
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.never).toRoutes)
        client <- ZIO.service[Client]
        sending <- SmsSender.http(client, settings()).send(phone, message).either.fork
        // The timeout has registered its sleep once the fiber has got that far; adjusting before that moves nothing.
        _ <- TestClock.sleeps.repeatUntil(_.nonEmpty)
        _ <- TestClock.adjust(HttpSmsSender.Timeout + 1.second)
        result <- sending.join
      yield assertTrue(result.left.exists(_.isInstanceOf[TimeoutException]))
    },
    test("does not print the password in its settings") {
      val printed = settings(username = Some("user"), password = Some("s3cret-pass")).toString
      assertTrue(!printed.contains("s3cret-pass"))
    },
  ).provide(TestClient.layer) @@ silentLogging
