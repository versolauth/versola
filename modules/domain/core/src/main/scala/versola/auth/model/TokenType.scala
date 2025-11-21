package versola.auth.model

import zio.*

enum TokenType(val typ: String, val ttl: Duration):
  case AccessToken extends TokenType("at+jwt", 12.hours)
  case RefreshToken extends TokenType("rt+jwt", 90.days)
