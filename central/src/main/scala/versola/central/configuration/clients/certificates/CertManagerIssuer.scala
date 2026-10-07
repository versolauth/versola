package versola.central.configuration.clients.certificates

import versola.central.CentralConfig.CertManagerConfig
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64

/** Signs through cert-manager: central creates a `CertificateRequest` in the cluster with its
  * own service account, waits for the issuer to answer, and reads the certificate off its status.
  *
  * What central needs is `create`, `get` and `delete` on `certificaterequests.cert-manager.io`
  * in one namespace and nothing else; the CA key stays with the issuer. Whether a request is
  * approved is cert-manager's call (its internal approver, or approver-policy), which is where
  * a policy over names and lifetimes belongs.
  */
object CertManagerIssuer:

  private val PollInterval: Duration = 500.millis
  private val Timeout: Duration = 60.seconds

  def make(config: CertManagerConfig, client: Client): Task[ClientCertificateIssuer] =
    for
      namespace <- config.namespace.fold(ZIO.attemptBlocking(Files.readString(Path.of(config.namespacePath)).trim))(ZIO.succeed(_))
      base = config.apiUrl.stripSuffix("/") + s"/apis/cert-manager.io/v1/namespaces/$namespace/certificaterequests"
      ssl = ClientSSLConfig.FromCertFile(config.caPath)
    yield new ClientCertificateIssuer:
      override def sign(request: CertificateSigningRequest): Task[String] =
        ZIO.scoped:
          for
            name <- create(base, request)
            certificate <- await(base, name).timeoutFail(RuntimeException(s"cert-manager did not sign request $name within $Timeout"))(Timeout)
              .ensuring(call(Request.delete(_), s"$base/$name").ignore)
          yield certificate

      private def token: Task[String] =
        ZIO.attemptBlocking(Files.readString(Path.of(config.tokenPath)).trim)

      private def call(build: URL => Request, url: String, body: Option[String] = None): Task[(Status, String)] =
        for
          bearer <- token
          target <- ZIO.fromEither(URL.decode(url)).mapError(error => RuntimeException(error.getMessage))
          base = build(target).addHeader(Header.Authorization.Bearer(bearer))
          withBody = body.fold(base)(text => base.withBody(Body.fromString(text)).addHeader(Header.ContentType(MediaType.application.json)))
          result <- ZIO.scoped(client.ssl(ssl).request(withBody).flatMap(r => r.body.asString.map(r.status -> _)))
        yield result

      private def create(base: String, request: CertificateSigningRequest): Task[String] =
        val body = Json.Obj(
          "apiVersion" -> Json.Str("cert-manager.io/v1"),
          "kind" -> Json.Str("CertificateRequest"),
          "metadata" -> Json.Obj("generateName" -> Json.Str("versola-client-")),
          "spec" -> Json.Obj(
            "request" -> Json.Str(Base64.getEncoder.encodeToString(request.csrPem.getBytes(StandardCharsets.UTF_8))),
            "isCA" -> Json.Bool(false),
            "duration" -> Json.Str(s"${request.validity.toHours}h0m0s"),
            "usages" -> Json.Arr(Json.Str("digital signature"), Json.Str("key encipherment"), Json.Str("client auth")),
            "issuerRef" -> Json.Obj(
              "name" -> Json.Str(config.issuerName),
              "kind" -> Json.Str(config.issuerKind),
              "group" -> Json.Str(config.issuerGroup),
            ),
          ),
        ).toJson
        call(Request.post(_, Body.empty), base, Some(body)).flatMap: (status, text) =>
          if !status.isSuccess then ZIO.fail(RuntimeException(s"cert-manager refused the request with $status: $text"))
          else
            ZIO.fromEither(text.fromJson[Json.Obj].left.map(RuntimeException(_)))
              .flatMap(obj => ZIO.fromOption(obj.get("metadata").flatMap(_.asObject).flatMap(_.get("name")).flatMap(_.asString))
                .orElseFail(RuntimeException("cert-manager's answer names no request")))

      private def await(base: String, name: String): Task[String] =
        call(Request.get(_), s"$base/$name").flatMap: (status, text) =>
          if !status.isSuccess then ZIO.fail(RuntimeException(s"reading request $name failed with $status: $text"))
          else
            ZIO.fromEither(text.fromJson[Json.Obj].left.map(RuntimeException(_))).flatMap: obj =>
              val conditions = obj.get("status").flatMap(_.asObject).flatMap(_.get("conditions")).flatMap(_.asArray).map(_.toList).getOrElse(Nil)
                .flatMap(_.asObject)
              def condition(kind: String) =
                conditions.find(_.get("type").flatMap(_.asString).contains(kind))
              def isTrue(c: Json.Obj) = c.get("status").flatMap(_.asString).contains("True")
              def reason(c: Json.Obj) = c.get("message").flatMap(_.asString).getOrElse("no reason given")
              condition("Denied").filter(isTrue) match
                case Some(denied) => ZIO.fail(RuntimeException(s"cert-manager denied request $name: ${reason(denied)}"))
                case None =>
                  condition("Ready").filter(isTrue) match
                    case Some(_) =>
                      ZIO.fromOption(obj.get("status").flatMap(_.asObject).flatMap(_.get("certificate")).flatMap(_.asString))
                        .orElseFail(RuntimeException(s"request $name is Ready but carries no certificate"))
                        .map(encoded => String(Base64.getDecoder.decode(encoded), StandardCharsets.UTF_8))
                    case None =>
                      condition("Ready").filter(c => c.get("reason").flatMap(_.asString).contains("Failed")) match
                        case Some(failed) => ZIO.fail(RuntimeException(s"cert-manager failed request $name: ${reason(failed)}"))
                        case None => ZIO.sleep(PollInterval) *> await(base, name)
