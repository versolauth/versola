package versola.auth.model

import scala.reflect.Typeable

type AttemptsLeft = AttemptsLeft.Type

object AttemptsLeft:
  given (typeable: Typeable[Int]) => Typeable[AttemptsLeft] = typeable
  
  opaque type Type <: Int = Int
  inline def apply(value: Int): Type = value
