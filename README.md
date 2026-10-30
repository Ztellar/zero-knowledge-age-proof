# Age proof

[![Android: working](https://img.shields.io/badge/Android-working-1F7A4D?logo=android&logoColor=white)](#status)
[![iOS: not available](https://img.shields.io/badge/iOS-not%20available-8C9BBA?logo=apple&logoColor=white)](#status)
[![npm package: not published](https://img.shields.io/badge/npm%20package-not%20published-8C9BBA?logo=npm&logoColor=white)](#for-businesses-an-npm-package)
[![Proof: post-quantum STARK](https://img.shields.io/badge/proof-post--quantum%20STARK-1846C4)](docs/technical.md#what-is-post-quantum-and-what-is-not)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-1846C4)](LICENSE)

**Prove you are an adult without showing your ID card.**

Today, to prove you are 18, you hand over your ID card, and whoever looks at it sees your
name, your national ID number, your date of birth and your photo. This app does the
opposite: it reads the chip of your Chilean ID card with your phone and makes a mathematical
proof that you are over 18. Whoever receives it learns only that. They do not see your name,
your ID number, your date of birth or your photo.

## How it is used

The first time, in three steps and a few minutes:

1. **You read your ID card with the phone.** You show the back of the card to the camera (or
   type three fields) and hold the card against the phone to read its chip.
2. **You confirm it is you.** The app compares your face with the chip's photo, with a
   liveness check. Everything happens on the phone.
3. **The phone makes your proof.** It takes one to three minutes, and you can leave the app
   meanwhile.

After that, whenever you are asked for your age, you tap **Mostrar mi prueba** ("Show my
proof") and scan the QR code you are shown. The site receives only a "yes, over 18".

Nothing from your ID card leaves the phone: not the data, not the photo, not the camera
images.

The app's interface is in Spanish, for people in Chile.

## Where it would apply

Anywhere the ID card is asked for today only to check age:

- **Online shops** selling age-restricted products, such as alcohol or tobacco.
- **Event and venue entrances**: at the door, the person scans the verifier's QR code instead
  of handing over the ID card.
- **Adult content sites and apps**, without asking for a copy of the ID card or storing
  personal data.
- **Any service that must check age** and would rather not receive data it then has to
  protect.

For the verifier, the advantage is not storing personal data to protect. The proof also
says that the Civil Registry signed the document, that it is valid, and that the original
chip was next to the phone.

## What it uses

| Piece | What it does | With |
| --- | --- | --- |
| ID card chip | Holds the data signed by the Civil Registry | NFC, ICAO 9303 standard, [JMRTD](https://jmrtd.org) |
| Back-of-card reading | Fills in the chip's access data without typing it | Google ML Kit, on the phone |
| Face match | Checks that the person using the app is the one in the photo | YuNet and SFace (OpenCV Zoo), on the phone |
| Zero-knowledge proof | Shows "over 18" without revealing anything else | [RISC Zero](https://github.com/risc0/risc0) STARK, which only uses hash functions |
| Phone signature | Binds the proof to your phone and to each site | ML-DSA-65 (FIPS 204) |

**Why post-quantum:** the proof and the phone's signature resist a quantum computer. Similar
projects, such as [ZKPassport](https://github.com/zkpassport) or the
[European Commission's experimental library](https://github.com/eu-digital-identity-wallet/av-lib-android-zkp-age-icao),
use elliptic-curve cryptography, which does not. There is a limit: the Civil Registry's
signatures are RSA, and the state chooses them
([details](docs/technical.md#what-is-post-quantum-and-what-is-not)).

## Status

| Part | Status |
| --- | --- |
| Android app | **Works.** Tested with a real ID card on a Galaxy S25: it read the chip, matched the face, made the proof in 92.5 s and the web verifier accepted it (2026-10-29, protocol version 1). Tested only on that phone |
| Accessible interface (dark mode, text up to 200%, screen reader labels) | Implemented on 2026-10-29. Walked through on the S25 with the real ID card up to making the proof (2026-10-30). Still to test: reading the back of a real ID card, presenting the new proof, and using it with TalkBack |
| Web verifier | **Reference only.** Works on a local network, for demos |
| iOS | **Not available.** There is no iPhone code |
| npm package for businesses | **Built, not published, not yet tried with a phone** ([below](#for-businesses-an-npm-package)) |
| Play Store | Not published |
| Security audit | Not done |

It is an open-source, educational project, not a product
([decision 0003](docs/decisions/0003-android-app-post-quantum-proof.md)). The chosen direction
is to make the post-quantum piece reusable by others
([decision 0008](docs/decisions/0008-real-use-and-whether-to-continue.md)).

## For businesses: an npm package

> **Not published to npm yet, and not yet tried with a phone**
> ([decision 0010](docs/decisions/0010-verifier-package-npm.md)). The app and the package
> speak the same protocol version, and the package passes its tests against the test
> vectors, but no phone has sent it a proof yet.

[`zkage-verifier`](packages/verifier) lets a business accept the proof from its own web
server, without understanding the cryptography or running anything extra:

1. **`npm install`** a package that carries the project's verifier compiled to WebAssembly.
2. **Two calls in the server it already has:** one creates the challenge and the QR code for
   its page, the other checks what the phone sends and answers only "over 18: yes or no".

Checking a presentation takes about 57 ms in Node on a Mac. The wire format is specified in
[`docs/protocol.md`](docs/protocol.md), so anyone can also build their own verifier.

Each business designs its own page. What does not change is the app screen that asks
"¿Mostrar tu prueba a este sitio?" ("Show your proof to this site?"). There the person sees
which site is asking and what it will learn.

## For developers

How it works inside, what was measured, the known limits, and how to build and test are in
the [technical documentation](docs/technical.md). The wire format is in
[`docs/protocol.md`](docs/protocol.md), the decisions in [`docs/decisions/`](docs/decisions)
and the measurements in [`docs/measurements/`](docs/measurements).

## License

[Apache-2.0](LICENSE). Dependencies have their own licenses: JMRTD and SCUBA are LGPL, YuNet
is MIT and SFace Apache-2.0. The Atkinson Hyperlegible Next font is SIL OFL 1.1; its license
is in [`android/app/src/main/assets/licenses`](android/app/src/main/assets/licenses).
