package versola.central

import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalamock.stubs.{Stub, ZIOStubs}
import versola.central.configuration.edges.{EdgeGroup, EdgeGroupKind, EdgeId, EdgeRecord, EdgeService}
import versola.central.configuration.topology.{CellId, CellRecord, CellRegistry}
import versola.util.http.Unauthorized
import versola.util.{JWT, RsaKeyPair}
import zio.*
import zio.http.{Header, Request, URL}
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.Date

/** Who calls central (#1050): decided from the key that verifies the token, RS256 only, with `iss`, `aud` and `exp` checked. */
object AuthorizeInternalSpec extends ZIOSpecDefault, ZIOStubs:

  private val configured = TestCentralConfig.authKeyPair
  private val kz1Key = TestCentralConfig.newKeyPair("kz-1-key")
  private val kz1KeyNext = TestCentralConfig.newKeyPair("kz-1-key-next")
  private val kz2Key = TestCentralConfig.newKeyPair("kz-2-key")
  private val edgeKey = TestCentralConfig.newKeyPair("edge-key")
  private val edgeId = EdgeId("edge-1")

  private val kz1 = TestCentralConfig.cellRecord("kz-1", kz1Key, kz1KeyNext)
  private val kz2 = TestCentralConfig.cellRecord("kz-2", kz2Key)

  private val edgeRecord = EdgeRecord(
    edgeId,
    Some(edgeKey.toPublicJwk),
    None,
    requireDpopNonce = true,
    overloadPolicy = None,
    accessLogPolicy = None,
    rateLimitPolicies = None,
    policyMeta = None,
    groupId = EdgeGroup.DefaultId,
    groupKind = EdgeGroupKind.FirstParty,
    publicUrl = None,
  )

  private val claims = TestCentralConfig.authClaims

  private def signed(
      key: RsaKeyPair,
      claims: JWT.Claims = claims,
      cellId: Option[String] = None,
      ttl: Duration = 1.minute,
      algorithm: JWT.Algorithm = JWT.Algorithm.RS256,
  ): Task[String] =
    JWT.serialize(
      claims = claims,
      ttl = ttl,
      signature = JWT.Signature.Asymmetric(algorithm, key.keyId, key.privateKey),
      headers = cellId.fold(Map.empty[String, String])(id => Map("cell_id" -> id)),
    )

  /** Built by hand, for the tokens [[JWT.serialize]] will not make: no `exp`, HS256 under a kid of a registered key. */
  private def handMade(header: JWSHeader, set: JWTClaimsSet, sign: SignedJWT => Unit): String =
    val jwt = SignedJWT(header, set)
    sign(jwt)
    jwt.serialize()

  private def claimsSet(expires: Option[Date]) =
    val builder = JWTClaimsSet.Builder().issuer("auth").subject("auth").audience("central").jwtID("jti-1").issueTime(Date())
    expires.foreach(builder.expirationTime)
    builder.build()

  private def authorize(token: Option[String], cells: Vector[CellRecord] = Vector(kz1, kz2), config: CentralConfig = TestCentralConfig.config) =
    val edges: Stub[EdgeService] = stub[EdgeService]
    for
      _ <- edges.find.succeedsWith(Some(edgeRecord))
      request = token.fold(Request.get(URL.empty))(t => Request.get(URL.empty).addHeader(Header.Authorization.Bearer(t)))
      result <- authorizeInternal(request).provideEnvironment(
        ZEnvironment[CentralConfig](config) ++ ZEnvironment[EdgeService](edges) ++ ZEnvironment[CellRegistry](TestCentralConfig.registryOf(cells*)),
      ).exit
    yield result

  private def refused(result: Exit[Unauthorized.type, Caller]) = result == Exit.fail(Unauthorized)

  private def caller(result: Exit[Unauthorized.type, Caller]): Option[Caller] = result.foldExit(_ => None, Some(_))

  def spec = suite("authorizeInternal")(
    suite("who calls, from the key")(
      test("a token signed with a key of a registered cell is that cell, and the key is the one that verified") {
        for
          token <- signed(kz1Key, cellId = Some("kz-1"))
          result <- authorize(Some(token))
        yield assertTrue(
          caller(result).exists:
            case Caller.Cell(id, key) => id == CellId("kz-1") && key.getModulus == kz1Key.publicKey.getModulus
            case _ => false,
        )
      },
      test("either key of a cell that has two while one is being replaced is the cell's, each as itself") {
        for
          old <- signed(kz1Key, cellId = Some("kz-1")).flatMap(token => authorize(Some(token)))
          next <- signed(kz1KeyNext, cellId = Some("kz-1")).flatMap(token => authorize(Some(token)))
        yield assertTrue(
          caller(old).exists(_.authKey.exists(_.getModulus == kz1Key.publicKey.getModulus)),
          caller(next).exists(_.authKey.exists(_.getModulus == kz1KeyNext.publicKey.getModulus)),
          caller(old).flatMap(_.authKey) != caller(next).flatMap(_.authKey),
        )
      },
      test("a token of a cell that does not say which it is, is still the cell the key belongs to") {
        for
          token <- signed(kz2Key)
          result <- authorize(Some(token))
        yield assertTrue(caller(result).exists { case Caller.Cell(id, _) => id == CellId("kz-2"); case _ => false })
      },
      test("a token signed with a configured key is the configured auth, whatever cell it says it is") {
        for
          bare <- signed(configured).flatMap(token => authorize(Some(token)))
          claiming <- signed(configured, cellId = Some("kz-1")).flatMap(token => authorize(Some(token)))
        yield assertTrue(
          caller(bare).exists { case Caller.ConfiguredAuth(key) => key.getModulus == configured.publicKey.getModulus; case _ => false },
          // the header is only the caller's word: with a key that is no cell's, the cell is not taken from it
          caller(claiming).exists { case _: Caller.ConfiguredAuth => true; case _ => false },
        )
      },
      test("a key that is in both places is the cell's, not the configured auth's") {
        val sharing = TestCentralConfig.cellRecord("kz-1", configured)
        for
          token <- signed(configured)
          result <- authorize(Some(token), cells = Vector(sharing))
        yield assertTrue(caller(result).exists { case Caller.Cell(id, _) => id == CellId("kz-1"); case _ => false })
      },
      test("the configured auth is known with no cell registered at all") {
        for
          token <- signed(configured)
          result <- authorize(Some(token), cells = Vector.empty)
        yield assertTrue(caller(result).exists { case _: Caller.ConfiguredAuth => true; case _ => false })
      },
      test("a change of the cells is seen by the next call: a key registered a moment ago verifies, a cell deleted stops") {
        for
          token <- signed(kz2Key)
          before <- authorize(Some(token), cells = Vector(kz1))
          after <- authorize(Some(token), cells = Vector(kz1, kz2))
          deleted <- authorize(Some(token), cells = Vector(kz1))
        yield assertTrue(refused(before), caller(after).isDefined, refused(deleted))
      },
    ),
    suite("what is refused")(
      test("a token whose kid no key has") {
        val stranger = TestCentralConfig.newKeyPair("nobody")
        for
          token <- signed(stranger)
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("a token under a kid of a registered key, signed with another key: the key of a cell is not enough to pass for it") {
        val impostor = RsaKeyPair(keyId = kz1Key.keyId, publicKey = kz2Key.publicKey, privateKey = kz2Key.privateKey)
        for
          token <- signed(impostor, cellId = Some("kz-1"))
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("the key of another cell, with this cell's id in the header: the key says which cell it is, and the header disagrees") {
        for
          token <- signed(kz2Key, cellId = Some("kz-1"))
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("a token of a cell that is not registered, though its header names one that is") {
        val unregistered = TestCentralConfig.newKeyPair("kz-9-key")
        for
          token <- signed(unregistered, cellId = Some("kz-1"))
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("a token with the wrong issuer") {
        for
          token <- signed(kz1Key, claims = claims.copy(issuer = "central"))
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("a token for another audience, or for none") {
        for
          wrong <- signed(kz1Key, claims = claims.copy(audience = List("auth"))).flatMap(token => authorize(Some(token)))
          none <- signed(kz1Key, claims = claims.copy(audience = Nil)).flatMap(token => authorize(Some(token)))
          // an audience that has central among others is one of central's
          several <- signed(kz1Key, claims = claims.copy(audience = List("other", "central"))).flatMap(token => authorize(Some(token)))
        yield assertTrue(refused(wrong), refused(none), caller(several).isDefined)
      },
      test("an expired token") {
        for
          token <- signed(kz1Key, ttl = Duration.fromSeconds(-60))
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("a token with no expiry: JWT.deserialize takes it for one that never ends, and a service token must end") {
        val token = handMade(
          JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kz1Key.keyId).`type`(JOSEObjectType.JWT).build(),
          claimsSet(expires = None),
          _.sign(RSASSASigner(kz1Key.privateKey)),
        )
        for result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("the same token with an expiry is accepted: it is the expiry that decided") {
        val token = handMade(
          JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kz1Key.keyId).`type`(JOSEObjectType.JWT).build(),
          claimsSet(expires = Some(Date.from(Instant.now().plusSeconds(60)))),
          _.sign(RSASSASigner(kz1Key.privateKey)),
        )
        for result <- authorize(Some(token))
        yield assertTrue(caller(result).isDefined)
      },
      test("a PS256 token under a key that verifies it: the algorithm is RS256 and nothing else") {
        for
          token <- signed(kz1Key, algorithm = JWT.Algorithm.PS256)
          viaConfigured <- signed(configured, algorithm = JWT.Algorithm.PS256).flatMap(t => authorize(Some(t)))
          viaCell <- authorize(Some(token))
        yield assertTrue(refused(viaCell), refused(viaConfigured))
      },
      test("an HS256 token, under the old shared secret or any other, even with the kid of a registered key") {
        val secret = Array.fill[Byte](32)(7)
        val token = handMade(
          JWSHeader.Builder(JWSAlgorithm.HS256).keyID(kz1Key.keyId).`type`(JOSEObjectType.JWT).build(),
          claimsSet(expires = Some(Date.from(Instant.now().plusSeconds(60)))),
          _.sign(MACSigner(secret)),
        )
        val noKid = handMade(
          JWSHeader.Builder(JWSAlgorithm.HS256).`type`(JOSEObjectType.JWT).build(),
          claimsSet(expires = Some(Date.from(Instant.now().plusSeconds(60)))),
          _.sign(MACSigner(secret)),
        )
        for
          withKid <- authorize(Some(token))
          withoutKid <- authorize(Some(noKid))
        yield assertTrue(refused(withKid), refused(withoutKid))
      },
      test("a token signed by an edge's key but with no edge_id is not an edge's, and is nobody's") {
        for
          token <- signed(edgeKey)
          result <- authorize(Some(token))
        yield assertTrue(refused(result))
      },
      test("no credential, a Basic credential, and something that is not a JWT") {
        val basic = Header.Authorization.Basic("admin", "admin")
        for
          none <- authorize(None)
          garbage <- authorize(Some("not-a-jwt"))
          onlyBasic <- authorizeInternal(Request.get(URL.empty).addHeader(basic)).provideEnvironment(
            ZEnvironment[CentralConfig](TestCentralConfig.config) ++ ZEnvironment[EdgeService](stub[EdgeService]) ++
              ZEnvironment[CellRegistry](TestCentralConfig.noCells),
          ).exit
        yield assertTrue(refused(none), refused(garbage), refused(onlyBasic))
      },
    ),
    suite("an edge")(
      test("a token that names an edge and is signed with its key is that edge, as it was") {
        for
          token <- JWT.serialize(
            JWT.Claims("edge", "edge", List("central"), Json.Obj()),
            1.minute,
            JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKey.keyId, edgeKey.privateKey),
            headers = Map("edge_id" -> edgeId.toString),
          )
          result <- authorize(Some(token))
        yield assertTrue(caller(result).contains(Caller.Edge(edgeId)), caller(result).flatMap(_.authKey).isEmpty)
      },
      test("a token that names an edge but is signed with an auth's key, or a cell's, is refused") {
        for
          byConfigured <- JWT.serialize(
            claims,
            1.minute,
            JWT.Signature.Asymmetric(JWT.Algorithm.RS256, configured.keyId, configured.privateKey),
            headers = Map("edge_id" -> edgeId.toString),
          ).flatMap(token => authorize(Some(token)))
          byCell <- JWT.serialize(
            claims,
            1.minute,
            JWT.Signature.Asymmetric(JWT.Algorithm.RS256, kz1Key.keyId, kz1Key.privateKey),
            headers = Map("edge_id" -> edgeId.toString),
          ).flatMap(token => authorize(Some(token)))
        yield assertTrue(refused(byConfigured), refused(byCell))
      },
      test("authorizeAuth takes a cell and the configured auth and refuses an edge") {
        val edges = stub[EdgeService]
        def viaAuth(token: String) =
          authorizeAuth(Request.get(URL.empty).addHeader(Header.Authorization.Bearer(token))).provideEnvironment(
            ZEnvironment[CentralConfig](TestCentralConfig.config) ++ ZEnvironment[EdgeService](edges) ++
              ZEnvironment[CellRegistry](TestCentralConfig.registryOf(kz1)),
          ).exit
        for
          _ <- edges.find.succeedsWith(Some(edgeRecord))
          edge <- JWT.serialize(
            JWT.Claims("edge", "edge", List("central"), Json.Obj()),
            1.minute,
            JWT.Signature.Asymmetric(JWT.Algorithm.RS256, edgeKey.keyId, edgeKey.privateKey),
            headers = Map("edge_id" -> edgeId.toString),
          ).flatMap(viaAuth)
          cell <- signed(kz1Key).flatMap(viaAuth)
          configuredAuth <- signed(configured).flatMap(viaAuth)
        yield assertTrue(refused(edge), caller(cell).isDefined, caller(configuredAuth).isDefined)
      },
    ),
  ) @@ TestAspect.silentLogging
