package versola.oauth.client.model

object Acr:

  def acrClaim(amr: Map[PassedAuthFactor, PassedFactorRecord]): Option[String] =
    if amr.size >= 2 then Some("mfa")
    else amr.keys.headOption.map {
      case PassedAuthFactor.otp      => "otp"
      case PassedAuthFactor.password => "pwd"
      case PassedAuthFactor.passkey  => "passkey"
    }

  def acrClaim(amr: Set[AuthMethodRef]): Option[String] =
    if amr.contains(AuthMethodRef.mfa) then Some("mfa")
    else if amr.contains(AuthMethodRef.hwk) || amr.contains(AuthMethodRef.swk) then Some("passkey")
    else if amr.contains(AuthMethodRef.pwd) then Some("pwd")
    else if amr.contains(AuthMethodRef.otp) then Some("otp")
    else None