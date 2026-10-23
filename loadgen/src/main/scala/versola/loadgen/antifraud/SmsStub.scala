package versola.loadgen.antifraud

import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.{Duration, Scope, ZIO, ZLayer}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*

/** The SMS provider `auth` is pointed at (`otp-provider`): it counts what it is asked to send, per range of
  * numbers, and keeps the last code each number was sent so that the scenarios can enter it, as a phone would.
  *
  * Expects the body `auth` renders from `otp-provider.body`: `{"phone": "{{phone}}", "message": "{{message}}"}`.
  */
final class SmsStub(codeLength: Int, rangeLength: Int, responseDelay: Duration):
  private val total = LongAdder()
  private val ranges = ConcurrentHashMap[String, LongAdder]()
  private val codes = ConcurrentHashMap[String, String]()
  private val codePattern = Pattern.compile(s"(?<!\\d)\\d{$codeLength}(?!\\d)")

  /** What has been asked to be sent since the process started. */
  def sent: Long = total.sum

  def sentByRange: Map[String, Long] = ranges.asScala.view.mapValues(_.sum).toMap

  /** The last code sent to `phone`, if one went to it. */
  def codeFor(phone: String): Option[String] = Option(codes.get(digits(phone)))

  def accept(phone: String, message: String): Unit =
    val number = digits(phone)
    total.increment()
    ranges.computeIfAbsent(number.take(rangeLength), _ => LongAdder()).increment()
    val matcher = codePattern.matcher(message)
    if matcher.find() then codes.put(number, matcher.group())

  private def digits(phone: String): String = phone.filter(_.isDigit)

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.POST / "sms" -> handler { (request: Request) =>
        request.body.asString.orElseSucceed("").flatMap: body =>
          val parsed = body.fromJson[Json.Obj].toOption
          def field(name: String): Option[String] =
            parsed.flatMap(_.get(name)).collect { case Json.Str(value) => value }
          (field("phone"), field("message")) match
            case (Some(phone), Some(message)) =>
              ZIO.succeed(accept(phone, message)) *> ZIO.sleep(responseDelay).as(Response.ok)
            case _ => ZIO.succeed(Response.status(Status.BadRequest))
      },
      Method.GET / "sms" / "stats" -> handler { (_: Request) =>
        ZIO.succeed(Response.text(s"sent=${sent}"))
      },
    )

object SmsStub:
  /** Serves the stub on the configured port for as long as the scope lives. */
  def serve(stub: SmsStub, config: SmsStubConfig): ZIO[Scope, Throwable, Unit] =
    (Server.install(stub.routes) *> ZIO.logInfo(s"SMS stub listening on ${config.bindHost}:${config.port}") *> ZIO.never)
      .provide(Server.live, ZLayer.succeed(Server.Config.default.binding(config.bindHost, config.port)))
      .forkScoped
      .unit
