# 0005 — Web verifier with a QR code

Date: 2026-10-26. Status: accepted. **Implemented** (2026-10-26): the `prover/verifier`
library, the `prover/verifier-web` server, and scanning and sending in the app. Tested with a
real ID card on the S25: the verifier accepted the proof. Implements the presentation of
[0004](0004-prove-once-present-many.md). Its protocol becomes version 1 of
[`docs/protocol.md`](../protocol.md); [0010](0010-verifier-package-npm.md) proposes
version 2.

## Context

The stored proof is 223,770 bytes and the ML-DSA-65 signature 3,309 bytes. A version 40 QR
code holds at most 2,953 bytes in binary mode, so the proof does not fit in a QR code: it has
to go over the network.

Options considered:

| Option | Why not |
| --- | --- |
| CLI with an exported file | The challenge is copied by hand; it is not convincing as a demo. Kept as a testing tool |
| Phone to phone over NFC or Bluetooth | The most complex, and 224 KB over NFC is slow |

## Decision

1. The verifier is a web server. Its page shows a QR code with its origin and a random
   32-byte challenge.
2. The app scans the QR code, shows whom it is about to answer and, if the person agrees,
   signs the presentation message with the phone's key and sends the proof, the public key
   and the signature over HTTPS to that origin.
3. The server verifies and the page shows the result.

The server uses HTTPS with a self-signed certificate it generates at startup, and the QR
code carries its SHA-256: the app trusts only that certificate (it pins it), not a CA. That
avoids plain HTTP, which Android blocks by default. The QR code also carries a session, and
the page polls for its result; each session lasts 5 minutes and is answered once.

The signed message is `"zkage-presentation-v1" || 0x00 || u16 origin length || origin ||
challenge`. The origin is signed so that a malicious site cannot forward another site's QR
code and use the answer (relay attack): the server rejects signatures made for another
origin.

The core is a Rust library that checks, in order:

1. the STARK receipt against the project program's image ID;
2. that the journal's CSCA hash is one of Chile's CSCAs;
3. that the journal's minimum age is at least the one the verifier asks for;
4. that the journal's date is neither in the future nor older than the verifier's validity
   window (12 months by default, as 0004 suggests);
5. that the SHA-256 of the public key matches the journal's;
6. the ML-DSA-65 signature of the presentation message.

It is used by the server and, to test without a phone, by the `mock_presentation` example
(which builds the phone's side with the test ID card).

## Accepted cost

- For the demo the server runs on the computer. Instead of the same Wi-Fi network, which on
  some networks hands out public IPs in different subnets, the phone reaches it over USB with
  `adb reverse tcp:8443 tcp:8443`, and the server listens only on `127.0.0.1`. Publishing it
  on the internet costs money and is out of scope for now.
- The browser warns that the certificate is self-signed; the app does not, because it pins
  it.
- The QR scanner is Google Play Services' (no camera permission): the app depends on a
  component that is not open source.
- If the app moves to `targetSdk` 37, Android 17 requires the local network permission to
  talk to a server on the same network.
- Presentations remain linkable across verifiers (0004).
