package versola.email.model

import versola.auth.model.OtpCode
import versola.user.model.Email
import versola.util.UnitSpecBase
import zio.test.*

object EmailTemplateSpec extends UnitSpecBase:

  val spec = suite("EmailTemplate")(
    suite("verificationCode")(
      test("create verification email with correct structure") {
        val email = Email("user@example.com")
        val code = OtpCode("123456")
        
        val message = EmailTemplate.verificationCode(email, code)
        
        assertTrue(
          message.to == email,
          message.subject == "Versola - Email Verification Code",
          message.textBody.isDefined,
          message.htmlBody.isDefined
        )
      },
      
      test("include verification code in text body") {
        val email = Email("user@example.com")
        val code = OtpCode("123456")
        
        val message = EmailTemplate.verificationCode(email, code)
        
        assertTrue(
          message.textBody.exists(_.contains("123456")),
          message.textBody.exists(_.contains("15 minutes"))
        )
      },
      
      test("include verification code in HTML body") {
        val email = Email("user@example.com")
        val code = OtpCode("789012")
        
        val message = EmailTemplate.verificationCode(email, code)
        
        assertTrue(
          message.htmlBody.exists(_.contains("789012")),
          message.htmlBody.exists(_.contains("15 minutes")),
          message.htmlBody.exists(_.contains("<html>")),
          message.htmlBody.exists(_.contains("</html>"))
        )
      },
      
      test("create valid HTML structure") {
        val email = Email("user@example.com")
        val code = OtpCode("456789")
        
        val message = EmailTemplate.verificationCode(email, code)
        
        assertTrue(
          message.htmlBody.exists(_.contains("<!DOCTYPE html>")),
          message.htmlBody.exists(_.contains("<head>")),
          message.htmlBody.exists(_.contains("<body>")),
          message.htmlBody.exists(_.contains("<style>")),
          message.htmlBody.exists(_.contains("font-family"))
        )
      },
      
      test("include security message in both formats") {
        val email = Email("user@example.com")
        val code = OtpCode("654321")
        
        val message = EmailTemplate.verificationCode(email, code)
        
        assertTrue(
          message.textBody.exists(_.contains("didn't request")),
          message.htmlBody.exists(_.contains("didn't request"))
        )
      }
    )
  )
