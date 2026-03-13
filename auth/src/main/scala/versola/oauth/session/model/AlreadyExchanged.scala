package versola.oauth.session.model

type RefreshAlreadyExchanged = RefreshAlreadyExchanged.Type

object RefreshAlreadyExchanged:
  opaque type Type <: Unit = Unit
  inline def apply(): Type = ()
