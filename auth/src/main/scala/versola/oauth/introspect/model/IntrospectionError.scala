package versola.oauth.introspect.model

sealed trait IntrospectionError

object IntrospectionError:
  case class Unauthenticated() extends IntrospectionError
