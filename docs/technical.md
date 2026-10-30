# Technical documentation

**Type:** technical reference · **Verified:** 2026-10-29 · For the general overview, see the
[README](../README.md).

How it works inside, what is post-quantum, what was measured, the known limits, and how to
build and test. Commands assume you start at the repository root.

## How it works

1. **Reading.** The app reads DG1 (the MRZ), DG2 (the photo), DG15 and the SOD from the chip
   over PACE, and asks the chip to sign the start of the hash of the phone's key (Active
   Authentication).
2. **Face match.** Before making the proof, the app compares the chip photo with the front
   camera, with a liveness check. An ID card without a photo stops here. It is a local check:
   it does not enter the proof and promises the verifier nothing
   ([decision 0007](decisions/0007-face-match.md)).
3. **Prove once.** The prover runs on the phone, in a background service, and stores the
   proof in the app's private storage
   ([decision 0004](decisions/0004-prove-once-present-many.md)).
4. **Present many times.** The web verifier shows a QR code with a challenge. The app scans
   it and sends the stored proof plus an ML-DSA-65 signature over
   `"zkage-presentation-v1" || origin || challenge`
   ([decision 0005](decisions/0005-web-verifier-with-qr.md); the wire format is in
   [`protocol.md`](protocol.md)).

Inside the proof, the program ([`prover/core/src/lib.rs`](../prover/core/src/lib.rs))
checks:

1. that the DSC is signed by the CSCA (RSA);
2. that the SOD is signed by the DSC (RSA);
3. that `messageDigest` covers the data group hashes;
4. that DG1's hash matches the one in the SOD;
5. that the person is at least the minimum age and the ID card is valid on the proof's date;
6. that DG15 is in the SOD and the chip's P-256 signature covers the phone's key
   ([decision 0006](decisions/0006-active-authentication-in-proof.md)). The program also
   accepts proofs without the chip, but the app no longer makes them: if the chip does not
   prove it is original, the app stops right after reading it
   ([decision 0009](decisions/0009-chip-must-be-original.md)).

The only thing that leaves the proof is the `Claim`: the CSCA hash, the date, the minimum
age, the hash of the phone's ML-DSA key, and `chip_authenticated`.

## What is post-quantum and what is not

| Layer | Algorithm | Post-quantum? |
| --- | --- | --- |
| Zero-knowledge proof | RISC Zero succinct STARK, not wrapped in Groth16 | Yes, because it only uses hash functions. It is the central piece |
| Signature on each presentation | ML-DSA-65 (FIPS 204) | Yes. On the S25 the key is a software key, with its seed encrypted by a StrongBox AES key. Keystore support for ML-DSA arrives in Android 17 (API 37) |
| Civil Registry signatures (CSCA → DSC → SOD) | RSA 2048/3072/4096 with SHA-256 | **No**, and it does not depend on this project |
| Chip Active Authentication | ECDSA P-256 | **No**: it is the ID card's key |
| Channel to the web verifier | rustls TLS with the `ring` provider and a self-signed certificate pinned by the QR code | **No**: `ring` has no ML-KEM, so the key exchange is classical. It protects the privacy of what is sent; validity comes from the proof and the signature |

A quantum computer that breaks RSA could forge an ID card and prove with it. What it could
not do is forge a proof without going through an ID card: that is the post-quantum part.

## Measured

The numbers come from a test ID card, except the last row. Each row is a single run; the
conditions are in [`docs/measurements/`](measurements).

| Where | What | Time | Peak RAM |
| --- | --- | --- | --- |
| Mac (Apple Silicon) | Proof, 2^19 segment | 59.5 s | 2.62 GB |
| Galaxy S25, over `adb` | Proof, 2^19 segment | 128.6 s | 4.41 GB |
| Galaxy S25, over `adb` | Proof, 2^18 segment | 195.4 s | 2.47 GB |
| Galaxy S25, over `adb` | Proof, 2^17 segment | 366.1 s | 1.52 GB |
| Galaxy S25, in the app | Proof with the real ID card, 2^19 segment | 92.5 s | not measured |

- The proof is 223,778 bytes. Verifying it takes 12.5 ms on the Mac and about 20 ms on the
  S25.
- The program uses 379,900 cycles. The chip's P-256 signature takes 217,412 of them
  ([measurement](measurements/active-auth.md)).
- The app picks the segment from the phone's RAM: the largest one whose peak fits in 40%
  ([`SegmentSize.kt`](../android/app/src/main/kotlin/cl/zkage/app/SegmentSize.kt)). The 40%
  is a judgment call, not a measurement.

## Known limits

- **Presentations are linkable.** Every verifier receives the same proof and the same public
  key, so two verifiers who pool their data know it was the same person, even if they do
  not know who ([decision 0004](decisions/0004-prove-once-present-many.md)).
- **The proof shows the chip was present, not that its owner used it.** The app matches the
  face against the chip photo, but anyone who builds the code without that check makes
  equally valid proofs. Also, the liveness check stops a photo, not a video or a mask
  ([decision 0007](decisions/0007-face-match.md)).
- **The binding between the chip and the phone's key is 64 bits**: the Active Authentication
  challenge is 8 bytes ([decision 0006](decisions/0006-active-authentication-in-proof.md)).
- **It has been tested on one phone only**, a Galaxy S25 with 12 GB of RAM. A mid-range or
  older phone, or Android 17's per-app memory limit, may make the proof take much longer or
  not finish: it has not been measured.
- **The debug APK is 200 MB** from a clean build. 104 MB are the prover and face match
  library, and 39 MB the face models.
- **Only Chilean ID cards are accepted**, against Chile's 4 CSCAs in
  [`ChileCsca.kt`](../android/verifier/src/main/kotlin/cl/zkage/verifier/ChileCsca.kt). The
  keys come from ZKPassport's public registry and match the ICAO master list. **A fifth key,
  from 2026, is missing**: a document signed with it is rejected as "unknown issuer"
  ([details](measurements/chile-key-sizes.md)).
- What the original design asked for beyond this (one ID card per phone, yearly
  revalidation) is on hold ([decision 0001](decisions/0001-credential-without-personal-data.md),
  [decision 0002](decisions/0002-device-registry.md)).

## Getting started

Tested on macOS with Apple Silicon. You need:

- Rust (rustup) and the RISC Zero toolchain (`rzup`), which puts `r0vm` in `~/.risc0/bin`;
- JDK 17 for Gradle;
- for Android, Homebrew's NDK (`android-ndk`) and `rustup target add aarch64-linux-android`.

**Quick check** (about 20 s on the Mac, no phone):

```sh
cd prover
cargo test --release -p age-core -p age-verifier
```

It runs 29 tests with the test ID card from
[`prover/fixtures/gen.py`](../prover/fixtures/gen.py). Do not use `RISC0_SKIP_BUILD=1` here:
with that variable the image ID is not the program's, and the presentation tests fail with
`Err(Proof)`.

**Face match** (about 3 minutes the first time, because of the build): downloads the models
and tests with public-domain portraits.

```sh
prover/scripts/download-face-models.sh
cd prover && cargo test --release -p facematch
```

**Kotlin tests**: the ID card reader, Active Authentication, ML-DSA, the chip photo, the
segment choice, the back-of-card (MRZ) reading, and the rules that stop the proof (45
tests):

```sh
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :verifier:test :app:testDebugUnitTest
```

**WebAssembly verifier** (decision 0010): the module the npm package will carry, and its
tests against the protocol's vectors.

```sh
rustup target add wasm32-unknown-unknown
prover/scripts/build-wasm.sh
cd prover && cargo test --release -p age-verifier-wasm
```

**npm package** (decision 0010): builds the TypeScript, copies the `.wasm` built above and
runs 7 tests in Node 20 or later.

```sh
cd packages/verifier && npm install && npm test
```

**Proof on the computer** (about 1 minute), with the test ID card:

```sh
cd prover
cargo run --release -p host -- succinct
```

`execute` instead of `succinct` only counts the cycles.

## Demo with an ID card and a phone

1. Download the face models, build the prover library and the app, and install it:

   ```sh
   prover/scripts/download-face-models.sh
   prover/scripts/build-android-lib.sh
   cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Start the verifier on the computer only and open a tunnel over USB:

   ```sh
   cd prover && cargo run --release -p verifier-web -- --host 127.0.0.1
   adb reverse tcp:8443 tcp:8443
   ```

   It uses `127.0.0.1` because on a network with public IPs the server would be exposed. If
   the phone shows `ConnectException: Failed to connect to /127.0.0.1:8443`, the tunnel
   dropped: `adb reverse --list` comes out empty, and running `adb reverse` again fixes it.
   The server also accepts `--trust-mock`, for the test CSCA, and `--allow-no-chip`, for
   proofs without Active Authentication.
3. In the app (its interface is in Spanish), tap "Empezar" and show the back of the ID card
   to the camera, or type the data by hand. Hold the ID card against the phone, tap
   "Comparar mi cara" and follow the instructions: the proof starts by itself once the face
   matches.
4. Open https://127.0.0.1:8443 on the computer, accept the self-signed certificate, tap
   "Mostrar mi prueba" in the app and scan the QR code.

Android's instrumented tests delete keys and uninstall the app. Run them only on an emulator
(`ANDROID_SERIAL=emulator-5554`).

## Repository map

| Folder | Contents |
| --- | --- |
| [`prover/core`](../prover/core) | The ID card checks, in `no_std` Rust. Used by the program inside the proof and by the tests |
| [`prover/methods`](../prover/methods) | The RISC Zero program (guest) and its build |
| [`prover/host`](../prover/host) | Command-line prover, for the computer and for measurements over `adb` |
| [`prover/ffi`](../prover/ffi) | The prover as a JNI library for the app |
| [`prover/verifier`](../prover/verifier), [`prover/verifier-web`](../prover/verifier-web) | The library that verifies presentations, and the web server with the QR code |
| [`prover/verifier-wasm`](../prover/verifier-wasm) | The verifier as a WebAssembly module with plain exported functions, for the npm package |
| [`prover/facematch`](../prover/facematch) | Face match and liveness, adapted from ZKPassport's code |
| [`prover/fixtures`](../prover/fixtures) | Test ID card generator and the protocol's test vectors. The keys are throwaway |
| [`packages/verifier`](../packages/verifier) | The `zkage-verifier` npm package, not published yet, with an Express example |
| [`android/app`](../android/app) | The app: back-of-card (MRZ) reading with ML Kit, NFC reading with JMRTD, face match, phone key, prover service, presentation. The interface is in [`ui/`](../android/app/src/main/kotlin/cl/zkage/app/ui) |
| [`android/verifier`](../android/verifier) | Kotlin verification of the ID card, Active Authentication and ML-DSA |

## Documentation

| Document | Type |
| --- | --- |
| [`plan.md`](plan.md) | Plan by stages and its status |
| [`protocol.md`](protocol.md) | Presentation protocol specification, version 2 (not implemented yet) |
| [`decisions/`](decisions) | Decisions, 0001 to 0010 |
| [`measurements/`](measurements) | Measurements, with date and conditions |
