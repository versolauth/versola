package versola.auth.model

import zio.schema.*

enum AuthenticatorTransport derives Schema:
  case Ble, Cable, Hybrid, Internal, Nfc, SmartCard, Usb
