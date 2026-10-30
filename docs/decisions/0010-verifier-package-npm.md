# 0010 — An npm package so any service can verify the proof

Date: 2026-10-30. Status: **accepted, being built** (2026-10-30). Done: the protocol
specification, the WebAssembly module and the npm package (steps 1 to 3). Not built yet: the
app and `verifier-web` changes. The package is not published to npm. Chooses option B of [0008](0008-real-use-and-whether-to-continue.md) and
changes the protocol of [0005](0005-web-verifier-with-qr.md).

## Context

0005 built a demo verifier: our own server (`prover/verifier-web`) with its page, its
self-signed certificate, and a QR code that only works with it. That is no use to a shop.
The user said so on 2026-10-30: each service builds its own verification page its own way,
and what is missing is a simple way to integrate verification into an existing web service,
without installing a separate server ("no quiero que sea engorroso", "I don't want it to be
cumbersome").

The core already exists: `age_verifier::verify` (`prover/verifier/src/lib.rs`) runs the
checks. The problem is that it is in Rust, and verifying a RISC Zero receipt has no
JavaScript implementation.

**Experiment of 2026-10-30.** I compiled `age_verifier::verify`, unchanged, to WebAssembly
(`wasm32-unknown-unknown`) and called it from Node 20 on the Mac, with the test ID card's
proof (`prover/fixtures/out/receipt.bin`) and a signature made with the demo key:

| Case | Result | Time |
| --- | --- | --- |
| Valid presentation | accepted | 68 ms |
| Altered signature | `Signature` | 57 ms |
| Altered receipt | `Proof` | 5 ms |

The `.wasm` is 1,939,477 bytes unoptimized. The only change was giving `getrandom` a source
that always fails, because verifying uses no randomness. I did not try other environments
(Bun, Deno, Cloudflare Workers, browsers) or other languages.

For it to work with a real service, two rigid parts of 0005 have to change:

- **The app builds the sending address itself:** `origin + /sessions/{id}/presentation`
  (`android/verifier/src/main/kotlin/cl/zkage/verifier/Presentation.kt:25`). A shop wants
  to choose its own route.
- **The app only trusts the certificate whose SHA-256 the QR code carries**
  (`Presenter.kt:83`). A real site has a CA certificate that is renewed every few months;
  pinning it would break the app at every renewal.

## Decision

### 1. An npm package with the verifier in WebAssembly

A package the shop installs with `npm install` and uses from its own server. It has two
functions:

```js
import { newAgeRequest, verifyAge } from '<name to be decided>'

// When showing the page: creates the challenge and the QR code.
const r = newAgeRequest({ origin: 'https://shop.example', url: 'https://shop.example/age/proof' })
// r.challenge → the shop stores it in its session
// r.link      → the zkage:// link
// r.qrSvg     → the QR code, ready to put on the page

// When the phone sends the proof to that url:
const result = await verifyAge(body, { origin: 'https://shop.example', challenge, minAge: 18 })
// { accepted: true, minAge: 18, provedOn: '2026-10-30' }
// or { accepted: false, reason: 'signature' }
```

- **Inside:** a new crate, `prover/verifier-wasm`, wraps `age_verifier::verify` and compiles
  it to WebAssembly. The package ships that `.wasm` and a TypeScript layer.
- **Stateless:** the package keeps no sessions. The shop stores the challenge, deletes it on
  first use and expires it after 5 minutes, as `verifier-web` does today. The package's
  documentation explains this, because if it is not deleted, the same answer can be
  replayed within those 5 minutes.
- **Default policy:** Chile's CSCAs that the verifier already uses, 18 years, proofs up to
  12 months old, and an original chip required (0009). The shop can raise the age or shorten
  the validity, not relax the chip requirement.
- **It returns little on purpose:** accepted or not, the minimum age, the proof's date, and
  the reason if rejected. Nothing that identifies the person, because the proof does not
  contain it.
- **First environment: Node 20 or later**, the oldest supported Node line on 2026-10-30
  (Node 18 reached end of life in April 2025). Only Node 20.20.2 was tested. Bun, Deno and
  Cloudflare Workers should work with the same `.wasm`, but they are marked as untested until
  tested.

### 2. Protocol v2: the QR code says where to send, and the certificate is the normal one

The QR link becomes:

```
zkage://present?v=2&origin=https://shop.example&url=https://shop.example/age/proof&challenge=<64 hex>
```

- `url` is the address the phone sends the proof to. It must be on the same origin as
  `origin`, and the app checks it: otherwise a page could get a signature for one site and
  send it to another.
- The shop can put in `url` whatever it needs to recognize the session, for example
  `/age/proof?s=abc`.
- `pin` becomes optional. Without `pin`, the app validates the certificate like a browser.
  With `pin`, it still pins it, for the local demo with a self-signed certificate.
- The signed message and the body format do not change (0005).
- The shop's response to the phone is the JSON the app already expects (`Presenter.kt`):
  `{"state": "accepted", "min_age", "proved_on", "csca", "chip_authenticated"}` or
  `{"state": "rejected", "reason"}`, where `reason` becomes a code and the app writes the
  text. The package builds it.
- There is no compatibility with the v1 link: nobody outside the project uses it.
  `verifier-web` moves to v2.

### 3. Protocol specification

[`docs/protocol.md`](../protocol.md) describes the QR code, the signed message, the body,
the response and the checks, with test vectors from the test ID card. It lets anyone build
their own verifier without reading the code.

## Work order

1. `docs/protocol.md` with the vectors (done on 2026-10-30).
2. `prover/verifier-wasm` and its tests, against the same cases as
   `prover/verifier/tests/presentation.rs` (done on 2026-10-30). The module exports `alloc`,
   `dealloc` and `verify`, imports nothing from its host, and is 1,740,688 bytes built with
   [`build-wasm.sh`](../../prover/scripts/build-wasm.sh). Loaded in Node 20 on the Mac, it
   gave the expected result for the 12 cases tried, about 57 ms per accepted presentation.
   It also exports some 40 `sys_*` and `syscall_*` functions that `risc0-zkvm` adds for its
   own virtual machine; the module never calls them. The policy limits of this decision are
   enforced inside the module: asking for less than 18 years or more than 365 days answers
   `bad_request`.
3. The npm package (TypeScript, tests in Node) and a minimal Express example (done on
   2026-10-30, in [`packages/verifier`](../../packages/verifier)). Its 7 tests run the
   protocol's expected results through the public API. The Express example was checked with
   `curl`: a malformed body is rejected, a second answer gets `409` and an unknown session
   `404`. It has not been tried with a phone, because the app does not speak v2 yet. The
   package would be 467.4 kB compressed, 1.8 MB unpacked, almost all of it the `.wasm`.
4. The app: v2 link, `url`, normal certificate without `pin`.
5. `verifier-web` on v2, built on the package or on `verify` as today.
6. Test it end to end with the S25 and the real ID card.

Publishing to npm needs the user's account and is outside this decision: meanwhile the
package can be installed from the repository.

## Calls made while building, not by the user

- **The package name is `zkage-verifier`.** On 2026-10-30, `zkage`, `zkage-verifier`,
  `@zkage/verifier`, `age-proof-verifier` and `pq-age-verifier` were all free on npm. The
  scoped name would need an npm organization.
- **TypeScript calls the `.wasm` through plain exported functions**, without `wasm-bindgen`:
  it adds no tools and works the same in Workers.
- **The QR code is drawn with [uqr](https://github.com/unjs/uqr)** 0.1.3, MIT, with no
  dependencies.

## Rejected

| Option | Why not |
| --- | --- |
| A verification server in Docker with an API | It forces the shop to run another service. The user rejected it as cumbersome |
| A native library per language (Node with napi, Python with PyO3) | It must be compiled for each operating system and each language. The `.wasm` is a single file |
| Rewriting RISC Zero verification in JavaScript | It is the most delicate part and it is already done in Rust |
| A central service of ours that verifies for the shops | It would be an intermediary that sees every verification. It contradicts the project's idea |
| Redesigning the `verifier-web` page | Each shop builds its own. It stays only as a demo |

## Accepted cost

- **The shop stores the challenge and deletes it when used.** If it gets that wrong, an
  answer can be replayed within 5 minutes. It is still signed for its origin, so it is
  useless on another site.
- **Trusting the phone's CAs** is any browser's model: a misissued certificate for the shop's
  domain would let someone receive answers meant for it.
- **The CSCAs ship inside the package.** When a new key appears, like the missing 2026 one
  ([details](../measurements/chile-key-sizes.md)), a new version has to be published. The
  package will allow adding CSCAs from outside, so as not to depend on that.
- **If the proof's program changes**, its image ID changes and a new version of the package
  has to be published.
- RISC Zero's verification is post-quantum cryptography (hash functions). Running it as
  WebAssembly does not change that.

## What would invalidate it

- The `.wasm` not running in the environment of the first interested shops, or being too
  heavy for them (for example, Cloudflare Workers' size limit; I did not check it).
- A shop needing another language before JavaScript. The same `.wasm` can be wrapped for
  Python, Go or Java, but that has not been tested.
