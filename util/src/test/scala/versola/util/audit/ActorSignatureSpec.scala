package versola.util.audit

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWKSet, KeyUse, RSAKey}
import versola.util.JWT
import zio.*
import zio.http.QueryParams
import zio.test.*

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

object ActorSignatureSpec extends ZIOSpecDefault:

  private case class Keys(privateKey: RSAPrivateKey, public: JWT.PublicKeys)

  private def generate(keyId: String): Keys =
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val pair = generator.generateKeyPair()
    val jwk = RSAKey.Builder(pair.getPublic.asInstanceOf[RSAPublicKey]).keyID(keyId).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).build()
    Keys(pair.getPrivate.asInstanceOf[RSAPrivateKey], JWT.PublicKeys(JWKSet(jwk)))

  private val edge = generate("k1")
  private val rotated = generate("k2")
  private val stranger = generate("k1")

  private val query = QueryParams("actorId" -> "u1", "actorIp" -> "203.0.113.7", "clientId" -> "web")
  private val path = ActorSignature.pathOf(List("configuration", "clients"))

  private def sign(keys: Keys, keyId: String, method: String = "DELETE", path: String = path, query: QueryParams = query) =
    ActorSignature.sign("edge-a", keyId, keys.privateKey, method, path, query)

  private def verifies(token: String, keys: Keys, method: String = "DELETE", path: String = path, query: QueryParams = query) =
    ActorSignature.verify(token, keys.public, method, path, query).either

  def spec = suite("ActorSignature")(
    test("a signature verifies for exactly the call it was made for, and names the edge") {
      for
        token <- sign(edge, "k1")
        result <- verifies(token, edge)
        named <- ActorSignature.edgeOf(token)
      yield assertTrue(result.isRight, named == "edge-a")
    },
    test("the order of the parameters, and the signature parameter itself, are not part of the call") {
      for
        token <- sign(edge, "k1")
        reordered = QueryParams("clientId" -> "web", "actorIp" -> "203.0.113.7", "actorId" -> "u1")
        withSignature = query.addQueryParam(ActorParams.Signature, token)
        a <- verifies(token, edge, query = reordered)
        b <- verifies(token, edge, query = withSignature)
      yield assertTrue(a.isRight, b.isRight)
    },
    test("a parameter that is changed, added or removed, or moved to another value of the same text, makes it invalid") {
      for
        token <- sign(edge, "k1")
        changed <- verifies(token, edge, query = QueryParams("actorId" -> "u2", "actorIp" -> "203.0.113.7", "clientId" -> "web"))
        added <- verifies(token, edge, query = query.addQueryParam("clientId", "other"))
        removed <- verifies(token, edge, query = QueryParams("actorId" -> "u1", "clientId" -> "web"))
        // `a=bc` and `ab=c` are not the same query
        glued <- ZIO.foreach(List(QueryParams("a" -> "bc"), QueryParams("ab" -> "c")))(q =>
          sign(edge, "k1", query = q).flatMap(verifies(_, edge, query = QueryParams("ab" -> "c"))),
        )
      yield assertTrue(changed.isLeft, added.isLeft, removed.isLeft, glued.map(_.isRight) == List(false, true))
    },
    test("it does not hold for another method or another path") {
      for
        token <- sign(edge, "k1")
        method <- verifies(token, edge, method = "PUT")
        elsewhere <- verifies(token, edge, path = ActorSignature.pathOf(List("configuration", "scopes")))
        slash <- verifies(token, edge, path = ActorSignature.pathOf(List("configuration", "clients", "")))
      yield assertTrue(method.isLeft, elsewhere.isLeft, slash.isRight)
    },
    test("a signature made with another key than the one the registration holds, even under the same key id, is invalid") {
      for
        token <- sign(stranger, "k1")
        result <- verifies(token, edge)
      yield assertTrue(result.isLeft)
    },
    test("while an edge rotates its key, a signature by the previous key still verifies, and one by a key that is in neither does not") {
      val both = JWT.PublicKeys(JWKSet(java.util.List.of(edge.public.keys.getKeys.get(0), rotated.public.keys.getKeys.get(0))))
      for
        old <- sign(edge, "k1")
        fresh <- sign(rotated, "k2")
        unknown <- sign(stranger, "k3")
        results <- ZIO.foreach(List(old, fresh, unknown))(ActorSignature.verify(_, both, "DELETE", path, query).either)
      yield assertTrue(results.map(_.isRight) == List(true, true, false))
    },
    test("it is invalid once its lifetime has passed, and not before") {
      for
        token <- sign(edge, "k1")
        _ <- TestClock.adjust(ActorSignature.Lifetime.minus(1.second))
        before <- verifies(token, edge)
        _ <- TestClock.adjust(2.seconds)
        after <- verifies(token, edge)
      yield assertTrue(before.isRight, after.isLeft)
    },
    test("a token that is no signature of an actor is not one: the token an edge syncs with is of another type") {
      for
        sync <- JWT.serialize(
          JWT.Claims("edge", "edge", List("central"), zio.json.ast.Json.Obj()),
          1.minute,
          JWT.Signature.Asymmetric(JWT.Algorithm.RS256, "k1", edge.privateKey),
          headers = Map("edge_id" -> "edge-a"),
        )
        result <- verifies(sync, edge)
        garbage <- verifies("not.a.jwt", edge)
        header <- ActorSignature.edgeOf("garbage").either
      yield assertTrue(result.isLeft, garbage.isLeft, header.isLeft)
    },
  )
