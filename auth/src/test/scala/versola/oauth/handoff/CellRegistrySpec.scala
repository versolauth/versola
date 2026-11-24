package versola.oauth.handoff

import versola.auth.TestEnvConfig
import versola.oauth.client.CentralSyncTokenService
import versola.util.RsaKeyPair
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.TestAspect.silentLogging

/** The cells an auth knows (versolauth/versola#1050): what central lists, and what an auth with no cell id never asks. */
object CellRegistrySpec extends ZIOSpecDefault:

  private val kz1 = TestEnvConfig.authKeyPair
  private val kz2 = TestEnvConfig.anotherCellKeyPair

  private def info(id: String, keys: List[Json.Obj]) =
    CellInfo(id = id, state = "open", weight = 100, regionId = "kz", publicKeys = keys)

  private def source(cells: Vector[CellInfo]): UIO[(CellSyncClient, Ref[Int])] =
    Ref.make(0).map: calls =>
      val client = new CellSyncClient:
        override def getAll = calls.update(_ + 1).as(cells)
      (client, calls)

  private def registry(config: versola.util.CoreConfig, cells: Vector[CellInfo]) =
    for
      (client, calls) <- source(cells)
      made <- CellRegistry.make(isCell = config.cellId.isDefined, source = client, config = config)
    yield (made, calls)

  private val cells = Vector(
    info("kz-1", List(kz1.toPublicJwk)),
    info("kz-2", List(kz2.toPublicJwk)),
  )

  def spec = suite("CellRegistry")(
    test("keys by cell: the keys a seal from the cell is verified with, and the key a seal for it is encrypted to") {
      for
        (made, _) <- registry(TestEnvConfig.cellCoreConfig, cells)
        keys <- made.keysOf("kz-2")
        encryption <- made.encryptionKeyOf("kz-2")
        other <- made.encryptionKeyOf("kz-1")
      yield assertTrue(
        keys.map(_.keys.getKeys.size) == Some(1),
        keys.map(_.active.id) == Some(kz2.keyId),
        encryption.map(_.getModulus) == Some(kz2.publicKey.getModulus),
        other.map(_.getModulus) == Some(kz1.publicKey.getModulus),
      )
    },
    test("a cell central does not list has no keys") {
      for
        (made, _) <- registry(TestEnvConfig.cellCoreConfig, cells)
        keys <- made.keysOf("kz-9")
        encryption <- made.encryptionKeyOf("kz-9")
      yield assertTrue(keys.isEmpty, encryption.isEmpty)
    },
    test("two keys while one is being replaced: both verify, the first is the one to encrypt to") {
      val replaced = TestEnvConfig.impostorKeyPair.copy(keyId = "kz-2-next")
      for
        (made, _) <- registry(TestEnvConfig.cellCoreConfig, Vector(info("kz-2", List(kz2.toPublicJwk, replaced.toPublicJwk))))
        keys <- made.keysOf("kz-2")
        encryption <- made.encryptionKeyOf("kz-2")
      yield assertTrue(
        keys.map(_.keys.getKeys.size) == Some(2),
        keys.exists(_.keys.getKeyByKeyId("kz-2-next") != null),
        encryption.map(_.getModulus) == Some(kz2.publicKey.getModulus),
      )
    },
    test("a cell with no key, or with only a key that is not a public RSA key, has none: nothing is sealed for it, nothing opened from it") {
      val privateJwk = Json.Obj(kz2.toPublicJwk.fields ++ Chunk("d" -> Json.Str("AQAB")))
      val ec = Json.Obj(
        "kty" -> Json.Str("EC"),
        "crv" -> Json.Str("P-256"),
        "x" -> Json.Str("f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU"),
        "y" -> Json.Str("x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"),
        "kid" -> Json.Str("ec"),
      )
      val noKid = Json.Obj(kz2.toPublicJwk.fields.filterNot(_._1 == "kid"))
      for
        (made, _) <- registry(
          TestEnvConfig.cellCoreConfig,
          Vector(info("none", Nil), info("private", List(privateJwk)), info("ec", List(ec)), info("no-kid", List(noKid))),
        )
        results <- ZIO.foreach(List("none", "private", "ec", "no-kid"))(cell => made.keysOf(cell).zip(made.encryptionKeyOf(cell)))
        all <- made.all
      yield assertTrue(results.forall(_ == (None, None)), all.map(_.id).toSet == Set("none", "private", "ec", "no-kid"))
    },
    test("the first key of the list is the one to encrypt to even when it is the one that is unusable") {
      val ec = Json.Obj(
        "kty" -> Json.Str("EC"),
        "crv" -> Json.Str("P-256"),
        "x" -> Json.Str("f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU"),
        "y" -> Json.Str("x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"),
        "kid" -> Json.Str("ec"),
      )
      for
        (made, _) <- registry(TestEnvConfig.cellCoreConfig, Vector(info("kz-2", List(ec, kz2.toPublicJwk))))
        encryption <- made.encryptionKeyOf("kz-2")
        keys <- made.keysOf("kz-2")
      yield assertTrue(encryption.isEmpty, keys.exists(_.keys.getKeyByKeyId(kz2.keyId) != null))
    },
    test("an auth with no cell id never asks central for the cells, and knows none") {
      for
        (made, calls) <- registry(TestEnvConfig.coreConfig, cells)
        keys <- made.keysOf("kz-2")
        encryption <- made.encryptionKeyOf("kz-2")
        all <- made.all
        asked <- calls.get
      yield assertTrue(keys.isEmpty, encryption.isEmpty, all.isEmpty, asked == 0)
    },
    test("after a miss the cells are read again, but not more often than MissRefreshEvery, however often a cell is missed") {
      for
        (client, calls) <- source(cells)
        made <- CellRegistry.make(isCell = true, source = client, config = TestEnvConfig.cellCoreConfig)
        atStart <- calls.get
        _ <- TestClock.adjust(1.minute)
        afterInterval <- calls.get
        _ <- made.refreshAfterMiss
        once <- calls.get
        _ <- ZIO.foreachDiscard(1 to 20)(_ => made.refreshAfterMiss)
        stillOnce <- calls.get
        _ <- TestClock.adjust(CellRegistry.MissRefreshEvery)
        _ <- made.refreshAfterMiss
        again <- calls.get
      yield assertTrue(once == afterInterval + 1, stillOnce == once, again == once + 1, atStart == 1)
    },
    test("an auth with no cell id reads nothing after a miss either") {
      for
        (client, calls) <- source(cells)
        made <- CellRegistry.make(isCell = false, source = client, config = TestEnvConfig.coreConfig)
        _ <- made.refreshAfterMiss *> made.refresh
        asked <- calls.get
      yield assertTrue(asked == 0)
    },
    test("a cell reads them once at start, and again on the configuration interval") {
      for
        (client, calls) <- source(cells)
        made <- CellRegistry.make(isCell = true, source = client, config = TestEnvConfig.cellCoreConfig)
        atStart <- calls.get
        _ <- TestClock.adjust(TestEnvConfig.cellCoreConfig.configurationCacheRefreshInterval + 1.second)
        _ <- calls.get.repeatWhile(_ < 2).timeout(5.seconds)
        later <- calls.get
      yield assertTrue(atStart == 1, later >= 2)
    },
  ).provide(Scope.default) @@ silentLogging

/** What central answers `/configuration/cells/sync` with, as an auth reads it. */
object CellSyncClientSpec extends ZIOSpecDefault:

  private def tokenService(client: Client): CentralSyncTokenService = new CentralSyncTokenService:
    override def getToken: UIO[String] = ZIO.dieMessage("Unused in test")
    override def syncRequest(request: Request): ZIO[Scope, Throwable, Response] = client.request(request)

  def spec = suite("CellSyncClient")(
    test("asks central's cells sync endpoint and reads the cells with their keys") {
      val jwk = TestEnvConfig.anotherCellKeyPair.toPublicJwk
      val body = Json.Obj(
        "cells" -> Json.Arr(
          Json.Obj(
            "id" -> Json.Str("kz-2"),
            "state" -> Json.Str("open"),
            "weight" -> Json.Num(100),
            "regionId" -> Json.Str("kz"),
            "publicKeys" -> Json.Arr(jwk),
          ),
        ),
      ).toJson
      for
        seen <- Ref.make(Option.empty[Request])
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](request => seen.set(Some(request)).as(Response.json(body))).toRoutes)
        client <- ZIO.service[Client]
        cells <- CellSyncClient.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).getAll
        request <- seen.get.someOrFail(RuntimeException("no request captured"))
      yield assertTrue(
        request.method == Method.GET,
        request.url.path.segments.toList == List("configuration", "cells", "sync"),
        cells == Vector(CellInfo(id = "kz-2", state = "open", weight = 100, regionId = "kz", publicKeys = List(jwk))),
      )
    },
    test("fails, rather than reading no cells, when central refuses or answers something else") {
      for
        _ <- TestClient.addRoutes(Handler.fromFunctionZIO[Request](_ => ZIO.succeed(Response.json("{}").status(Status.Unauthorized))).toRoutes)
        client <- ZIO.service[Client]
        result <- CellSyncClient.Impl(TestEnvConfig.cellCoreConfig, tokenService(client)).getAll.either
      yield assertTrue(result.isLeft)
    },
  ).provide(TestClient.layer) @@ silentLogging
