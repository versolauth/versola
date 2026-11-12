package versola.e2e.support

/** Who a spec says it is when it calls central's admin API itself, standing in for edge: central refuses a call that names no actor, and
  * a client credentials token's subject is the client, so the actor is a client (`actorId` and `actorClientId` are the same). */
object E2EActor:
  val clientId = "e2e"

  val query: List[(String, String)] = List("actorId" -> clientId, "actorClientId" -> clientId)
