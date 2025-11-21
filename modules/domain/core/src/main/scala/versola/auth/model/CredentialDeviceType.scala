package versola.auth.model

import zio.schema.*

enum CredentialDeviceType derives Schema:
  case SingleDevice, MultiDevice
