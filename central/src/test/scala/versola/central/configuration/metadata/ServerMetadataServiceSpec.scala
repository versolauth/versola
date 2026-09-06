package versola.central.configuration.metadata

import org.scalamock.stubs.ZIOStubs
import versola.central.configuration.sync.SyncEvent
import versola.util.ReloadingCache
import zio.*
import zio.json.ast.Json
import zio.test.*

object ServerMetadataServiceSpec extends ZIOSpecDefault, ZIOStubs:
  private val metadataKey = "authorization_details_types_supported"

  private def metadata(fields: (String, Json)*): Json.Obj = Json.Obj(fields*)

  class Env(initial: Option[ServerMetadataRecord]):
    val cache = ReloadingCache(Unsafe.unsafe(unsafe ?=> Ref.unsafe.make(initial)))
    val repository = stub[ServerMetadataRepository]
    val service = ServerMetadataService.Impl(cache, repository)

  def spec = suite("ServerMetadataService")(
    test("updates supported authorization detail types in stored metadata") {
      val existing = metadata(
        "issuer" -> Json.Str("https://issuer.example"),
        metadataKey -> Json.Arr(Json.Str("payment")),
      )
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for
        _ <- env.repository.get.succeedsWith(Some(ServerMetadataRecord("default", existing)))
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("account", SyncEvent.Op.INSERT)
        cached <- env.cache.get
      yield assertTrue(
        env.repository.upsert.calls.headOption.flatMap(_.get(metadataKey).flatMap(_.as[Set[String]].toOption)) ==
          Some(Set("account", "payment")),
        env.repository.upsert.calls.headOption.flatMap(_.get("issuer")) ==
          Some(Json.Str("https://issuer.example")),
        cached == Some(ServerMetadataRecord("default", existing)),
      )
    },
    test("removes the last supported authorization detail type") {
      val existing = metadata(
        "issuer" -> Json.Str("https://issuer.example"),
        metadataKey -> Json.Arr(Json.Str("payment")),
      )
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for
        _ <- env.repository.get.succeedsWith(Some(ServerMetadataRecord("default", existing)))
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("payment", SyncEvent.Op.DELETE)
      yield assertTrue(
        env.repository.upsert.calls == List(
          metadata(
            "issuer" -> Json.Str("https://issuer.example"),
            metadataKey -> Json.Arr(),
          ),
        ),
      )
    },
    test("adds the first type when no metadata has been stored yet") {
      val env = new Env(None)

      for
        _ <- env.repository.get.succeedsWith(None)
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("account", SyncEvent.Op.INSERT)
      yield assertTrue(
        env.repository.upsert.calls == List(metadata(metadataKey -> Json.Arr(Json.Str("account")))),
      )
    },
    test("adds a type when the existing metadata has no configured types yet") {
      val existing = metadata("issuer" -> Json.Str("https://issuer.example"))
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for
        _ <- env.repository.get.succeedsWith(Some(ServerMetadataRecord("default", existing)))
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.updateAuthorizationDetailType("account", SyncEvent.Op.INSERT)
      yield assertTrue(
        env.repository.upsert.calls == List(
          metadata(
            "issuer" -> Json.Str("https://issuer.example"),
            metadataKey -> Json.Arr(Json.Str("account")),
          ),
        ),
      )
    },
    test("getMetadata returns the cached metadata") {
      val existing = metadata("issuer" -> Json.Str("https://issuer.example"))
      val env = new Env(Some(ServerMetadataRecord("default", existing)))

      for result <- env.service.getMetadata
      yield assertTrue(result.contains(existing))
    },
    test("getMetadata returns None when nothing is cached") {
      val env = new Env(None)

      for result <- env.service.getMetadata
      yield assertTrue(result.isEmpty)
    },
    test("upsertMetadata delegates to repository") {
      val env = new Env(None)
      val newMetadata = metadata("issuer" -> Json.Str("https://issuer.example"))

      for
        _ <- env.repository.upsert.succeedsWith(())
        _ <- env.service.upsertMetadata(newMetadata)
      yield assertTrue(env.repository.upsert.calls == List(newMetadata))
    },
    test("sync reloads the cache from the repository") {
      val env = new Env(None)
      val record = ServerMetadataRecord("default", metadata("issuer" -> Json.Str("https://issuer.example")))

      for
        _ <- env.repository.get.succeedsWith(Some(record))
        _ <- env.service.sync()
        cached <- env.cache.get
      yield assertTrue(cached.contains(record))
    },
  )
