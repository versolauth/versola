package versola.util.audit

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWKSet, KeyUse, RSAKey}
import versola.util.JWT
import zio.*
import zio.http.*
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/** The signature is made over the call as edge builds it and checked over the call as central's server reads it. Between the two is
  * a real HTTP client and a real server, which encode and decode the path and the query: this proves they agree on what the call is,
  * for values that are hard to get through a query string. */
object ActorSignatureRoundTripSpec extends ZIOSpecDefault:

  private val (privateKey, keys) =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    val jwk = RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey]).keyID("k1").algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).build()
    (pair.getPrivate.asInstanceOf[RSAPrivateKey], JWT.PublicKeys(JWKSet(jwk)))

  /** What a server answers for a call: whether the signature it carries is of the call it received. */
  private val routes = Routes(
    Method.ANY / trailing -> handler { (_: Path, request: Request) =>
      request.url.queryParams.getAll(ActorParams.Signature).headOption match
        case None => ZIO.succeed(Response.text("unsigned"))
        case Some(token) =>
          ActorSignature
            .verify(token, keys, request.method.name, ActorSignature.pathOf(request.url.path.segments), request.url.queryParams)
            .fold(_ => Response.text("invalid"), _ => Response.text("valid"))
            // the server runs in a fiber of its own, which the test's clock does not reach
            .withClock(Clock.ClockLive)
    },
  )

  private def answer(method: Method, path: String, query: QueryParams): ZIO[Client & Server, Throwable, String] =
    for
      port <- ZIO.serviceWithZIO[Server](_.port)
      url = URL(Path.decode(path), kind = URL.Location.Absolute(Scheme.HTTP, "localhost", Some(port))).setQueryParams(query)
      token <- ActorSignature.sign("edge-a", "k1", privateKey, method.name, ActorSignature.pathOf(url.path.segments), url.queryParams)
      response <- ZIO.serviceWithZIO[Client](_.batched(Request(method = method, url = url.addQueryParam(ActorParams.Signature, token))))
      body <- response.body.asString
    yield body

  private val awkward = List(
    "plain" -> QueryParams("actorId" -> "0192b3c0-0000-7000-8000-0000000000aa", "actorIp" -> "203.0.113.7"),
    "spaces and plus" -> QueryParams("actorId" -> "a b+c", "q" -> "x y"),
    "reserved characters" -> QueryParams("a" -> "1&b=2", "c" -> "x=y;z", "d" -> "?#/\\"),
    "percent and unicode" -> QueryParams("a" -> "100%", "b" -> "%2B", "c" -> "Привет ✓", "d" -> "é"),
    "empty" -> QueryParams("a" -> "", "b" -> "1"),
    "repeated" -> QueryParams("b" -> "1").addQueryParam("b", "2"),
    "an email" -> QueryParams("email" -> "first+last@example.com", "actorId" -> "u"),
  )

  private val awkwardTests = awkward.map: (name, query) =>
    test(s"a signed call is the call the server reads: $name") {
      answer(Method.DELETE, "/configuration/clients", query).map(body => assertTrue(body == "valid"))
    }

  private val pathTest = test("the path is read the same: a trailing slash and an encoded character") {
    for
      slash <- answer(Method.GET, "/users/audit/", QueryParams("id" -> "u"))
      encoded <- answer(Method.GET, "/configuration/a%20b", QueryParams("id" -> "u"))
    yield assertTrue(slash == "valid", encoded == "valid")
  }

  def spec = (suite("ActorSignature across a real HTTP client and server")(awkwardTests :+ pathTest) @@
    TestAspect.beforeAll(Server.install(routes).unit) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds))
    .provideShared(Server.defaultWith(_.port(0)), Client.default)
