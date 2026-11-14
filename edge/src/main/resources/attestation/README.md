# Roots of trust for app attestation (#423)

Edge verifies the certificate chain of an App Attest attestation and of an Android Key Attestation against the roots in this directory.
They are public certificates published by the platforms; edge trusts nothing else for these chains.

| File | Certificate | SHA-256 of the DER |
|---|---|---|
| `apple-app-attestation-root.pem` | Apple App Attestation Root CA (expires 2045-03-15) | `1cb9823ba28ba6ad2d33a006941de2ae4f513ef1d4e831b9f7e0fa7b6242c932` |
| `android-key-attestation-roots.pem` | Google hardware attestation root, RSA 4096 (serialNumber f92009e853b6b045, expires 2042-03-15) | `cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc` |
| `android-key-attestation-roots.pem` | Google "Key Attestation CA1", ECDSA P-384 (expires 2035-07-15) | `6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0` |

Where they come from (downloaded 2026-11-13):

- Apple: <https://www.apple.com/certificateauthority/Apple_App_Attestation_Root_CA.pem>
- Google: the JSON array at <https://android.googleapis.com/attestation/root>, one PEM per element. It held these two certificates and
  no others on that day; a device whose chain ends at a root Google does not list there is refused, which `report` shows as
  `UntrustedChain` before `enforce` does.

`AttestationTrustSpec` pins the fingerprints above, so changing a file here fails the build until the table is updated on purpose.
**Check a new fingerprint against the platform's own documentation before recording it** -- the table is what a reviewer compares to, not
a proof of origin.

A platform that rotates a root before a release of edge carries it is met by `native.attestation.apple-roots` / `android-roots`, which
name a PEM file that replaces the one here (see `deploy.md`).
