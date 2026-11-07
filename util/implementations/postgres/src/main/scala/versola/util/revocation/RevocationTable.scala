package versola.util.revocation

/** Where a service keeps its revocations and what it listens on to hear of them.
  *
  * Auth and edge each own a table and a `NOTIFY` channel, and their names differ because a
  * development or test setup can run both services against one database: sharing either would have
  * each service apply the other's rows. Both are part of a statement's text (a table cannot be a
  * bound parameter, and `LISTEN` takes no parameter either), so they are constants of the service,
  * never a value from a request, and are validated as plain identifiers where they are used ([[versola.util.postgres.TableName.validated]]).
  */
case class RevocationTable(name: String, channel: String)
