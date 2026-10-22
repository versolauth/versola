package versola.e2e.flows.est

import versola.e2e.support.{*, given}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.sys.process.{Process, ProcessLogger}

/** RFC 7030 EST: a bank's `tls_client_auth` client obtains its mTLS certificate from auth with a one-time token,
  * authenticates with it at `/token` on auth's mutual-TLS listener, and renews it there with the certificate it has --
  * a certificate central issued through the CA it holds, never with the client's key.
  *
  * What only this level shows is the wiring: registration through central's admin API, the token, auth's request checks,
  * auth's call to central, the CA behind central, and the certificate that comes out being one auth's real handshake then
  * accepts. The client side is `openssl`, as in [[EdgeCertificate]]: the key and the request are the client's, made here.
  *
  * The local stack (`scripts/gen-env.scala`) gives a chain of 20 seconds (`client-identity.max-age`), which is what lets
  * "a chain ends, an operator extends it" be watched. A test that renews therefore does it straight after enrolling.
  */
object EstEnrollmentSpec extends ZIOSpec[Flows.Setups & CentralApi]:

  /** The `E2ESpec` environment and central's admin API beside it, both on the same client. */
  override val bootstrap: ZLayer[Any, Any, Flows.Setups & CentralApi] =
    val common = E2EConfig.live ++ Client.default
    (common >>> OAuthClient.live >>> Flows.layer) ++ (common >>> CentralApi.live)

  private def setup(id: Flows.Id): ZIO[Flows.Setups, Nothing, (Flows.Setup, OAuthClient)] =
    ZIO.serviceWith[Flows.Setups](s => s(id) -> s.client)

  private val tenant = Fixtures.suiteTenant

  private def uid: UIO[String] = ZIO.succeed(UUID.randomUUID().toString.replace("-", "").take(8))

  private val central: URIO[CentralApi, CentralApi] = ZIO.service[CentralApi]

  // ── the client's side, with openssl ────────────────────────────────────────────────────────────

  private def openssl(args: String*): Task[Array[Byte]] =
    ZIO.attemptBlocking:
      val out = java.io.ByteArrayOutputStream()
      val err = StringBuilder()
      val code = (Process("openssl" +: args) #> out).run(ProcessLogger(_ => (), line => err.append(line).append('\n'))).exitValue()
      if code != 0 then throw RuntimeException(s"openssl ${args.mkString(" ")} failed: $err")
      out.toByteArray

  /** A key the client generated: its own file, and the PKCS#8 form a TLS stack reads back. */
  private case class Key(dir: Path, ec: Path, pkcs8: Path)

  private def newKey(algorithm: String = "ec"): Task[Key] =
    for
      dir <- ZIO.attemptBlocking(Files.createTempDirectory("est-e2e"))
      ec = dir.resolve(s"${UUID.randomUUID()}.pem")
      pkcs8 = dir.resolve(s"${UUID.randomUUID()}-pkcs8.pem")
      _ <- algorithm match
        case "rsa1024" => openssl("genrsa", "-out", ec.toString, "1024")
        case _ => openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", ec.toString)
      _ <- openssl("pkcs8", "-topk8", "-nocrypt", "-in", ec.toString, "-out", pkcs8.toString)
    yield Key(dir, ec, pkcs8)

  /** `CN=a,OU=b,O=c` (RFC 4514) as `openssl req -subj` has it: the RDNs in the order they are encoded, the reverse. */
  private def opensslSubject(rfc4514: String): String = rfc4514.split(",").reverse.map("/" + _).mkString

  /** The request a client sends: base64 of the DER (§4.2.1), for `subject` and signed by `key`. */
  private def csr(subject: String, key: Key): Task[String] =
    openssl("req", "-new", "-key", key.ec.toString, "-subj", opensslSubject(subject), "-outform", "DER")
      .map(Base64.getMimeEncoder(64, "\n".getBytes).encodeToString)

  /** The certificates of a certs-only PKCS#7 (§4.1.3) as PEM, in the order `openssl` lists them. */
  private def certificates(pkcs7Base64: String): Task[List[String]] =
    for
      dir <- ZIO.attemptBlocking(Files.createTempDirectory("est-e2e-p7"))
      file = dir.resolve("response.p7")
      _ <- ZIO.attemptBlocking(Files.write(file, Base64.getMimeDecoder.decode(pkcs7Base64)))
      text <- openssl("pkcs7", "-inform", "DER", "-in", file.toString, "-print_certs").map(String(_, StandardCharsets.UTF_8))
    yield "(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----".r.findAllIn(text).toList

  private def subjectOf(certificate: String): Task[String] =
    ZIO.attemptBlocking:
      val in = java.io.ByteArrayInputStream(certificate.getBytes(StandardCharsets.UTF_8))
      val out = java.io.ByteArrayOutputStream()
      (Process(Seq("openssl", "x509", "-noout", "-subject", "-nameopt", "RFC2253")) #< in #> out).!
      String(out.toByteArray, StandardCharsets.UTF_8).trim.stripPrefix("subject=").trim

  private def serialOf(certificate: String): Task[String] =
    ZIO.attemptBlocking:
      val in = java.io.ByteArrayInputStream(certificate.getBytes(StandardCharsets.UTF_8))
      val out = java.io.ByteArrayOutputStream()
      (Process(Seq("openssl", "x509", "-noout", "-serial")) #< in #> out).!
      String(out.toByteArray, StandardCharsets.UTF_8).trim

  /** The client's identity: the certificate EST gave it and the key it generated for it. */
  private case class Identity(certificate: String, key: Key):
    lazy val certificateFile: Path =
      val file = key.dir.resolve(s"${UUID.randomUUID()}.crt")
      Files.writeString(file, certificate)
      file

    def ssl(trust: String): ClientSSLConfig =
      ClientSSLConfig.FromClientAndServerCert(
        ClientSSLConfig.FromCertFile(trust),
        ClientSSLCertConfig.FromClientCertFile(certificateFile.toString, key.pkcs8.toString),
      )

  // ── requests ───────────────────────────────────────────────────────────────────────────────────

  private def send(request: Request, ssl: Option[ClientSSLConfig] = None): Task[(Response, String)] =
    ZIO.scoped:
      for
        client <- Client.default.build.map(_.get[Client])
        response <- Client.batched(request).provide(ZLayer.succeed(ssl.fold(client)(client.ssl)))
        body <- response.body.asString
      yield (response, body)

  private def estPost(url: String, csrBody: String, authorization: Option[Header.Authorization], ssl: Option[ClientSSLConfig] = None) =
    val request = Request.post(url, Body.fromString(csrBody)).addHeader(Header.Custom("Content-Type", "application/pkcs10"))
    send(authorization.fold(request)(request.addHeader(_)), ssl)

  private def tokenOver(identity: Identity, auth: OAuthClient, clientId: String): Task[TokenResult] =
    ZIO.scoped:
      for
        client <- Client.default.build.map(_.get[Client])
        request = Request.post(
          s"${auth.authMutualTlsUrl}/token",
          Body.fromString(s"grant_type=client_credentials&client_id=$clientId&scope=openid"),
        ).addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))
        result <- Client.batched(request).provide(ZLayer.succeed(client.ssl(identity.ssl(auth.authMutualTlsTrustedCertificates))))
          .flatMap(TokenResult.parse)
      yield result

  // ── fixtures ───────────────────────────────────────────────────────────────────────────────────

  private def registerBank(central: CentralApi, id: String, extra: (String, Json)*) =
    central.post(
      "/configuration/clients/service",
      Json.Obj(
        // `extra` overrides, so that a member is not sent twice.
        Chunk.from(Map(
          "tenantId" -> Json.Str(tenant),
          "id" -> Json.Str(id),
          "clientName" -> Json.Obj("en" -> Json.Str("EST bank")),
          "allowedScopes" -> Json.Arr(Json.Str("openid")),
          "accessTokenTtl" -> Json.Num(300),
          "authMethod" -> Json.Str("tls_client_auth"),
          "certificateBoundAccessTokens" -> Json.Bool(true),
          "estEnrollment" -> Json.Bool(true),
        ) ++ extra),
      ),
    )

  /** A token and the subject the client must ask for. */
  private def enrolmentToken(central: CentralApi, clientId: String): Task[(String, String)] =
    for
      created <- central.postEmpty("/configuration/clients/est/enrollment-tokens", "clientId" -> clientId)
      body <- created.obj
      token <- ZIO.fromOption(body.str("token")).orElseFail(RuntimeException(s"no token in ${created.body}"))
      subject <- ZIO.fromOption(body.str("subject")).orElseFail(RuntimeException(s"no subject in ${created.body}"))
    yield (token, subject)

  /** A registered client, its token and the subject; cleaned up either way. */
  private def withBank[A](use: (CentralApi, OAuthClient, String, String, String) => Task[A]): ZIO[Flows.Setups & CentralApi, Throwable, A] =
    for
      (_, auth) <- setup(Flows.Id.LoginPassword)
      api <- central
      id <- uid.map(suffix => s"est-bank-$suffix")
      result <- ZIO.acquireReleaseWith(
        registerBank(api, id).tap(r => ZIO.fail(RuntimeException(s"registration: ${r.status} ${r.body}")).unless(r.status.isSuccess)),
      )(_ => api.delete("/configuration/clients", "clientId" -> id).ignore) { _ =>
        enrolmentToken(api, id).flatMap((token, subject) => use(api, auth, id, token, subject))
      }
    yield result

  private def enrol(auth: OAuthClient, id: String, token: String, subject: String, key: Key): Task[(Response, String)] =
    csr(subject, key).flatMap(body => estPost(s"${auth.issuer}/.well-known/est/simpleenroll", body, Some(Header.Authorization.Basic(id, token))))

  private def issuedIdentity(response: (Response, String), key: Key, id: String): Task[Identity] =
    for
      _ <- ZIO.fail(RuntimeException(s"EST answered ${response._1.status}: ${response._2}")).unless(response._1.status == Status.Ok)
      chain <- certificates(response._2)
      subjects <- ZIO.foreach(chain)(subjectOf)
      leaf <- ZIO.fromOption(chain.zip(subjects).collectFirst { case (pem, subject) if subject.contains(s"CN=$id") => pem })
        .orElseFail(RuntimeException(s"no certificate for $id in $subjects"))
    yield Identity(leaf, key)

  /** Waits until auth serves the client on its mutual-TLS listener: the sync that carries a registration is asynchronous. */
  private def awaitServed(auth: OAuthClient, identity: Identity, id: String): Task[TokenResult.Success] =
    val attempt = auth.syncConfiguration() *> tokenOver(identity, auth, id).flatMap:
      case success: TokenResult.Success => ZIO.succeed(success)
      case failure: TokenResult.Failure => ZIO.fail(RuntimeException(s"/token over mTLS: ${failure.response.status} ${failure.body}"))
    attempt.retry(Schedule.spaced(500.millis) && Schedule.recurs(60)).withClock(Clock.ClockLive)

  private def reenrollUrl(auth: OAuthClient) = s"${auth.authMutualTlsUrl}/.well-known/est/simplereenroll"

  private def renew(auth: OAuthClient, identity: Identity, subject: String, key: Key): Task[(Response, String)] =
    csr(subject, key).flatMap(body => estPost(reenrollUrl(auth), body, None, Some(identity.ssl(auth.authMutualTlsTrustedCertificates))))

  def spec = suite("EST (RFC 7030): a client's mTLS certificate")(
    test("enrol with a token, authenticate with the certificate, renew over mTLS; the old certificate works until it expires") {
      withBank: (_, auth, id, token, subject) =>
        for
          key1 <- newKey()
          enrolled <- enrol(auth, id, token, subject, key1)
          first <- issuedIdentity(enrolled, key1, id)
          // Straight away, while the chain of the local stack (20 s) is young.
          key2 <- newKey()
          renewed <- renew(auth, first, subject, key2)
          second <- issuedIdentity(renewed, key2, id)
          firstSubject <- subjectOf(first.certificate)
          secondSubject <- subjectOf(second.certificate)
          firstSerial <- serialOf(first.certificate)
          secondSerial <- serialOf(second.certificate)
          // The certificate EST issued is the one auth's handshake accepts, and the client authenticates by it.
          withFirst <- awaitServed(auth, first, id)
          withSecond <- awaitServed(auth, second, id)
        yield assertTrue(
          enrolled._1.status == Status.Ok,
          enrolled._1.rawHeader("Content-Type").exists(_.startsWith("application/pkcs7-mime")),
          enrolled._1.rawHeader("Content-Transfer-Encoding").contains("base64"),
          enrolled._1.rawHeader("Versola-Chain-Ends-At").isDefined,
          firstSubject == subject,
          secondSubject == subject,
          firstSerial != secondSerial,
          withFirst.accessToken.nonEmpty,
          withSecond.accessToken.nonEmpty,
        )
    },
    test("a token is good once, and a wrong, missing or other client's token is 401") {
      withBank: (central, auth, id, token, subject) =>
        for
          other <- uid.map(suffix => s"est-bank-$suffix")
          _ <- registerBank(central, other)
          otherToken <- enrolmentToken(central, other).map(_._1)
          key <- newKey()
          body <- csr(subject, key)
          url = s"${auth.issuer}/.well-known/est/simpleenroll"
          anonymous <- estPost(url, body, None)
          wrong <- estPost(url, body, Some(Header.Authorization.Basic(id, "not-a-token")))
          otherClients <- estPost(url, body, Some(Header.Authorization.Basic(id, otherToken)))
          first <- estPost(url, body, Some(Header.Authorization.Basic(id, token)))
          again <- estPost(url, body, Some(Header.Authorization.Basic(id, token)))
          freshToken <- enrolmentToken(central, id).map(_._1)
          bearer <- estPost(url, body, Some(Header.Authorization.Bearer(freshToken)))
          _ <- central.delete("/configuration/clients", "clientId" -> other).ignore
        yield assertTrue(
          anonymous._1.status == Status.Unauthorized,
          anonymous._1.rawHeader("WWW-Authenticate").exists(_.startsWith("Basic")),
          wrong._1.status == Status.Unauthorized,
          otherClients._1.status == Status.Unauthorized,
          first._1.status == Status.Ok,
          again._1.status == Status.Unauthorized,
          // a Bearer token carries no user name: the token alone says whose it is
          bearer._1.status == Status.Ok,
        )
    },
    test("a request for another subject, or a weak key, is 400 and does not cost the token") {
      withBank: (_, auth, id, token, subject) =>
        for
          key <- newKey()
          weak <- newKey("rsa1024")
          anotherSubject <- enrol(auth, id, token, "CN=somebody-else,O=Versola", key)
          weakKey <- enrol(auth, id, token, subject, weak)
          good <- enrol(auth, id, token, subject, key)
        yield assertTrue(
          anotherSubject._1.status == Status.BadRequest,
          anotherSubject._2.contains(subject),
          weakKey._1.status == Status.BadRequest,
          good._1.status == Status.Ok,
        )
    },
    test("re-enrolment is served on the mutual-TLS listener alone, for a certificate EST issued, and with a new key") {
      withBank: (_, auth, id, token, subject) =>
        for
          key1 <- newKey()
          first <- enrol(auth, id, token, subject, key1).flatMap(issuedIdentity(_, key1, id))
          // not on the main listener, where a certificate in a header could be anyone's
          main <- csr(subject, key1).flatMap: body =>
            estPost(
              s"${auth.issuer}/.well-known/est/simplereenroll",
              body,
              None,
            ).map(_._1.status)
          // the key of the certificate it replaces
          reused <- renew(auth, first, subject, key1)
          // another subject
          another <- newKey().flatMap(key => renew(auth, first, "CN=somebody-else,O=Versola", key))
          // a certificate the listener trusts but EST did not issue: the one e2e's own client presents
          foreign = Identity(
            java.nio.file.Files.readString(Path.of(auth.authMutualTlsClientCertificate)),
            Key(Path.of(auth.authMutualTlsClientKey).getParent, Path.of(auth.authMutualTlsClientKey), Path.of(auth.authMutualTlsClientKey)),
          )
          fresh <- newKey()
          notIssuedByEst <- renew(auth, foreign, subject, fresh)
          withoutCertificate <- csr(subject, fresh).flatMap: body =>
            estPost(reenrollUrl(auth), body, None, Some(ClientSSLConfig.FromCertFile(auth.authMutualTlsTrustedCertificates))).either
        yield assertTrue(
          main == Status.NotFound,
          reused._1.status == Status.BadRequest,
          reused._2.contains("new key"),
          another._1.status == Status.BadRequest,
          notIssuedByEst._1.status == Status.Unauthorized,
          // the handshake itself refuses a connection with no certificate
          withoutCertificate.isLeft,
        )
    },
    test(
      "cacerts is served without credentials on both listeners, csrattrs says there are none, and a client that holds a secret is not registered for EST",
    ) {
      for
        (_, auth) <- setup(Flows.Id.LoginPassword)
        api <- central
        main <- send(Request.get(s"${auth.issuer}/.well-known/est/cacerts"))
        mutual <- send(
          Request.get(s"${auth.authMutualTlsUrl}/.well-known/est/cacerts"),
          Some(
            ClientSSLConfig.FromClientAndServerCert(
              ClientSSLConfig.FromCertFile(auth.authMutualTlsTrustedCertificates),
              ClientSSLCertConfig.FromClientCertFile(auth.authMutualTlsClientCertificate, auth.authMutualTlsClientKey),
            ),
          ),
        )
        attrs <- send(Request.get(s"${auth.issuer}/.well-known/est/csrattrs"))
        chain <- certificates(main._2)
        id <- uid.map(suffix => s"est-secret-$suffix")
        refused <- registerBank(api, id, "authMethod" -> Json.Str("client_secret"))
      yield assertTrue(
        main._1.status == Status.Ok,
        main._1.rawHeader("Content-Type").exists(_.contains("certs-only")),
        chain.nonEmpty,
        mutual._1.status == Status.Ok,
        attrs._1.status == Status.NoContent,
        refused.status == Status.BadRequest,
        refused.body.contains("estEnrollment"),
      )
    },
    test("a chain ends at max-age, renewing is refused until an operator extends it, and then it works") {
      withBank: (central, auth, id, token, subject) =>
        for
          key <- newKey()
          first <- enrol(auth, id, token, subject, key).flatMap(issuedIdentity(_, key, id))
          // `client-identity.max-age` is 20 seconds in the local stack: renew until the chain is refused.
          refusal <- ZIO.iterate(Option.empty[(Response, String)])(_.isEmpty): _ =>
            newKey().flatMap(renew(auth, first, subject, _)).flatMap: answer =>
              if answer._1.status == Status.Forbidden then ZIO.succeed(Some(answer))
              else
                ZIO.fail(RuntimeException(s"renewal answered ${answer._1.status}: ${answer._2}")).unless(answer._1.status == Status.Ok) *>
                  ZIO.sleep(2.seconds).as(None)
          .map(_.get).timeoutFail(RuntimeException("the chain did not end within a minute"))(60.seconds).withClock(Clock.ClockLive)
          listed <- central.get("/configuration/clients", "tenantId" -> tenant).flatMap(_.items("clients"))
            .map(_.find(_.str("id").contains(id)))
          extended <- central.postEmpty("/configuration/clients/est/extend", "clientId" -> id)
          afterwards <- newKey().flatMap(renew(auth, first, subject, _))
        yield assertTrue(
          refusal._2.contains("extend"),
          // the console and the API show the state
          listed.flatMap(_.obj("est")).exists(_.str("chainEndsAt").isDefined),
          extended.status == Status.NoContent,
          afterwards._1.status == Status.Ok,
        )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(180.seconds)
