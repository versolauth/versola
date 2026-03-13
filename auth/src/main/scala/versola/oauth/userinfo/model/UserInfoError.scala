package versola.oauth.userinfo.model

enum UserInfoError:
  case InvalidToken
  case InsufficientScope
  case Unauthorized

