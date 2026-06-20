package versola.central.configuration.resources

import zio.json.JsonCodec
import zio.schema.{Schema, derived}

enum ResourceValidationError derives JsonCodec, Schema:
  case InvalidAllowExpression(endpointId: ResourceEndpointId, expression: String, message: String)
  case InvalidInjectExpression(endpointId: ResourceEndpointId, ruleName: String, expression: String, message: String)
  case InvalidEndpointPath(endpointId: ResourceEndpointId)
