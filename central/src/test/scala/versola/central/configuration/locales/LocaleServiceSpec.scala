package versola.central.configuration.locales

import org.scalamock.stubs.ZIOStubs
import zio.*
import zio.test.*

object LocaleServiceSpec extends ZIOSpecDefault, ZIOStubs:

  private val enActive   = LocaleRecord("en", "English", isDefault = true,  active = true)
  private val ruActive   = LocaleRecord("ru", "Russian", isDefault = false, active = true)
  private val frInactive = LocaleRecord("fr", "French",  isDefault = false, active = false)

  class Env:
    val repository = stub[LocaleRepository]
    val service    = LocaleService.Impl(repository)

  private def completenessResult(expectedMissing: Vector[String]): LocaleCompletenessValidator =
    new LocaleCompletenessValidator:
      override def missing(locale: String): Task[Vector[String]] = ZIO.succeed(expectedMissing)

  def spec = suite("LocaleService")(
    test("getActive returns only active locales") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, ruActive, frInactive))
        result <- env.service.getActive
      yield assertTrue(result == Vector(enActive, ruActive))
    },
    test("setDefault returns Right(()) for an existing active locale") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, ruActive))
        _ <- env.repository.setDefault.succeedsWith(())
        result <- env.service.setDefault("ru")
      yield assertTrue(
        result == Right(()),
        env.repository.setDefault.calls == List("ru"),
      )
    },
    test("setDefault returns Left(Inactive) when locale is inactive") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive, frInactive))
        result <- env.service.setDefault("fr")
      yield assertTrue(
        result == Left(SetDefaultLocaleError.Inactive),
        env.repository.setDefault.calls.isEmpty,
      )
    },
    test("setDefault returns Left(NotFound) when locale does not exist") {
      val env = new Env()
      for
        _ <- env.repository.getAll.succeedsWith(Vector(enActive))
        result <- env.service.setDefault("de")
      yield assertTrue(
        result == Left(SetDefaultLocaleError.NotFound),
        env.repository.setDefault.calls.isEmpty,
      )
    },
    test("update validates a locale before activating it") {
      val env = new Env()
      val updated = frInactive.copy(active = true)
      val service = LocaleService.Impl(env.repository, completenessResult(Vector.empty))
      for
        _ <- env.repository.getAll.succeedsWith(Vector(frInactive))
        _ <- env.repository.update.succeedsWith(())
        result <- service.update(Vector(updated), Vector.empty)
      yield assertTrue(result == ())
    },
    test("update does not persist an incomplete locale activation") {
      val env = new Env()
      val service = LocaleService.Impl(env.repository, completenessResult(Vector("client 'app' name")))
      for
        _ <- env.repository.getAll.succeedsWith(Vector(frInactive))
        result <- service.update(Vector(frInactive.copy(active = true)), Vector.empty).either
      yield assertTrue(result.isLeft, env.repository.update.calls.isEmpty)
    },
    test("update validates all newly active locales before persisting any of them") {
      val env = new Env()
      val deInactive = LocaleRecord("de", "German", isDefault = false, active = false)
      val service = LocaleService.Impl(
        env.repository,
        new LocaleCompletenessValidator:
          override def missing(locale: String): Task[Vector[String]] =
            ZIO.succeed(if locale == "de" then Vector("scope 'profile' description") else Vector.empty),
      )
      for
        _ <- env.repository.getAll.succeedsWith(Vector(frInactive, deInactive))
        result <- service.update(Vector(frInactive.copy(active = true), deInactive.copy(active = true)), Vector.empty).either
      yield assertTrue(result.isLeft, env.repository.update.calls.isEmpty)
    },
  )
