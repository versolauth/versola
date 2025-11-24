package versola.user.model

type WasCreated = WasCreated.Type

object WasCreated:
  opaque type Type <: Boolean = Boolean
  
  inline def apply(value: Boolean): WasCreated = value

