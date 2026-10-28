package versola.oauth.conversation.otp

import versola.auth.model.OtpCode
import versola.oauth.conversation.otp.model.OtpTemplate
import versola.oauth.delivery.{ChannelUnavailable, DeliveryChannels}
import versola.util.Email
import versola.util.delivery.EmailSender
import zio.*
import zio.test.*
import zio.test.TestAspect.silentLogging

object EmailOtpProviderSpec extends ZIOSpecDefault:

  private val email = Email("user@example.com")
  private val code = OtpCode("123456")
  private val template = OtpTemplate("Your code is {{code}}")

  /** Records what it is asked to send. */
  private class RecordingSender extends EmailSender:
    val sent = Unsafe.unsafe(Ref.unsafe.make(List.empty[(Email, String)])(using _))
    override def send(to: Email, message: String): Task[Unit] = sent.update(_ :+ (to -> message))

  def spec = suite("EmailOtpProvider")(
    test("sendOtp renders the code into the template and hands it to the configured sender") {
      val sender = RecordingSender()
      for
        _ <- EmailOtpProvider.Impl(DeliveryChannels.fixed(Some(sender), None)).sendOtp(email, code, template)
        sent <- sender.sent.get
      yield assertTrue(sent == List(email -> "Your code is 123456"))
    },
    test("send hands the message over as it is") {
      val sender = RecordingSender()
      for
        _ <- EmailOtpProvider.Impl(DeliveryChannels.fixed(Some(sender), None)).send(email, "<p>hello</p>")
        sent <- sender.sent.get
      yield assertTrue(sent == List(email -> "<p>hello</p>"))
    },
    test("asks the channels again for every message, so a changed server is used without a restart") {
      val first = RecordingSender()
      val second = RecordingSender()
      for
        current <- Ref.make[Option[EmailSender]](Some(first))
        channels = new DeliveryChannels:
          override def email: UIO[Option[EmailSender]] = current.get
          override def sms = ZIO.none
          override def refresh: UIO[Unit] = ZIO.unit
        provider = EmailOtpProvider.Impl(channels)
        _ <- provider.send(email, "one")
        _ <- current.set(Some(second))
        _ <- provider.send(email, "two")
        sentFirst <- first.sent.get
        sentSecond <- second.sent.get
      yield assertTrue(sentFirst.map(_._2) == List("one"), sentSecond.map(_._2) == List("two"))
    },
    test("a one-time code is skipped, with a warning, when no SMTP server is configured or the saved one is switched off") {
      val provider = EmailOtpProvider.Impl(DeliveryChannels.fixed(None, None))
      for _ <- provider.sendOtp(email, code, template)
      yield assertCompletes
    },
    test("a message that must arrive fails, naming the channel, when no SMTP server is configured or the saved one is switched off") {
      for result <- EmailOtpProvider.Impl(DeliveryChannels.fixed(None, None)).send(email, "hello").either
      yield assertTrue(result.left.exists(_.isInstanceOf[ChannelUnavailable]))
    },
    test("passes a failure of the sender on") {
      val failing = new EmailSender:
        override def send(to: Email, message: String): Task[Unit] = ZIO.fail(RuntimeException("smtp down"))
      for
        result <- EmailOtpProvider.Impl(DeliveryChannels.fixed(Some(failing), None)).send(email, "hello").either
      yield assertTrue(result.left.map(_.getMessage) == Left("smtp down"))
    },
  ) @@ silentLogging
