package versola.central.configuration.resources

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

enum ResourceValidationError derives JsonCodec, Schema:
  case InvalidResourceId
  case ReservedResourceId
  case InvalidAllowExpression(endpointId: ResourceEndpointId, expression: String, message: String)
  case InvalidInjectExpression(endpointId: ResourceEndpointId, ruleName: String, expression: String, message: String)
  case InvalidStepUpConditionExpression(endpointId: ResourceEndpointId, expression: String, message: String)
  case InvalidEndpointPath(endpointId: ResourceEndpointId)

  /** The endpoint's `rateLimit` breaks a rule of a rate limit policy; `message` names the rule. */
  case InvalidRateLimit(endpointId: ResourceEndpointId, message: String)

  /** Another endpoint with the same method matches exactly the same requests, differing
    * only in its path parameter names. */
  case AmbiguousEndpointPath(endpointId: ResourceEndpointId, path: String)
