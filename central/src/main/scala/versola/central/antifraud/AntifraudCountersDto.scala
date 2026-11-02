package versola.central.antifraud

import versola.central.users.UserId
import versola.util.JsonStringSchema
import zio.json.JsonCodec
import zio.schema.{Schema, derived}

/** Which counters a reset clears: the velocity counters, the SMS budget, or both. */
enum CounterScope derives JsonCodec:
  case velocity, budget, all

object CounterScope:
  given Schema[CounterScope] = JsonStringSchema.of

/** The kind of the value a reset is narrowed to. */
enum CounterValueType derives JsonCodec:
  case phone, ip, prefix

object CounterValueType:
  given Schema[CounterValueType] = JsonStringSchema.of

/** Clears counters, all of the scope's or those of one value (`valueType` and `value` go together; auth validates and hashes a
  * phone). `userId` is the admin, which edge puts in from the verified token (see [[AntifraudCountersController]]); the same
  * body goes to auth, which records it. */
case class ResetCountersRequest(
    scope: CounterScope,
    valueType: Option[CounterValueType],
    value: Option[String],
    userId: UserId,
) derives JsonCodec, Schema

/** `generation` is the number auth gave the reset, which every instance picks up and clears its memory for. */
case class ResetCountersResponse(generation: Long) derives JsonCodec, Schema
