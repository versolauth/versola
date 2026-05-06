package versola.central

import javax.crypto.SecretKey

import versola.util.Secret

case class CentralConfig(
    initialize: Boolean,
    clientSecretsPepper: Secret,
    secretKey: SecretKey,
)
