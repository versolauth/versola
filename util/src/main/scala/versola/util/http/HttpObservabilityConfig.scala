package versola.util.http

object HttpObservabilityConfig:

  case class Server(
      logRequestBody: Boolean,
      logResponseBody: Boolean,
      logQuery: Set[String],
      logRequestHeaders: Set[String],
      logResponseHeaders: Set[String],
  )

  object Server:
    val default = Server(
      logRequestBody = false,
      logResponseBody = false,
      logQuery = Set.empty,
      logRequestHeaders = Set.empty,
      logResponseHeaders = Set.empty,
    )

  case class Client(
      logRequestBody: Boolean,
      logResponseBody: Boolean,
      logQuery: Set[String],
      logRequestHeaders: Set[String],
      logResponseHeaders: Set[String],
  )

  object Client:
    val default = Client(
      logRequestBody = false,
      logResponseBody = false,
      logQuery = Set.empty,
      logRequestHeaders = Set.empty,
      logResponseHeaders = Set.empty,
    )
