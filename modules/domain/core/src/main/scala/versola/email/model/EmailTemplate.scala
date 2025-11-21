package versola.email.model

import versola.auth.model.OtpCode
import versola.user.model.Email

object EmailTemplate:
  
  def verificationCode(email: Email, code: OtpCode): EmailMessage =
    EmailMessage.multipart(
      to = email,
      from = EmailAddress("noreply@versola.com"), // This will be overridden by config
      subject = "Versola - Email Verification Code",
      textBody = textVerificationTemplate(code),
      htmlBody = htmlVerificationTemplate(code),
    )

  private def textVerificationTemplate(code: OtpCode): String =
    s"""
    |Your Versola verification code is: $code
    |
    |This code will expire in 15 minutes.
    |
    |If you didn't request this code, please ignore this email.
    |
    |Best regards,
    |Versola Team
    """.stripMargin.trim

  private def htmlVerificationTemplate(code: OtpCode): String =
    s"""
    |<!DOCTYPE html>
    |<html>
    |<head>
    |    <meta charset="UTF-8">
    |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    |    <title>Versola - Email Verification</title>
    |    <style>
    |        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px; }
    |        .header { text-align: center; margin-bottom: 30px; }
    |        .code { font-size: 32px; font-weight: bold; color: #007bff; text-align: center; padding: 20px; background-color: #f8f9fa; border-radius: 8px; margin: 20px 0; letter-spacing: 4px; }
    |        .footer { margin-top: 30px; font-size: 14px; color: #666; text-align: center; }
    |    </style>
    |</head>
    |<body>
    |    <div class="header">
    |        <h1>Email Verification</h1>
    |    </div>
    |    
    |    <p>Your Versola verification code is:</p>
    |    
    |    <div class="code">$code</div>
    |    
    |    <p>This code will expire in <strong>15 minutes</strong>.</p>
    |    
    |    <p>If you didn't request this code, please ignore this email.</p>
    |    
    |    <div class="footer">
    |        <p>Best regards,<br>Versola Team</p>
    |    </div>
    |</body>
    |</html>
    """.stripMargin.trim
