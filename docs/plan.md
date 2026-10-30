# Plan: post-quantum age proof with the Chilean ID card

Date: 2026-10-22.

Status: **stages 1 to 5 done** (2026-10-29). The app reads the real ID card, makes the STARK
proof on the phone with the chip's Active Authentication, and the web verifier accepts it.
What the project does today, its limits and how to run it: [README](../README.md) and the
[technical documentation](technical.md).

## Goal

A portfolio project, open source and educational
([decision 0003](decisions/0003-android-app-post-quantum-proof.md)). First version: an
Android app that reads the Chilean ID card over NFC, checks the Civil Registry's signature
and makes, on the phone, a STARK proof of "over 18", without any data leaving the phone.

## What is post-quantum and what is not

The current table is in the
[technical documentation](technical.md#what-is-post-quantum-and-what-is-not).

## What the proof proves

Private inputs: DG1 (the MRZ data), the SOD (the signed hashes and their signature) and the
DSC certificate. Public inputs: the date of day D and the key of a Chilean CSCA. Output:
"over 18 on D". Initial design; the current list of checks, with the phone's key and the
Active Authentication of decision 0006, is in the
[technical documentation](technical.md#how-it-works).

The circuit checks:

1. the DSC is signed by the CSCA (RSA);
2. the SOD is signed by the DSC (RSA);
3. DG1's hash matches the one in the SOD;
4. with the date of birth from DG1, the person is 18 or older on D;
5. the document has not expired on D.

Known pitfalls:
- **The birth year has two digits** (`YYMMDD`): the century is resolved against D.
- **D is a public input**, and the verifier rejects old dates.
- **Signature scheme:** RSA PKCS#1 v1.5, confirmed with the real ID card in stage 3.

## Stack

- **App:** native Kotlin. NFC reading with [JMRTD](https://jmrtd.org) (Java, mature,
  supports BAC and PACE).
- **Cryptographic core:** Rust, called from Kotlin through hand-written JNI
  ([`prover/ffi`](../prover/ffi/src/lib.rs)), not UniFFI.
- **Proof system, candidate:** [RISC Zero](https://github.com/risc0/risc0) (Apache-2.0). It
  is programmed in ordinary Rust and has acceleration for RSA and SHA-256. Its STARK proof
  must not be wrapped in Groth16, which is not post-quantum. **Alternative** if it does not
  run on Android: Plonky3, with a hand-written circuit.
- **Builds:** local, with Gradle. **No EAS.**

## Stages

Each stage has an exit criterion. If it is not met, the plan is reviewed before going on.

1. **Measurement on the computer.** Test ID card data: a test CSCA and DSC with RSA 4096,
   and an SOD and DG1 built like those of a TD1 ID card. STARK proof of the five points.
   *Exit:* time, RAM and proof size on the M1 Pro, in a table.
   **Done:** succinct proof in 32.8 s with 2.57 GB after optimizing (at first, 151.6 s with
   6.6 GB); it is 223,514 bytes ([measurements](measurements/stage-1-desktop.md)).
2. **The same prover on Android.** Build for `aarch64-linux-android` and run it on the S25.
   *Exit:* time and RAM on the phone. If it does not build or does not fit in memory, move
   to Plonky3.
   **Done:** builds unchanged; succinct proof in 64.7 s with 2.34–2.47 GB on the S25
   ([measurements](measurements/stage-2-android.md)). Plonky3 is not needed.
3. **Reading the real ID card.** Kotlin app with JMRTD: MRZ typed by hand, reading DG1 and
   the SOD, and checking the signature in Kotlin (no zero knowledge yet).
   *Exit:* the app shows "valid signature" with your ID card and confirms the signature
   scheme.
   **Done** (2026-10-25), with a real ID card on the S25: all four checks pass. Code in
   [`android/`](../android); 10 verifier tests with the test ID card. Real scheme, read from
   the app's screen:

   | Field | Real ID card | Test ID card (stages 1–2) |
   | --- | --- | --- |
   | Chip access | PACE | — |
   | SOD signature | SHA-256 with RSA PKCS#1 v1.5 | same |
   | DSC key | RSA 2048 | RSA 4096 |
   | CSCA that signed the DSC | CHL 2021, RSA 3072 | RSA 4096 |
   | Data group hash | SHA-256 | same |
   | DG1 / SOD size | 95 B / 2,177 B | 95 B / 2,261 B |

   The measurements of stages 1 and 2 used larger keys than the real ones, so they
   overestimate the cost of the signatures. The CSCA keys come from ZKPassport's registry,
   which only carries modulus and exponent; that is why the core takes the CSCA as a key
   (step 1 of stage 4).
4. **Integration.** The app reads the ID card and makes the proof on the phone, only once and
   in the background. The proof includes in its journal an ML-DSA public key of the phone
   ([decision 0004](decisions/0004-prove-once-present-many.md)).
   *Exit:* a proof made from a real ID card, offline, and stored in the app.
   Before starting: the test ID card now uses the real key sizes (CSCA RSA 3072, DSC
   RSA 2048); on the S25 the succinct proof takes 58–65 s with 2.46 GB
   ([key sizes and measurement](measurements/chile-key-sizes.md)).
   **Step 1 done** (2026-10-26): the core takes the CSCA as a key (DER SubjectPublicKeyInfo)
   instead of a certificate, and the `Claim` publishes its SHA-256; it accepts the SOD as it
   comes from the chip (tag 0x77). A test checks that the key Kotlin builds from modulus and
   exponent is byte-for-byte identical to OpenSSL's.
   **Step 3 done** (2026-10-26): the prover runs inside the app as a JNI library
   ([`prover/ffi`](../prover/ffi/src/lib.rs)). **First STARK proof from a real ID card** on
   the S25: 51.5 s, 223,514 bytes, verified on the phone, with the `Claim` pointing at the
   CHL 2021 CSCA. The app reached `VmHWM` 2,476,448 kB (2.54 GB) and Android did not kill
   it; a single run, with the app in the foreground and the screen on.
   **Background service done** (2026-10-26): the proof runs in a foreground service of type
   `specialUse` ([`ProverService`](../android/app/src/main/kotlin/cl/zkage/app/ProverService.kt))
   with a wake lock, a notification with a chronometer, and another one at the end. Tested
   with the real ID card while leaving the app: it finished in **135 s** (the `adb` monitor
   saw the service active from 09:16:49 to 09:19:05, 136 s), with `VmHWM` 2,588,748 kB
   (2.59 GB) and no kills. It takes 2.6 times longer than in the activity (135 / 51.5).
   Probable cause, **unverified**: on this phone the `foreground` cpuset (a foreground
   service's) uses cores 0–5 (3.53 GHz) and leaves out the two 4.47 GHz cores that `top-app`
   does have (0–7); the `background` cpuset uses only 0–1 and 4–5. Which cpuset the process
   was in during the run was not measured. A single run; tapping the notification opening
   the result was not confirmed.
   **ML-DSA key done** (2026-10-26): the journal publishes `device_key_sha256`, the SHA-256
   of the phone's ML-DSA-65 public key; where the key lives is in
   [decision 0004](decisions/0004-prove-once-present-many.md). The program grows from
   157,578 to 161,167 cycles (+3,589) and stays in one segment. At the end, the app signs a
   random challenge and verifies it against the proof's hash. First run on the S25 with the
   real ID card: a 223,770-byte proof in 94.2 s (whether the app was on screen was not
   recorded), a software key with its seed encrypted by an AES key in StrongBox, a
   3,309-byte signature in 116 ms (a single measurement, including the first class loading).
   **Android 17** (2026-10-26): API 37 arm64 emulator with 16 KB pages and 8 GB of RAM. The
   library is already 16 KB aligned (`LOAD` with `0x4000`, NDK r30) and the test ID card's
   proof runs inside the app's process in 100.9 s with `VmHWM` 2,618,208 kB
   (instrumented test `NativeProverTest`). Android 17 adds a per-app memory limit depending
   on the phone's RAM (`MemoryLimiter:AnonSwap`); on the emulator it is disabled, so **it is
   not known whether the ~2.6 GB fit under the S25's limit**. The ML-DSA key and the
   migration from software are in decision 0004. The instrumented tests delete keys and
   uninstall the app: run them only with `ANDROID_SERIAL` pointing at an emulator. The app
   stays on `targetSdk` 36; raising it to 37 requires, among other things, the local
   network permission if stage 5 talks to the verifier over Wi-Fi.
   **Missing:** the library is 87 MB and the debug APK 134 MB.
5. **Presentation, verifier and documentation.** The verifier sends a challenge and the
   phone answers with the stored proof and an ML-DSA signature. A verifier (CLI or web) and
   a README with the post-quantum map, the measurements, the linkability limitation of 0004
   and a short article.
   Transport: web server with a QR code
   ([decision 0005](decisions/0005-web-verifier-with-qr.md)).
   **Verifier library done** (2026-10-26,
   [`prover/verifier`](../prover/verifier/src/lib.rs)): it checks the receipt against the
   image ID, the CSCA, the minimum age, the validity (12 months by default), the hash of the
   phone's key and the ML-DSA-65 signature of
   `"zkage-presentation-v1" || origin || challenge`. Tests with a receipt of the test ID card
   bound to a fixed-seed demo key; one rejection per check. Verifying the receipt takes
   12.52 ms on the Mac (one measurement). Bouncy Castle and the `ml-dsa` crate derive the
   same key from the same seed (tested on both sides).
   **Web verifier done** (2026-10-26): Chile's CSCA list in Rust generated by
   `gen_csca.py --rust` (compared with Kotlin in a test), the
   [`prover/verifier-web`](../prover/verifier-web/src/main.rs) server with a QR code and a
   pinned certificate, and in the app QR scanning, origin confirmation and sending. With the
   test ID card and `curl`: accepted in 30 ms round trip; a signature for another origin and
   a second answer, rejected. **With the real ID card on the S25: accepted** ("Mayor de 18,
   cédula firmada por CHL 2021 RSA3072, prueba del 2026-10-26": over 18, ID card signed by
   CHL 2021 RSA3072, proof of 2026-10-26). Demo: `adb reverse tcp:8443 tcp:8443` and
   `cargo run --release -p verifier-web -- --host 127.0.0.1`.
   **Active Authentication checked outside the proof** (2026-10-26,
   [`ActiveAuth.kt`](../android/verifier/src/main/kotlin/cl/zkage/verifier/ActiveAuth.kt)):
   the real ID card carries DG14 and DG15; DG15 matches the SOD and is a P-256
   (prime256v1) key with ecdsa-plain-SHA256; the chip signed a random challenge and the
   signature is valid.
   **Active Authentication inside the proof done** (2026-10-26,
   [decision 0006](decisions/0006-active-authentication-in-proof.md)): the chip signs the
   start of the hash of the phone's key and the program checks it. It doubles the proof
   time; the segment size is chosen from the phone's RAM
   ([measurement](measurements/active-auth.md)). **With the real ID card on the S25**
   (2026-10-29): "Chip original (Active Authentication, P-256): sí" ("original chip: yes"), 92.5 s with the app
   open and a 2^19 segment, 223,778 bytes; the web verifier accepted it ("Mayor de 18,
   cédula firmada por CHL 2021 RSA3072, prueba del 2026-10-29, con el chip de la cédula
   presente": the same, adding "with the ID card's chip present").
   **README done** (2026-10-29): post-quantum map, measurements, limits and demo.
   **Face match done** (2026-10-29, [decision 0007](decisions/0007-face-match.md)): YuNet
   and SFace on the phone, with a liveness check, before making the proof. It is a local
   check and does not enter the proof. Tested with the real ID card on the S25.

## Risks

| Risk | What happens if it materializes |
| --- | --- |
| RISC Zero does not build or run on Android | Ruled out in stage 2: it runs on the S25 |
| Android 17 kills the app for its per-app memory limit while proving | Not measured: the emulator does not apply the limit; it will be seen on the S25 with Android 17 (`am memory-limiter status`) |
| Making the proof takes minutes on the phone | With Active Authentication it takes 92.5 s in the app and 128.6 s over `adb` on the S25; with little RAM the app uses smaller segments (366 s with 2^17 on the S25). Mid-range or older phones not measured |
| The proof is too heavy for a QR code | The verifier receives it as a file or over the network in the first version |
| Reading real ID card data in tests | The tests use only test documents; the real ID card only on the development phone |

## Background

### Chile's certificates

Verified on 2026-10-22 against
[ZKPassport's public certificate registry](https://github.com/zkpassport/zkpassport-packages/blob/main/packages/registry-explorer/public/assets/certificates/certificates.json):
4 CSCAs with `country: CHL` (out of 411 certificates and 120 countries), taken from the
Master Lists of ICAO, Germany and the Netherlands. All RSA with SHA-256.

| Valid from | Key | Signs documents until | Sources |
| --- | --- | --- | --- |
| 2024-05-28 | RSA 4096 | 2029-05-28 | ICAO, NL |
| 2021-08-01 | RSA 3072 | 2026-08-01 | ICAO, DE, NL |
| 2016-08-01 | RSA 3072 | — | ICAO, DE, NL, IT |
| 2013-08-01 | RSA 3072 | — | ICAO, DE, NL, ES |

### Trying ZKPassport (2026-10-22)

The ZKPassport app accepts a real ID card on a Samsung S25. It asks for no account, compares
the face with the chip's photo and allows several documents per phone. On the S25 it was
almost immediate, with no measured times.

### What ZKPassport already solves

Reviewed on 2026-10-22 in [zkpassport-docs](https://github.com/zkpassport/zkpassport-docs)
(commit `a41aa5f`) and [zkpassport-packages](https://github.com/zkpassport/zkpassport-packages)
(commit `fac2812`). That review did not find the app's code, and what it does was inferred
from the documentation and the SDK. **Correction (2026-10-29):** the app is published, at
[mobile-app](https://github.com/zkpassport/mobile-app) (Apache-2.0, a single push on
2026-07-17, commit `c52f5ef`). Its face match is reviewed in
[decision 0007](decisions/0007-face-match.md).

| What we designed (0001, 0002) | ZKPassport |
| --- | --- |
| One ID card, one phone | There is no phone registry. Each service receives a unique identifier per document, the same from any phone, and can ask for a face match on **every** verification |
| OPRF code | Already exists: "salted identifiers" with vOPRF, the secret split across several servers. It requires a face match |
| Face match on the phone | Already exists, with a liveness check. It requires device attestation and may refuse on some Android phones |
| Yearly revalidation | The expiry proof is valid for 7 days by default (`validity`) |
| Works on low-end phones | According to their FAQ: with RSA it uses up to 1 GB of RAM; base proofs of 10 to 50 s once, and under 1 to 10 s per verification. Not measured by us |
| QR code in person and offline | Not found: the site shows the QR code, the app scans it and the answer goes through a bridge server (`@obsidion/bridge`) |
| Proof system | UltraHonk on Barretenberg: elliptic curves, **not post-quantum** |

### Out of the first version

- Everything in decisions 0001 and 0002, on hold.
