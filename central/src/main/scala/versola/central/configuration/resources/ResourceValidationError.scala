package versola.central.configuration.resources

import versola.central.configuration.clients.ClientId

/** Why a resource was refused. Not a wire type: central answers `400` with no body and records this in its log. */
enum ResourceValidationError:
  case InvalidResourceId
  case ReservedResourceId
  case InvalidAllowExpression(endpointId: ResourceEndpointId, expression: String, message: String)
  case InvalidInjectExpression(endpointId: ResourceEndpointId, ruleName: String, expression: String, message: String)
  case InvalidStepUpConditionExpression(endpointId: ResourceEndpointId, expression: String, message: String)
  case InvalidEndpointPath(endpointId: ResourceEndpointId)

  /** The endpoint's `rateLimit` breaks a rule of a rate limit policy; `message` names the rule. */
  case InvalidRateLimit(endpointId: ResourceEndpointId, message: String)

  /** A limit set for a client of the audience breaks a rule of a rate limit policy, names a client that is not in the audience, or
    * repeats a client; `message` names the problem.
    */
  case InvalidAudienceRateLimit(clientId: ClientId, message: String)

  /** Another endpoint with the same method matches exactly the same requests, differing
    * only in its path parameter names. */
  case AmbiguousEndpointPath(endpointId: ResourceEndpointId, path: String)
