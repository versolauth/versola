# Attestation chains made by real devices

Copied, unchanged, from the test data of Google's [`android/keyattestation`](https://github.com/android/keyattestation)
(Apache License 2.0), commit `c391708adccdeff0ac12f51e81cebfe5de42a017`, `testdata/<device>/<sdk>/<name>.pem` with
`<name>.json`, the values Google's verifier read from the chain.

| File | Device | Provisioning |
|---|---|---|
| `caiman-sdk36-TEE_EC_RKP` | Pixel 9 Pro, Android 16 | remotely provisioned (RKP) |
| `akita-sdk34-TEE_EC_NONE` | Pixel 7a, Android 14 | factory |
| `blueline-sdk28-TEE_EC_NONE` | Pixel 3, Android 9 | factory |
| `walleye-sdk27-TEE_EC_NONE` | Pixel 2, Android 8.1 | factory |

They are used by `RealAndroidChainsSpec`, the one spec whose evidence was not built from the documentation by the same reading of it that
the verifier has. None of them is a secret: an attestation chain is a public statement about a key.
