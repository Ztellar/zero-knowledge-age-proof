# zkage-verifier

Accept a proof of age from your own server, without receiving any personal data.

The [age proof app](../../README.md) reads the chip of a Chilean ID card and makes a
post-quantum zero-knowledge proof that its holder is over 18. This package checks that proof
in your Node server: you learn that the person is at least the age you ask for, and nothing
else. No name, ID number, date of birth or photo reaches you.

> **Status (2026-10-30): not published to npm, and the app does not speak this protocol yet.**
> The package implements version 2 of the [protocol](../../docs/protocol.md); the app still
> speaks version 1 ([decision 0010](../../docs/decisions/0010-verifier-package-npm.md),
> step 4). Until then you can try it against the test vectors, not with a phone.

## How it works

1. Your page shows a QR code with a fresh challenge.
2. The person scans it with the app, which shows your site's name and asks them to confirm.
3. The phone POSTs its proof to your server.
4. You call `verifyAge`, and answer the phone and your page.

The verification runs inside your process, as WebAssembly compiled from the project's Rust
verifier. Nothing is sent anywhere else.

## Install

Requires Node 20 or later. While it is not on npm, build it from the repository (you need
Rust, the RISC Zero toolchain and the `wasm32-unknown-unknown` target; see the
[technical documentation](../../docs/technical.md#getting-started)):

```sh
cd packages/verifier
npm run build:wasm
npm install
npm run build
```

Then, from your project: `npm install /path/to/zero-knowledge-age-proof/packages/verifier`.

## Use

```js
import { MAX_BODY_BYTES, newAgeRequest, phoneResponse, verifyAge } from 'zkage-verifier'

const ORIGIN = 'https://shop.example'

// 1. When you show the page.
const request = newAgeRequest({ origin: ORIGIN, url: `${ORIGIN}/age/proof?s=${sessionId}` })
// Store request.challenge with the session, until request.expiresAt (5 minutes).
// Put request.qrSvg on the page.

// 2. When the phone POSTs to that url (raw body, application/octet-stream, up to MAX_BODY_BYTES).
const challenge = takeChallengeOnce(sessionId) // yours: return it once, then forget it
const result = await verifyAge(body, { origin: ORIGIN, challenge })
// { accepted: true, minAge: 18, provedOn: '2026-10-30', chipAuthenticated: true, csca: { ... } }
// or { accepted: false, reason: 'signature' }
response.json(phoneResponse(result))
```

[`examples/express`](examples/express/server.mjs) is a complete site in 74 lines.

### Your part

- **Use each challenge once.** Drop it before or right after verifying, and refuse a second
  answer (`409`). Otherwise someone who copies an answer can replay it while the challenge
  lives.
- **Expire challenges** after `CHALLENGE_TTL_SECONDS` (300).
- **Serve `url` over HTTPS**, on the same origin you pass to `newAgeRequest`. The app refuses
  anything else.
- **Pass the origin exactly as browsers write it**: `https://shop.example`, not
  `https://shop.example/` or `https://shop.example:443`. It is signed byte for byte, and
  `newAgeRequest` and `verifyAge` throw `TypeError` for any other form.

## API

### `newAgeRequest({ origin, url })`

Returns `{ challenge, link, qrSvg, expiresAt }`. `challenge` is 64 lowercase hex characters;
`link` is the `zkage://present` link the QR code carries.

### `verifyAge(body, options)`

| Option | Default | |
| --- | --- | --- |
| `origin` | required | The origin you passed to `newAgeRequest` |
| `challenge` | required | The challenge you stored for this request |
| `minAge` | `18` | Can be raised, not lowered |
| `maxProofAgeDays` | `365` | How old the proof may be. Can be shortened, not extended |
| `extraCscas` | `[]` | SHA-256 (hex) of state signing keys to trust besides Chile's |
| `now` | the current time | Dates are compared in UTC |

It always requires that the ID card's original chip was present when the proof was made.

Resolves to `{ accepted: true, minAge, provedOn, chipAuthenticated, csca }` or
`{ accepted: false, reason }`, where `reason` is one of:

| Reason | Meaning |
| --- | --- |
| `proof` | Not a valid proof of the project's program |
| `untrusted_csca` | The ID card was signed by a key you do not trust |
| `age_too_low` | The proof shows a lower age than `minAge` |
| `future_proof` | The proof is dated after `now` |
| `expired_proof` | The proof is older than `maxProofAgeDays` |
| `chip_not_authenticated` | The proof was made without the ID card's chip |
| `device_key` | The phone's key is not the one in the proof |
| `signature` | The phone signed for another site or another challenge |
| `malformed_body` | The body is not a presentation, or is larger than `MAX_BODY_BYTES` |

It throws `TypeError` for invalid options, never for an invalid proof.

### `phoneResponse(result)`

The JSON to answer the phone with, with status `200`.

### `initVerifier(source?)`

Loads the WebAssembly module. On Node it happens on the first `verifyAge`. On other runtimes,
pass the module or the bytes of `dist/age_verifier.wasm`. Bun, Deno and Cloudflare Workers
have not been tested.

## What it does not protect

Two sites can tell that two presentations come from the same person, and the face match
happens on the phone, outside the proof. See
[what the protocol does not protect](../../docs/protocol.md#8-what-the-protocol-does-not-protect).

## License

Apache-2.0. The QR code is drawn with [uqr](https://github.com/unjs/uqr) (MIT).
