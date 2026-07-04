package versola.oauth.client.model

object Acr:

  def satisfied(
      acr: String,
      amr: Map[PassedAuthFactor, PassedFactorRecord],
      equivalents: Map[PassedAuthFactor, Set[PassedAuthFactor]],
  ): Boolean =
    acr match
      case "mfa"     => amr.size >= 2
      case "pwd"     => amr.keySet.exists(_.satisfies(PassedAuthFactor.password, equivalents))
      case "otp"     => amr.keySet.exists(_.satisfies(PassedAuthFactor.otp, equivalents))
      case "passkey" => amr.keySet.exists(_.satisfies(PassedAuthFactor.passkey, equivalents))
      case _         => false
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