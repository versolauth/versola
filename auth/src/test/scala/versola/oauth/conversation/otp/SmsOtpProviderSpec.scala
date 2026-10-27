package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.delivery.{ChannelUnavailable, DeliveryChannels}
import versola.util.Phone
import versola.util.delivery.SmsSender
import zio.*
import zio.test.*
import zio.test.TestAspect.silentLogging

object SmsOtpProviderSpec extends ZIOSpecDefault:

  private val phone = Phone("+12025551234")
  private val code = OtpCode("123456")
  private val template = OtpTemplate("Use {{code}} to login")

  private class RecordingSender extends SmsSender:
    val sent = Unsafe.unsafe(Ref.unsafe.make(List.empty[(Phone, String)])(using _))
    override def send(to: Phone, message: String): Task[Unit] = sent.update(_ :+ (to -> message))

  def spec = suite("SmsOtpProvider")(
    test("sendOtp renders the code into the template and hands it to the configured sender") {
      val sender = RecordingSender()
      for
        _ <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, Some(sender))).sendOtp(phone, code, template)
        sent <- sender.sent.get
      yield assertTrue(sent == List(phone -> "Use 123456 to login"))
    },
    test("send hands the message over as it is") {
      val sender = RecordingSender()
      for
        _ <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, Some(sender))).send(phone, "hello")
        sent <- sender.sent.get
      yield assertTrue(sent == List(phone -> "hello"))
    },
    test("asks the channels again for every message, so a changed provider is used without a restart") {
      val first = RecordingSender()
      val second = RecordingSender()
      for
        current <- Ref.make[Option[SmsSender]](Some(first))
        channels = new DeliveryChannels:
          override def email = ZIO.none
          override def sms: UIO[Option[SmsSender]] = current.get
          override def refresh: UIO[Unit] = ZIO.unit
        provider = SmsOtpProvider.Impl(channels)
        _ <- provider.send(phone, "one")
        _ <- current.set(Some(second))
        _ <- provider.send(phone, "two")
        sentFirst <- first.sent.get
        sentSecond <- second.sent.get
      yield assertTrue(sentFirst.map(_._2) == List("one"), sentSecond.map(_._2) == List("two"))
    },
    test("a one-time code is skipped, with a warning, when no provider is configured or the saved one is switched off") {
      val provider = SmsOtpProvider.Impl(DeliveryChannels.fixed(None, None))
      for _ <- provider.sendOtp(phone, code, template)
      yield assertCompletes
    },
    test("a message that must arrive fails, naming the channel, when no provider is configured or the saved one is switched off") {
      for result <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, None)).send(phone, "hello").either
      yield assertTrue(result.left.exists(_.isInstanceOf[ChannelUnavailable]))
    },
    test("is available exactly when a sender is configured") {
      for
        withSender <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, Some(RecordingSender()))).available
        without <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, None)).available
      yield assertTrue(withSender, !without)
    },
    test("fails when the sender fails, so a caller can undo what it stored for the message") {
      val failing = new SmsSender:
        override def send(to: Phone, message: String): Task[Unit] = ZIO.fail(RuntimeException("provider down"))
      for
        result <- SmsOtpProvider.Impl(DeliveryChannels.fixed(None, Some(failing))).send(phone, "hello").either
      yield assertTrue(result.left.map(_.getMessage) == Left("provider down"))
    },
  ) @@ silentLogging
