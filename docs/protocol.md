# Presentation protocol, version 2

How a site asks the app for the age proof, what the phone sends and how it is verified. It is
meant for building your own verifier without reading the project's code.

**Status (2026-10-30): implemented in the app, `prover/verifier-web` and the
[`zkage-verifier`](../packages/verifier) package, but not yet tried with a phone.** Version 1,
from [decision 0005](decisions/0005-web-verifier-with-qr.md), is no longer supported. The
differences are listed at the end. The signed message, the body and the checks are the same in both versions. Version 2
comes from [decision 0010](decisions/0010-verifier-package-npm.md).

## Overview

1. The site creates a random challenge and shows a QR code with its origin, the address
   where it receives the proof, and the challenge.
2. The person scans the QR code with the app. The app shows which site is asking and, if the
   person agrees, signs the origin and the challenge with the phone's key.
3. The app sends the stored proof, the phone's public key and the signature over HTTPS to
   the address in the QR code.
4. The site verifies, answers the app, and shows the result to the person on its own page.

The site only learns that the person is at least the requested age, when the proof was
made, and which country issued the ID card. It receives no name, national ID number, date of
birth or photo: the proof does not contain them.

## 1. The QR link

```
zkage://present?v=2&origin=<origin>&url=<address>&challenge=<challenge>[&pin=<hash>]
```

Values are percent-encoded as URL components. Parameter order does not matter.

| Parameter | Required | Meaning |
| --- | --- | --- |
| `v` | yes | `2` |
| `origin` | yes | The site's origin: `https://` followed by the host and, if it is not 443, the port. No path, no `?`, no `#`, no user info. Example: `https://shop.example` |
| `url` | yes | The HTTPS address where the site receives the proof. It must start with exactly `origin` followed by `/`. It may carry a query so the site can recognize the session. Example: `https://shop.example/age/proof?s=8f2a` |
| `challenge` | yes | The challenge: 32 random bytes as lowercase hex (64 characters) |
| `pin` | no | SHA-256 of the site's TLS certificate (DER), as lowercase hex. Only for demos with a self-signed certificate |

**The challenge** comes from a cryptographically secure generator. The site stores it,
accepts **a single** answer for it, and expires it after 5 minutes at most. Otherwise anyone
who copies an answer can replay it while the challenge is alive.

**The origin** is signed exactly as it appears in the QR code. The site must verify with the
very same string it put in the QR code: `https://shop.example` and `https://shop.example:443`
are different strings, and a signature over one does not verify for the other.

## 2. What the app does

1. It rejects the link if a required parameter is missing, if `v` is not `2`, if `origin` or
   `url` is not HTTPS, or if `url` does not start with `origin` + `/`. This way a site cannot
   ask for a signature for one origin and receive it at another.
2. It shows the host of `origin` and asks the person to confirm.
3. It signs the [presentation message](#3-the-signed-message).
4. It sends the [body](#4-the-body) with `POST` to `url`, with
   `Content-Type: application/octet-stream`.
   - Without `pin`, it validates the site's certificate like a browser: against the phone's
     certificate authorities, checking the host name.
   - With `pin`, it only accepts the certificate whose SHA-256 is `pin`, and still checks
     the host name.
5. It shows the [response](#6-the-response).

## 3. The signed message

```
"zkage-presentation-v1" || 0x00 || origin length (u16 big-endian) || origin (UTF-8) || challenge (32 bytes)
```

- The signature is **ML-DSA-65** (FIPS 204), pure variant (not HashML-DSA), with an empty
  context. It is 3,309 bytes.
- The `zkage-presentation-v1` tag stays the same in protocol version 2, because the message
  is the same.
- The public key travels as a DER `SubjectPublicKeyInfo`, 1,974 bytes, with OID
  `2.16.840.1.101.3.4.3.18` (id-ml-dsa-65) and no parameters. It starts with
  `308207b2300b0609608648016503040312038207a100`.

Vector: with origin `https://192.168.1.5:8443` and a challenge of 32 bytes `0x07`, the
message is the one in
[`prover/verifier/tests/message-vector.hex`](../prover/verifier/tests/message-vector.hex).

## 4. The body

Three fields in a row, each preceded by its length as 4 bytes little-endian:

```
u32 LE | receipt | u32 LE | public key (SPKI DER) | u32 LE | signature
```

Nothing follows the third field. The site rejects bodies over 1 MiB: a real one is about
229 KB, almost all of it the receipt.

**The receipt** is RISC Zero's `risc0_zkvm::Receipt` in its *succinct* form, serialized with
`bincode` 1. It is the STARK proof the phone made once and stores. It is the same in every
presentation from that phone until it makes a new one
([decision 0004](decisions/0004-prove-once-present-many.md)).

## 5. The checks

The site rejects at the first check that fails, in this order. The code in parentheses goes
in the response.

1. **The receipt is a valid proof of the project's program** (`proof`): it deserializes and
   verifies against the program's image ID. For the program version in this repository,
   the image ID is the one in
   [`prover/fixtures/vectors/image-id.hex`](../prover/fixtures/vectors/image-id.hex). If
   the program changes, the image ID changes.
2. **The ID card was signed by an accepted state** (`untrusted_csca`): the journal's
   `csca_sha256` is in the site's CSCA list. By default, Chile's, from
   [`prover/verifier/src/chile_csca.rs`](../prover/verifier/src/chile_csca.rs).
3. **The age is enough** (`age_too_low`): the journal's `min_age` is at least the age the
   site asks for.
4. **The date is not in the future** (`future_proof`): the journal's `today` is not after
   the site's date, in UTC.
5. **The proof is not too old** (`expired_proof`): at most 365 days have passed since
   `today`, or whatever the site accepts.
6. **The chip was present** (`chip_not_authenticated`): `chip_authenticated` is true.
   [Decision 0009](decisions/0009-chip-must-be-original.md) always requires it.
7. **The key is the proof's key** (`device_key`): the SHA-256 of the public key sent is the
   journal's `device_key_sha256`, and the key is a valid ML-DSA-65 key.
8. **The signature is for this site and this challenge** (`signature`): the signature
   verifies over the presentation message built from the QR code's origin and the stored
   challenge.

If the body does not have the format of section 4, the site answers `400` before reaching
these checks.

### The journal

The journal is the public part of the proof. It comes inside the receipt, encoded with RISC
Zero's serialization: **every number takes one 32-bit little-endian word, bytes included**.
For this program it is 69 words (276 bytes), in this order:

| Field | Words | Meaning |
| --- | --- | --- |
| `csca_sha256` | 32 | SHA-256 of the DER `SubjectPublicKeyInfo` of the CSCA that signed the ID card, one byte per word |
| `today` | 3 | Year, month and day the proof was made |
| `min_age` | 1 | Minimum age proven |
| `device_key_sha256` | 32 | SHA-256 of the phone's public key, one byte per word |
| `chip_authenticated` | 1 | `1` if the chip answered Active Authentication, `0` otherwise |

## 6. The response

For a well-formed body and a live challenge, the site answers `200` with JSON:

```json
{"state": "accepted", "min_age": 18, "proved_on": "2026-10-22", "csca": "Chile 2021", "chip_authenticated": true}
```

```json
{"state": "rejected", "reason": "signature"}
```

- `csca` is a readable name for the CSCA, for the app to show. The site chooses it.
- `reason` is one of the codes in section 5. The app turns it into text for the person.

Other HTTP status codes:

| Code | When |
| --- | --- |
| `400` | The body does not have the format of section 4 |
| `404` | The site does not know the challenge, or it expired |
| `409` | The challenge was already answered |
| `413` | The body is over 1 MiB |

How the site's page learns the result (polling its server, a socket, and so on) is up to
each site.

## 7. Test vectors

They are in [`prover/fixtures/vectors/`](../prover/fixtures/vectors) and come from the test
ID card of [`prover/fixtures/gen.py`](../prover/fixtures/gen.py). They are not anyone's
personal data. The `protocol_vectors` test in
[`prover/verifier/tests/presentation.rs`](../prover/verifier/tests/presentation.rs)
regenerates and checks them.

| File | Contents |
| --- | --- |
| `presentation-body.bin` | The section 4 body: 229,073 bytes, SHA-256 `df3f4f40335f963f45f6ee7c1e36513da7cfaa65aa0d03e24677ac62ccba56e8` |
| `journal.hex` | The receipt's journal |
| `image-id.hex` | The program's image ID |

Inputs for verifying `presentation-body.bin`:

| Input | Value |
| --- | --- |
| Origin | `https://verifier.example` |
| Challenge | 32 bytes `0xc4` |
| Site's date | 2026-10-22 |
| Accepted CSCA | SHA-256 of [`prover/fixtures/out/csca_spki.der`](../prover/fixtures/out/csca_spki.der): `60708b640db1356e49e1a4e2e5ed3b6b327b60f431b38606997e03d8e595a40d` |
| Requested age | 18 |

Expected results (the tests in `presentation.rs` cover them):

| Change | Result |
| --- | --- |
| None | `accepted`, `min_age` 18, `proved_on` 2026-10-22, `chip_authenticated` true |
| Site's date 2027-10-22 | `accepted` (365 days) |
| Site's date 2027-10-23 | `expired_proof` |
| Site's date 2026-10-21 | `future_proof` |
| Requested age 21 | `age_too_low` |
| A different CSCA accepted instead of that one | `untrusted_csca` |
| Origin `https://evil.example`, or any other challenge | `signature` |
| One bit flipped in the middle of the receipt | `proof` |

## 8. What the protocol does not protect

- **Two sites can tell it is the same person**: the receipt and the phone's key are the same
  in every presentation ([decision 0004](decisions/0004-prove-once-present-many.md)).
- **An answer replayed while its challenge is alive** is accepted if the site does not mark
  the challenge as used (section 1).
- **A misissued certificate for the site's domain** would let someone receive its answers.
  It is the same risk any browser takes.
- **The face match is not in the proof**: the app does it before making the proof
  ([decision 0007](decisions/0007-face-match.md)). The site trusts that the proof was made
  by an app that matched the face; it cannot check it.

## Differences from version 1

| | Version 1 | Version 2 |
| --- | --- | --- |
| Link | `zkage://present?origin=…&session=…&challenge=…&pin=…` | `zkage://present?v=2&origin=…&url=…&challenge=…[&pin=…]` |
| Where it is sent | Always `origin + /sessions/<session>/presentation` | `url`, chosen by the site |
| Certificate | Always pinned with `pin` | Validated like a browser; `pin` only if present |
| `reason` | Text in Spanish for the person | A code; the app writes the text |
