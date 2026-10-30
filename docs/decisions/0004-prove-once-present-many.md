# 0004 — Prove once, present many times

Date: 2026-10-25. Status: accepted. **Partly implemented** (2026-10-26): registration with
the ML-DSA key in the journal, and a sample presentation inside the app. **Missing:** the
external verifier with a challenge (stage 5). Complements
[0003](0003-android-app-post-quantum-proof.md).

## Context

On the S25 the succinct proof takes 64.7 s and uses up to 2.47 GB
([measurements](../measurements/stage-2-android.md)). That is too slow and too heavy to do
every time someone asks for the age.

What was measured and does not bring it down:

- **Verifying CSCA→DSC outside the proof.** The program's cycles drop from 198,377 to
  122,622, but with paging (47,061) they add up to 169,683. That does not fit in
  2^17 = 131,072, so the proof stays in a 2^18 segment and takes the same on the Mac: 23.4 s
  against 22.8 s, for the composite proof. It would also reveal the DSC.
- **Smaller segments.** With 2^16 it drops to 1.75 GB but takes 21 minutes
  ([stage 1](../measurements/stage-1-desktop.md)).

The two RSA-4096 signatures take 152,031 of the program's 198,377 cycles (77%). That is
RISC Zero's floor with this circuit.

## Decision

The heavy proof is made **once**, when the ID card is registered. Every later use costs
only a signature.

1. **Registration.** The app generates an ML-DSA (FIPS 204) key pair for the phone. The
   STARK proof takes the public key as input and publishes it in the journal next to the
   `Claim`. It runs in the background with a progress notification.
2. **Presentation.** The verifier sends a random challenge. The phone answers with the stored
   proof and an ML-DSA signature of the challenge.
3. **Verification.** The verifier checks the proof, that the journal's CSCA is Chile's, that
   the journal's date is recent, and that the challenge's signature matches the journal's
   key.

It stays post-quantum in what the project controls: STARK plus ML-DSA.

## Accepted cost

**Presentations are linkable across verifiers.** They all receive the same proof and the same
public key. If two verifiers pool their data, they know it was the same person, even if they
do not know who. Documented as a limitation of the first version.

Ways to close it, both outside the first version:

- one key per verifier, which forces one proof per verifier;
- a short per-use proof that verifies the stored proof (RISC Zero composition). Its cost has
  not been measured.

## Open questions

- **Where the ML-DSA key lives.** *Resolved 2026-10-26.* The Android Keystore generates
  ML-DSA keys from API 37 (`KeyProperties.KEY_ALGORITHM_ML_DSA_65`, "Added in API level 37"
  in the Android reference); the S25 is on API 36. The app
  ([`DeviceKey`](../../android/app/src/main/kotlin/cl/zkage/app/DeviceKey.kt)) uses the
  Keystore on API 37+ and, otherwise, generates the ML-DSA-65 key with Bouncy Castle from a
  32-byte seed stored encrypted with a Keystore AES-256-GCM key. On the S25 that AES key
  ended up in StrongBox. Cost: while signing, the key is in the app's memory; the encrypted
  seed is useless on another phone. **Android 17** (the S25 will get it): the stored proof
  and its software key keep working after the update; the next proof uses a Keystore key,
  and only once it is stored is the software one deleted. The app picks the key by the hash
  the proof carries, so a proof that fails halfway through the switch does not break the
  previous one. On the Android 17 emulator the KeyMint is `SoftKeymaster`, which does not
  generate ML-DSA (`TRANSACTION_FAILED` with `Algorithm(ML_DSA)`, `MlDsaVariant(ML_DSA_65)`):
  the app falls back to software and says why. **The Keystore branch is still untested on a
  phone with ML-DSA**, and so is the migration; it will be seen on the S25 with Android 17.
  The journal publishes the SHA-256 of the public key (DER SubjectPublicKeyInfo), not the
  whole key (1,974 bytes): the presentation sends it along with the signature.
- **How long the proof is valid.** The journal carries the date it was made. How long a
  verifier accepts it is the verifier's call; the suggestion is 12 months, like the
  revalidation in [0002](0002-device-registry.md).
