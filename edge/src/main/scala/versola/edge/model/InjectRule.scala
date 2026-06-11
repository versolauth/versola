package versola.edge.model

import zio.json.JsonCodec

enum InjectTarget derives JsonCodec:
  case header, query, body

case class InjectRule(
    target: InjectTarget,
    name: String,
    expression: String,
) derives JsonCodec
