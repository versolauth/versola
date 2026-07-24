package versola.central.configuration.forms

import com.augustnagro.magnum.magzio.TransactorZIO
import versola.util.DatabaseSpecBase
import zio.ZIO
import zio.test.*
import zio.test.Assertion.*
import zio.duration.*

trait FormRepositorySpec extends DatabaseSpecBase[FormRepositorySpec.Env]:
  self: ZIOSpec[TransactorZIO] =>

  override def testCases(env: FormRepositorySpec.Env) =
    List(
      test("upsertForm persists js source, style and localizations and find returns them") {
        val formId = FormId("credential")
        val localizations = Map(
          "en" -> Map("title" -> "Sign in"),
          "ru" -> Map("title" -> "Вход"),
        )
        for
          _ <- env.repository.upsertForm(formId, ".a{}", Some("src"), Some("compiled"), localizations, Vector.empty, activate = true)
          found <- env.repository.find(formId, 1)
        yield assertTrue(
          found == Some(FormRecord(formId, 1, true, ".a{}", Some("src"), Some("compiled"), localizations, Vector.empty)),
        )
      },
      test("upsertForm increments version and keeps only the newest versions") {
        val formId = FormId("credential")
        for
          _ <- ZIO.foreachDiscard(1 to 6)(i => env.repository.upsertForm(formId, "", Some(s"src$i"), None, Map.empty, Vector.empty, activate = true))
          all <- env.repository.getAll
          versions = all.filter(_.id == formId).map(_.version).sorted
          first <- env.repository.find(formId, 1)
          latest <- env.repository.find(formId, 6)
        yield assertTrue(
          versions == Vector(2, 3, 4, 5, 6),
          first.isEmpty,
          latest.map(_.jsSource) == Some(Some("src6")),
          latest.map(_.active) == Some(true),
        )
      },
      test("first version is active, subsequent versions are not") {
        val formId = FormId("credential")
        for
          _ <- env.repository.upsertForm(formId, "", Some("v1"), None, Map.empty, Vector.empty, activate = false)
          _ <- env.repository.upsertForm(formId, "", Some("v2"), None, Map.empty, Vector.empty, activate = false)
          v1 <- env.repository.find(formId, 1)
          v2 <- env.repository.find(formId, 2)
        yield assertTrue(
          v1.map(_.active) == Some(true),
          v2.map(_.active) == Some(false),
        )
      },
      test("upsertForm with activate=true switches the active version") {
        val formId = FormId("credential")
        for
          _ <- env.repository.upsertForm(formId, "", Some("v1"), None, Map.empty, Vector.empty, activate = false)
          _ <- env.repository.upsertForm(formId, "", Some("v2"), None, Map.empty, Vector.empty, activate = true)
          v1 <- env.repository.find(formId, 1)
          v2 <- env.repository.find(formId, 2)
        yield assertTrue(
          v1.map(_.active) == Some(false),
          v2.map(_.active) == Some(true),
        )
      },
      test("upsertForm handles concurrent calls without duplicate key errors") {
        val formId = FormId("concurrent-test")
        val numConcurrent = 5
        // Run multiple upsertForm calls concurrently
        val concurrentUpdates = ZIO.foreachPar(1 to numConcurrent) { i =>
          env.repository.upsertForm(
            formId,
            s"style$i",
            Some(s"source$i"),
            Some(s"compiled$i"),
            Map("en" -> Map("title" -> s"Form $i")),
            Vector.empty,
            activate = true
          )
        }
        for
          _ <- concurrentUpdates
          // Verify we don't have duplicate key errors (test would fail if we did)
          all <- env.repository.getAll
          versions = all.filter(_.id == formId).map(_.version).distinct.sorted
        yield assertTrue(
          // We should have exactly numConcurrent versions (no duplicates from concurrent inserts)
          versions.length == numConcurrent,
          // Versions should be sequential starting from 1
          versions.head == 1,
          versions.last == numConcurrent,
          versions == (1 to numConcurrent).toList
        )
      }
    )

object FormRepositorySpec:
  case class Env(repository: FormRepository)
