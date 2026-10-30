# 0006 — Active Authentication inside the proof

Date: 2026-10-26. Status: accepted. **Implemented** (2026-10-26) and tested with the test ID
card on the Mac and on the S25. **Tested with the real ID card** (S25, 2026-10-29): proof with
the chip authenticated in 92.5 s with a 2^19 segment, 223,778 bytes, accepted by the web
verifier ("…con el chip de la cédula presente", "…with the ID card's chip present").
Complements [0004](0004-prove-once-present-many.md).

## Context

The proof shows that the Civil Registry signed the ID card's data, but not that the chip was
present when it was made. DG1 and the SOD are files that can be copied: someone who read an
ID card once (with the MRZ in view) can make that person's proof on another phone. Self's
audit attributes this same weakness to them (not verified: the zkSecurity site gives a
certificate error).

Active Authentication (ICAO 9303 part 11, section 6.1) closes it: the chip signs an 8-byte
challenge with a private key that never leaves it, and the public key is in DG15, which the
SOD signs.

What the real ID card used in the tests carries (S25, 2026-10-26, read with the app):

| Item | Value |
| --- | --- |
| Data groups for Active Authentication and Chip Authentication | DG15 and DG14, both in the SOD |
| DG15 hash against the SOD | Matches |
| DG15 key | EC 256 bits, prime256v1 curve (P-256) |
| Algorithm according to DG14 | `0.4.0.127.0.7.1.1.4.1.3`, ecdsa-plain-SHA256 |
| Answer to a random challenge | 64 bytes (r‖s), valid signature |

Chip Authentication (DG14) does not help here: it sets up an encrypted channel with the chip
and leaves no signature that can be checked later, inside the proof.

## Decision

1. Before reading the ID card, the app picks the phone's ML-DSA key the proof will use (until
   now it was created afterwards, in `ProofJob`). The Active Authentication challenge is the
   first 8 bytes of `device_key_sha256`.
2. The zkVM program also receives DG15 and the chip's answer, and checks:
   - that DG15's hash is the one the SOD signs;
   - that the answer's ECDSA P-256 with SHA-256 signature is valid for those 8 bytes of
     `device_key_sha256`, which are already in the journal.
3. The journal gains a boolean `chip_authenticated`; DG15's key does not leave the proof (it
   identifies the ID card). The verifier can require it in its `Policy`, and the web verifier
   requires it unless run with `--allow-no-chip`. An ID card without DG15, or with a key that
   is not P-256, can still prove, with `chip_authenticated = false`. **Since 2026-10-29 the
   app no longer makes those proofs: [0009](0009-chip-must-be-original.md) stops it first.**
4. The program no longer fits in a 2^18 segment. The app picks the segment size from the
   phone's RAM (`SegmentSize`): the largest one whose measured peak fits in 40% of RAM. It is
   our own criterion, not a measurement.

This way the chip signs which phone will present the proof: a copy of the files is not
enough to make it.

## Accepted cost

- **The binding is 64 bits**, because ICAO fixes the challenge at 8 bytes. Reusing a chip's
  answer with another phone key requires a key whose SHA-256 starts with the same 8 bytes:
  on the order of 2^64 attempts.
- **It proves the chip's presence, not its owner's.** Whoever holds the ID card for a moment
  can make a proof on their phone. Matching the face is another decision (point 3, pending).
- **P-256 is not post-quantum**, just like the Civil Registry's RSA: it belongs to the ID
  card and cannot be changed. It is in the README's post-quantum map.
- **The proof takes twice as long and needs more memory**
  ([measurement](../measurements/active-auth.md)). The P-256 signature costs 217,412 cycles,
  even with RISC Zero's accelerated `p256`, and the program grows from 161,167 to 379,900. On
  the S25, with a 2^19 segment, that is 128.6 s and 4.41 GB, against 64.7 s and 2.34 GB
  before. A phone with less RAM uses smaller segments: 2^17 drops to 1.52 GB but takes
  366.1 s on the S25. Since the proof is made once (0004), it is accepted. The receipt and
  the verification do not change.
- The test ID card used by the tests needs its own DG15 and P-256 key
  ([the test ID card generator](../../prover/fixtures/gen.py)).
