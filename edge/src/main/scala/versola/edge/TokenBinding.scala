package versola.edge

import versola.edge.model.Confirmation

/** Where the caller put the token. Only the cookie is edge's own copy of it -- the two header
  * schemes carry whatever the caller chose to send -- which is what tells a certificate-bound
  * token apart. */
enum Presentation:
  case Cookie, Bearer, Dpop

/** What a presented token obliges edge to do before honouring it. */
enum BindingCheck:
  /** Nothing further to prove. */
  case Accept

  /** RFC 9449 §7.1: a proof of this key (`cnf.jkt`) has to be shown and verified. */
  case VerifyProof(jkt: String)

  /** Bound to a key but presented without a proof of it (RFC 9449 §7.2). */
  case Downgrade

  /** RFC 8705 §3: bound to a certificate, which edge cannot see outside its own session cookie. */
  case CertificateOutsideSession

  /** A proof for a token that was never bound to a key says nothing (RFC 9449 §7.1). */
  case UnboundDpop

/** The binding rule shared by the proxy (`EdgeService`) and edge's own endpoints (`authorize`),
  * so one token cannot be refused on one route and honoured on the other. It only decides; each
  * caller runs the proof check and shapes the refusal for its own response type.
  */
object TokenBinding:
  def check(confirmation: Option[Confirmation], presentation: Presentation): BindingCheck =
    (confirmation.flatMap(_.jkt), confirmation.flatMap(_.certificateThumbprint), presentation) match
      // Bound to both: the key is only half of what the client proved at issuance, and the
      // certificate half cannot be shown here, so a valid proof must not be enough (#452).
      case (Some(_), Some(_), Presentation.Dpop) => BindingCheck.CertificateOutsideSession
      case (Some(jkt), _, Presentation.Dpop) => BindingCheck.VerifyProof(jkt)
      case (Some(_), _, _) => BindingCheck.Downgrade
      // The session cookie is edge's own copy, and the certificate is one edge still holds.
      case (None, Some(_), Presentation.Cookie) => BindingCheck.Accept
      case (None, Some(_), _) => BindingCheck.CertificateOutsideSession
      case (None, None, Presentation.Dpop) => BindingCheck.UnboundDpop
      case (None, None, _) => BindingCheck.Accept
